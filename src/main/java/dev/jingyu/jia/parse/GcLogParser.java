package dev.jingyu.jia.parse;

import dev.jingyu.jia.model.GcEvent;
import dev.jingyu.jia.model.GcLog;
import dev.jingyu.jia.model.TextSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GC log → normalised {@link GcEvent} timeline, for both families of format:
 *
 * <ul>
 *   <li>JDK 9+ unified {@code -Xlog:gc*} (bracketed decorators, one line per record,
 *   {@code GC(n)} ids used to merge the start/heap/phases records back into one event)</li>
 *   <li>JDK 8 traditional {@code -XX:+PrintGCDetails} (timestamped {@code 1.234: [GC …}]
 *   openers followed by indented detail lines, closed by {@code [Times: …]})</li>
 * </ul>
 */
public final class GcLogParser {

    private static final Pattern DECORATOR = Pattern.compile("^\\[([^\\]]*)\\]");
    private static final Pattern UPTIME_TOKEN = Pattern.compile("^(\\d+(?:\\.\\d+)?)s$");
    private static final Pattern WALL_TOKEN =
            Pattern.compile("^\\d{4}[-/]\\d{2}[-/]\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}");
    private static final Pattern GC_ID = Pattern.compile("^GC\\((\\d+)\\)\\s*(.*)$");
    private static final Pattern HEAP_TRANSITION = Pattern.compile(
            "(\\d+(?:\\.\\d+)?\\s*[KMGT]?B?)\\s*->\\s*(\\d+(?:\\.\\d+)?\\s*[KMGT]?B?)\\s*\\(\\s*(\\d+(?:\\.\\d+)?\\s*[KMGT]?B?)\\s*\\)");
    private static final Pattern HEAP_KW = Pattern.compile(
            "Heap:\\s*(\\d+(?:\\.\\d+)?\\s*[KMGT]?B?)\\s*->\\s*(\\d+(?:\\.\\d+)?\\s*[KMGT]?B?)\\s*\\(\\s*(\\d+(?:\\.\\d+)?\\s*[KMGT]?B?)\\s*\\)");
    private static final Pattern PAUSE_MS = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)\\s*ms\\b");
    private static final Pattern PAUSE_SECS = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)\\s+secs");
    private static final Pattern OLD_REGIONS = Pattern.compile("Old regions:\\s*(\\d+)\\s*->\\s*(\\d+)");
    private static final Pattern REGION_SIZE = Pattern.compile("Region Size:\\s*(\\d+(?:\\.\\d+)?\\s*[KMGT]?B?)");
    private static final Pattern MAX_HEAP = Pattern.compile("Max Heap Size:\\s*(\\d+(?:\\.\\d+)?\\s*[KMGT]?B?)");
    private static final Pattern METASPACE = Pattern.compile(
            "Metaspace:\\s*(\\d+(?:\\.\\d+)?\\s*[KMGT]?B?)\\s*->\\s*(\\d+(?:\\.\\d+)?\\s*[KMGT]?B?)");
    private static final Pattern OLD_GEN_K = Pattern.compile(
            "\\[(?:ParOldGen|PSOldGen|Tenured Generation|CMS Old Gen):\\s*\\d+(?:\\.\\d+)?\\s*[KMGT]?B?\\s*->\\s*(\\d+(?:\\.\\d+)?\\s*[KMGT]?B?)");
    private static final Pattern PAREN = Pattern.compile("\\(([^()]*)\\)");
    /** A bracketed size, e.g. the {@code (256M)} capacity or {@code (1056768K)} metaspace commit. */
    private static final Pattern CAPACITY_PAREN = Pattern.compile("\\d+(?:\\.\\d+)?\\s*[KMGT]?B?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern METASPACE_GROUP = Pattern.compile("\\[?Metaspace:.*?\\](?:,|\\s|$)");

    /**
     * {@code [Metaspace: 3072K->3072K(1056768K)]} has exactly the shape of a heap transition, and it
     * is the last one on the line — without this the metaspace commit becomes "heap capacity".
     */
    static String withoutMetaspace(String line) {
        return METASPACE_GROUP.matcher(line).replaceAll(" ");
    }

    // JDK 8 traditional opener: optional date stamp, uptime, then [GC / [Full GC.
    private static final Pattern TRAD_START = Pattern.compile(
            "^(?:(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?[+-]\\d{4}):\\s+)?(\\d+(?:\\.\\d+)?):\\s+\\[(Full GC|GC)\\b(.*)$");
    private static final Pattern TRAD_START_NO_TS = Pattern.compile(
            "^(\\d+(?:\\.\\d+)?):\\s+\\[(Full GC|GC)\\b(.*)$");

    private GcLogParser() {
    }

    public static ParseReport<GcLog> parse(TextSource src) {
        List<String> lines = src.lines();
        boolean unified = looksUnified(lines);
        List<String> notes = new ArrayList<>();
        GcLog log = unified ? parseUnified(src, lines, notes) : parseTraditional(src, lines, notes);
        if (log.events().isEmpty()) {
            notes.add("no GC events recognised in " + src.name()
                    + (unified ? " (unified format)" : " (traditional format)"));
        }
        return ParseReport.of(log, notes);
    }

    static boolean looksUnified(List<String> lines) {
        int scanned = 0;
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            if (++scanned > 400) {
                break;
            }
            String s = line.strip();
            if (s.startsWith("[") && s.contains("][")
                    && (s.contains("][gc") || s.contains("][info][") || s.contains("][warning][gc"))) {
                return true;
            }
            if (TRAD_START.matcher(s).matches() || TRAD_START_NO_TS.matcher(s).matches()) {
                return false;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- unified

    private record Decorators(List<String> tokens, String message) {
    }

    private static Decorators stripDecorators(String line) {
        List<String> tokens = new ArrayList<>();
        String rest = line;
        while (rest.startsWith("[")) {
            Matcher m = DECORATOR.matcher(rest);
            if (!m.find()) {
                break;
            }
            tokens.add(m.group(1));
            rest = rest.substring(m.end()).stripLeading();
        }
        return new Decorators(tokens, rest);
    }

    private static GcLog parseUnified(TextSource src, List<String> lines, List<String> notes) {
        Map<Integer, Ev> pending = new LinkedHashMap<>();
        List<Integer> order = new ArrayList<>();
        GcLog.Collector collector = GcLog.Collector.UNKNOWN;
        Long regionSize = null;
        Long maxHeap = null;
        Long bootEpochMillis = null;
        double lastUptime = 0.0;

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            Decorators d = stripDecorators(line.strip());
            double atSec = lastUptime;
            String wall = null;
            boolean taggedGc = false;
            for (String tok : d.tokens()) {
                Matcher up = UPTIME_TOKEN.matcher(tok);
                if (up.matches()) {
                    atSec = Double.parseDouble(up.group(1));
                    lastUptime = atSec;
                    continue;
                }
                if (WALL_TOKEN.matcher(tok).find()) {
                    wall = tok;
                    continue;
                }
                String t = tok.toLowerCase(Locale.ROOT);
                if (t.equals("info") || t.equals("debug") || t.equals("trace") || t.equals("warning")
                        || t.equals("error")) {
                    continue;
                }
                if (t.startsWith("gc")) {
                    taggedGc = true;
                }
            }
            String msg = d.message();
            if (bootEpochMillis == null && wall != null) {
                Long ms = Epochs.toMillis(wall);
                if (ms != null) {
                    bootEpochMillis = ms - (long) (atSec * 1000.0);
                }
            }
            String lower = msg.toLowerCase(Locale.ROOT);
            if (lower.startsWith("using ")) {
                collector = GcLog.Collector.guess(msg);
                continue;
            }
            if (lower.startsWith("version:") || lower.startsWith("cpus:") || lower.startsWith("memory limits")) {
                continue;
            }
            Matcher mh = MAX_HEAP.matcher(msg);
            if (mh.find()) {
                maxHeap = Sizes.parseBytes(mh.group(1));
                continue;
            }
            Matcher rs = REGION_SIZE.matcher(msg);
            if (rs.find()) {
                regionSize = Sizes.parseBytes(rs.group(1));
            }

            Matcher gid = GC_ID.matcher(msg);
            String inner = msg;
            Integer id = null;
            if (gid.matches()) {
                id = Integer.parseInt(gid.group(1));
                inner = gid.group(2);
            } else if (!taggedGc) {
                continue;
            }
            if (id == null) {
                // Records without a GC id (rare) get a synthetic one so nothing is lost.
                id = 100_000 + i;
            }
            Ev ev = pending.get(id);
            if (ev == null) {
                ev = new Ev(id, i + 1);
                pending.put(id, ev);
                order.add(id);
            }
            ev.absorbUnified(inner, atSec, wall, i + 1);
            ev.applyRegionSizes(regionSize);
        }

        List<GcEvent> events = new ArrayList<>();
        int seq = 0;
        for (Integer id : order) {
            Ev ev = pending.get(id);
            if (ev.pauseMs == null && ev.heapAfter == null) {
                continue;
            }
            events.add(ev.toEvent(seq++));
        }
        if (collector == GcLog.Collector.UNKNOWN) {
            notes.add("collector not identified from GC log; rules fall back to format-agnostic reasoning");
        }
        return new GcLog(src, collector, true, events, notes, bootEpochMillis != null ? bootEpochMillis : null);
    }

    // ------------------------------------------------------------ traditional

    private static GcLog parseTraditional(TextSource src, List<String> lines, List<String> notes) {
        List<GcEvent> events = new ArrayList<>();
        List<String> block = new ArrayList<>();
        String opener = null;
        int openLine = 0;
        double lastAt = 0;
        Long bootEpochMillis = null;
        GcLog.Collector collector = GcLog.Collector.UNKNOWN;
        int seq = 0;

        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i);
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            Matcher m = TRAD_START.matcher(line);
            boolean isStart = m.matches();
            if (!isStart) {
                Matcher m2 = TRAD_START_NO_TS.matcher(line);
                if (m2.matches()) {
                    m = m2;
                    isStart = true;
                }
            }
            if (isStart) {
                if (opener != null) {
                    seq = flushTrad(opener, block, openLine, events, seq, collector);
                }
                String wall = null;
                double at;
                if (m.groupCount() == 4) {
                    wall = m.group(1);
                    at = Double.parseDouble(m.group(2));
                } else {
                    at = Double.parseDouble(m.group(1));
                }
                lastAt = at;
                if (bootEpochMillis == null && wall != null) {
                    Long ms = Epochs.toMillis(wall);
                    if (ms != null) {
                        bootEpochMillis = ms - (long) (at * 1000.0);
                    }
                }
                opener = line;
                openLine = i + 1;
                block = new ArrayList<>();
                String lower = line.toLowerCase(Locale.ROOT);
                if (collector == GcLog.Collector.UNKNOWN) {
                    if (lower.contains("psyounggen") || lower.contains("paroldgen")) {
                        collector = GcLog.Collector.PARALLEL;
                    } else if (lower.contains("g1")) {
                        collector = GcLog.Collector.G1;
                    } else if (lower.contains("cms")) {
                        collector = GcLog.Collector.CMS;
                    } else if (lower.contains("defnew")) {
                        collector = GcLog.Collector.SERIAL;
                    }
                }
                continue;
            }
            if (opener == null) {
                if (line.toLowerCase(Locale.ROOT).startsWith("command line flags")
                        || line.toLowerCase(Locale.ROOT).startsWith("attime:")) {
                    continue;
                }
                if (line.startsWith("Open JDK") || line.startsWith("Java HotSpot")) {
                    continue;
                }
                if (notes.size() < 5) {
                    notes.add("ignored line without GC stamp: " + truncate(line));
                }
                continue;
            }
            block.add(line);
            if (line.contains("[Times:") || line.endsWith("]")) {
                if (line.contains("[Times:")) {
                    seq = flushTrad(opener, block, openLine, events, seq, collector);
                    opener = null;
                    block = new ArrayList<>();
                }
            }
        }
        if (opener != null) {
            flushTrad(opener, block, openLine, events, seq, collector);
        }
        return new GcLog(src, collector, false, events, notes, bootEpochMillis);
    }

    private static int flushTrad(String opener,
                                 List<String> block,
                                 int lineNo,
                                 List<GcEvent> events,
                                 int seq,
                                 GcLog.Collector collector) {
        String all = opener + "\n" + String.join("\n", block);
        Matcher start = TRAD_START.matcher(opener);
        boolean dated = start.matches();
        if (!dated) {
            start = TRAD_START_NO_TS.matcher(opener);
            dated = start.matches();
        }
        double at = 0;
        String wall = null;
        String gcToken = "GC";
        String tail = opener;
        if (dated) {
            if (start.groupCount() == 4) {
                wall = start.group(1);
                at = Double.parseDouble(start.group(2));
            } else {
                at = Double.parseDouble(start.group(1));
            }
            gcToken = start.group(start.groupCount() - 1);
            tail = start.group(start.groupCount());
        }
        boolean full = gcToken.equalsIgnoreCase("Full GC");
        GcEvent.Kind kind;
        String lower = all.toLowerCase(Locale.ROOT);
        if (full) {
            kind = GcEvent.Kind.FULL;
        } else if (lower.contains("(mixed)") || lower.contains("pause young (mixed)")) {
            kind = GcEvent.Kind.MIXED;
        } else if (lower.contains("initial mark") || lower.contains("remark")) {
            kind = GcEvent.Kind.REMARK;
        } else if (lower.contains("concurrent reset") || lower.contains("concurrent-mark")) {
            kind = GcEvent.Kind.CYCLE;
        } else {
            kind = GcEvent.Kind.YOUNG;
        }

        Double pause = null;
        Matcher p = PAUSE_SECS.matcher(opener);
        if (p.find()) {
            pause = Double.parseDouble(p.group(1)) * 1000.0;
        }

        Long before = null;
        Long after = null;
        Long cap = null;
        Matcher hk = HEAP_KW.matcher(all);
        if (hk.find()) {
            before = Sizes.parseBytes(hk.group(1));
            after = Sizes.parseBytes(hk.group(2));
            cap = Sizes.parseBytes(hk.group(3));
        } else {
            Matcher ht = HEAP_TRANSITION.matcher(withoutMetaspace(opener));
            Long b = null;
            Long a = null;
            Long c = null;
            while (ht.find()) {
                b = Sizes.parseBytes(ht.group(1));
                a = Sizes.parseBytes(ht.group(2));
                c = Sizes.parseBytes(ht.group(3));
            }
            before = b;
            after = a;
            cap = c;
        }
        Long oldAfter = null;
        Matcher og = OLD_GEN_K.matcher(all);
        if (og.find()) {
            oldAfter = Sizes.parseBytes(og.group(1));
        }
        Long metaAfter = null;
        Matcher ms = METASPACE.matcher(all);
        if (ms.find()) {
            metaAfter = Sizes.parseBytes(ms.group(2));
        }
        String cause = null;
        Matcher par = PAREN.matcher(tail);
        if (par.find()) {
            cause = par.group(1);
        }
        boolean toSpace = lower.contains("to-space exhausted") || lower.contains("evacuation failure")
                || lower.contains("promotion failed");
        boolean humongous = lower.contains("humongous");

        events.add(new GcEvent(seq, kind, cause, at, pause, before, after, cap, oldAfter, metaAfter,
                toSpace, humongous, wall, lineNo));
        return seq + 1;
    }

    /** Mutable accumulator keyed by the log's own {@code GC(n)} id. */
    private static final class Ev {
        private final int id;
        private final int firstLine;
        private double atSec;
        private String wall;
        private int lastLine;
        private GcEvent.Kind kind = GcEvent.Kind.OTHER;
        private String cause;
        private Double pauseMs;
        private Long heapBefore;
        private Long heapAfter;
        private Long capacity;
        private Long oldAfter;
        private Long oldToRegions;
        private Long metaspaceAfter;
        private boolean toSpaceExhausted;
        private boolean humongous;

        Ev(int id, int line) {
            this.id = id;
            this.firstLine = line;
            this.lastLine = line;
        }

        void absorbUnified(String msg, double atSec, String wall, int lineNo) {
            this.atSec = Math.max(this.atSec, atSec);
            if (wall != null) {
                this.wall = wall;
            }
            this.lastLine = lineNo;
            String m = msg.strip();
            String lower = m.toLowerCase(Locale.ROOT);
            if (lower.startsWith("pause full")) {
                kind = GcEvent.Kind.FULL;
            } else if (lower.contains("pause young (mixed)") || lower.startsWith("pause mixed")) {
                kind = GcEvent.Kind.MIXED;
            } else if (lower.startsWith("pause young")) {
                kind = GcEvent.Kind.YOUNG;
            } else if (lower.contains("pause remark")) {
                kind = GcEvent.Kind.REMARK;
            } else if (lower.contains("pause cleanup")) {
                kind = GcEvent.Kind.CLEANUP;
            } else if (lower.contains("concurrent mark cycle") || lower.contains("conc-mark")) {
                kind = GcEvent.Kind.CYCLE;
            }
            if (lower.contains("to-space exhausted") || lower.contains("evacuation failure")
                    || lower.contains("promotion failed")) {
                toSpaceExhausted = true;
            }
            if (lower.contains("humongous")) {
                humongous = true;
            }
            Matcher p = PAUSE_MS.matcher(m);
            Double last = null;
            while (p.find()) {
                last = Double.parseDouble(p.group(1));
            }
            if (last != null) {
                pauseMs = last;
            }
            Matcher hk = HEAP_KW.matcher(m);
            if (hk.find()) {
                heapBefore = Sizes.parseBytes(hk.group(1));
                heapAfter = Sizes.parseBytes(hk.group(2));
                capacity = Sizes.parseBytes(hk.group(3));
            } else {
                Matcher ht = HEAP_TRANSITION.matcher(withoutMetaspace(m));
                if (ht.find()) {
                    heapBefore = Sizes.parseBytes(ht.group(1));
                    heapAfter = Sizes.parseBytes(ht.group(2));
                    capacity = Sizes.parseBytes(ht.group(3));
                }
            }
            Matcher meta = METASPACE.matcher(m);
            if (meta.find()) {
                metaspaceAfter = Sizes.parseBytes(meta.group(2));
            }
            Matcher or = OLD_REGIONS.matcher(m);
            if (or.find()) {
                oldToRegions = Long.parseLong(or.group(2));
            }
            Matcher par = PAREN.matcher(m);
            String lastParen = null;
            while (par.find()) {
                String cand = par.group(1);
                // "(240M->150M(256M))" ends in a capacity, not a cause.
                if (CAPACITY_PAREN.matcher(cand).matches()) {
                    continue;
                }
                lastParen = cand;
            }
            // "Pause Young (Concurrent Start) (G1 Evacuation Pause)": the cause is the last bracket.
            if (lastParen != null && !lastParen.equalsIgnoreCase("Normal") && !lastParen.equalsIgnoreCase("Mixed")
                    && !lastParen.equalsIgnoreCase("Concurrent Start") && !lastParen.equalsIgnoreCase("Prepare Mixed")) {
                cause = lastParen;
            } else if (cause == null && lastParen != null) {
                cause = lastParen;
            }
        }

        void applyRegionSizes(Long regionSize) {
            if (regionSize != null && oldToRegions != null) {
                oldAfter = oldToRegions * regionSize;
            }
        }

        GcEvent toEvent(int seq) {
            return new GcEvent(seq, kind, cause, atSec, pauseMs, heapBefore, heapAfter, capacity,
                    oldAfter, metaspaceAfter, toSpaceExhausted, humongous, wall, firstLine);
        }
    }

    private static String truncate(String s) {
        return s.length() <= 80 ? s : s.substring(0, 80) + "…";
    }
}
