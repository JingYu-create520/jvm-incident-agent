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
    /**
     * The old generation under each collector's own name. CMS prints its own as bare {@code [CMS:} in
     * the Full GC line — {@code [CMS Old Gen:} is what the unified format's {@code [gc,heap]} tag uses
     * — and the two must not be confused with {@code [CMS Perm:} or {@code [CMS-concurrent-mark:},
     * which is why the alternation is anchored on the colon and {@code CMS Old Gen} is tried first.
     */
    private static final Pattern OLD_GEN_K = Pattern.compile(
            "\\[(?:ParOldGen|PSOldGen|Tenured Generation|CMS Old Gen|CMS):\\s*\\d+(?:\\.\\d+)?\\s*[KMGT]?B?\\s*->\\s*(\\d+(?:\\.\\d+)?\\s*[KMGT]?B?)");
    private static final Pattern PAREN = Pattern.compile("\\(([^()]*)\\)");
    /**
     * A bracketed number that is a measurement rather than a reason: the {@code (256M)} capacity,
     * the {@code (1056768K)} metaspace commit, and ZGC's {@code (99%)} occupancy shares. Left in
     * place, "cause 2%" is what the report says instead of the real cause on the same line.
     */
    private static final Pattern CAPACITY_PAREN = Pattern.compile("\\d+(?:\\.\\d+)?\\s*[KMGT]?B?%?",
            Pattern.CASE_INSENSITIVE);
    /**
     * A bracketed accounting block that names a pool which is not the heap. Metaspace on JDK 8+, and
     * the permanent generation under its three real spellings on JDK 7 — {@code [CMS Perm: …]},
     * {@code [CMS Perm : …]} (some builds print the space) and {@code [PSPermGen: …]}.
     */
    private static final Pattern NON_HEAP_POOL = Pattern.compile(
            "\\[?\\s*(?:Metaspace|CMS Perm|PSPermGen|Perm)\\s*:.*?\\](?:,|\\s|$)");

    /**
     * {@code [Metaspace: 3072K->3072K(1056768K)]} and {@code [CMS Perm: 21402K->21400K(21504K)]} have
     * exactly the shape of a heap transition, and they are the <em>last</em> one on the line — left in
     * place, the permanent generation's commit becomes "heap capacity". Measured: a CMS log whose heap
     * held 31 M of 491 M after every Full GC reported {@code GCA003 CRITICAL, "21M, 100% of the heap
     * still live after a Full GC … (capacity 21M)"} — every number in that sentence was PermGen.
     */
    static String withoutNonHeapPool(String line) {
        return NON_HEAP_POOL.matcher(line).replaceAll(" ");
    }

    /**
     * ZGC prints occupancy as a share of the heap instead of a capacity in brackets:
     * {@code 1014M(99%)->1012M(99%)}. The percentage is the only thing that turns that into a
     * usable ceiling, and the capacity itself arrives on a separate line (see MAX_CAPACITY).
     */
    private static final Pattern PCT_TRANSITION = Pattern.compile(
            "(\\d+(?:\\.\\d+)?\\s*[KMGT]?B?)\\(\\s*\\d+(?:\\.\\d+)?%\\s*\\)\\s*->\\s*"
                    + "(\\d+(?:\\.\\d+)?\\s*[KMGT]?B?)\\(\\s*\\d+(?:\\.\\d+)?%\\s*\\)");
    /** {@code GC(88) Max Capacity: 1024M(100%)} — what ZGC's percentages are percentages of. */
    private static final Pattern MAX_CAPACITY = Pattern.compile(
            "\\bMax Capacity(?: \\((?:small|medium|large)\\))?:\\s*(\\d+(?:\\.\\d+)?\\s*[KMGT]?B?)");
    /**
     * A {@code [gc,stats]} table row: {@code Collector: Garbage Collection Cycle  30.142 / 613.231
     * … ms}. Rolling averages over a window, printed per collection, and none of them is a pause.
     */
    private static final Pattern STATS_ROW = Pattern.compile(
            "^[A-Za-z][A-Za-z ]{2,48}:.*\\d+(?:\\.\\d+)?\\s*/\\s*\\d+(?:\\.\\d+)?");

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
        /** The first few hundred messages, kept only to identify the collector when no banner is present. */
        List<String> vocabulary = new ArrayList<>();
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
            if (vocabulary.size() < 300) {
                vocabulary.add(inner);
            }
            ev.applyRegionSizes(regionSize);
        }

        List<GcEvent> events = new ArrayList<>();
        int seq = 0;
        for (Integer id : order) {
            Ev ev = pending.get(id);
            if (ev.stopTheWorldMs() == null && ev.heapAfter == null) {
                continue;
            }
            events.add(ev.toEvent(seq++));
        }
        if (collector == GcLog.Collector.UNKNOWN) {
            // A rotated or truncated log routinely starts after its own "Using …" line, and the
            // collector is not a detail to leave unknown: which collection counts as "major" — i.e.
            // whether the live-set rules have anything to read at all — is decided from it. The
            // pause vocabulary identifies the collector as reliably as the banner does.
            collector = GcLog.Collector.guess(String.join("\n", vocabulary));
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
        // The collection's own stop-the-world time is the LAST "N secs" before the [Times: …] tail.
        // Every earlier one is a phase: a ParNew line reports the young collection and then the whole
        // collection, and a CMS Final Remark reports Rescan, weak refs, class unloading and two scrubs
        // before its total. Taking the first turn made a 13.345 ms remark a 9.102 ms one, and made
        // "real=0.03 secs" — wall time, two significant digits — a pause when a line had no other.
        String measured = opener;
        int timesAt = opener.indexOf("[Times:");
        if (timesAt >= 0) {
            measured = opener.substring(0, timesAt);
        }
        Matcher p = PAUSE_SECS.matcher(measured);
        while (p.find()) {
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
            Matcher ht = HEAP_TRANSITION.matcher(withoutNonHeapPool(opener));
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
        boolean humongous = lower.contains("humongous allocation");

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
        /** The stop-the-world number, split three ways — see {@link #stopTheWorldMs()}. */
        private Double summaryPauseMs;
        private double phasePauseSum;
        private boolean sawPhasePause;
        private Double otherPauseMs;
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
            if (STATS_ROW.matcher(m).find()) {
                // [gc,stats] prints a rolling-averages table per collection. Its "30.142 / 613.231
                // … ms" cells are not pauses and not events, and the largest of them (a max over the
                // last 10 hours!) was being read as this collection's stop-the-world time.
                return;
            }
            if (lower.startsWith("allocation stall")) {
                // ZGC's substitute for "the heap could not keep up": it stopped the allocating thread.
                kind = GcEvent.Kind.ALLOCATION_STALL;
            } else if (lower.startsWith("pause full")) {
                kind = GcEvent.Kind.FULL;
            } else if (lower.contains("pause young (mixed)") || lower.startsWith("pause mixed")) {
                kind = GcEvent.Kind.MIXED;
            } else if (lower.startsWith("pause young")) {
                kind = GcEvent.Kind.YOUNG;
            } else if (lower.contains("pause remark")) {
                kind = GcEvent.Kind.REMARK;
            } else if (lower.contains("pause cleanup")) {
                kind = GcEvent.Kind.CLEANUP;
            } else if (lower.contains("concurrent mark cycle") || lower.contains("conc-mark")
                    || lower.startsWith("garbage collection")) {
                // ZGC names its whole-heap cycle "Garbage Collection (<cause>)" and prints the
                // occupancy on that same line; it is the cycle, not a pause, and under ZGC it is the
                // event whose "after" number is the live set.
                kind = GcEvent.Kind.CYCLE;
            }
            if (lower.contains("to-space exhausted") || lower.contains("evacuation failure")
                    || lower.contains("promotion failed")) {
                toSpaceExhausted = true;
            }
            if (lower.contains("humongous allocation")) {
                // The *cause* phrase ("Pause Young (Normal) (G1 Humongous Allocation)"), never a bare
                // "humongous": Shenandoah's [gc,ergo] free-space accounting prints
                // "Max: 512K regular, 902M humongous" on nearly every cycle, and counting that as
                // "1086 collections triggered by humongous allocation" told a Shenandoah user that
                // their large objects were being promoted to old gen — which is not even a thing
                // that collector does.
                humongous = true;
            }
            Matcher p = PAUSE_MS.matcher(m);
            Double last = null;
            while (p.find()) {
                last = Double.parseDouble(p.group(1));
            }
            if (last != null) {
                if (lower.startsWith("pause ") && m.contains("->")) {
                    // The record's own summary — G1 writes "Pause Young (Normal) (G1 Evacuation
                    // Pause) 150M->30M(256M) 5.123ms". One number for the whole stop, so it wins.
                    summaryPauseMs = last;
                } else if (lower.startsWith("pause ")) {
                    // A phase of a stop. ZGC splits one into "Pause Mark Start 0.007ms",
                    // "Pause Mark End 0.018ms" and "Pause Relocate Start 0.009ms"; the mutator lost
                    // the sum of them, so that is what this record's pause is.
                    phasePauseSum += last;
                    sawPhasePause = true;
                } else if (kind == GcEvent.Kind.ALLOCATION_STALL) {
                    otherPauseMs = last;
                }
                // Everything else that ends in milliseconds is the collector working while the
                // application kept running — "Concurrent Mark Cycle 512.123ms" included. Adding
                // those to the pause total is how a ZGC heap whose real stop-the-world time was
                // under one second got reported as 63.4% of wall time spent in pauses.
            }
            Matcher mc = MAX_CAPACITY.matcher(m);
            if (mc.find() && capacity == null) {
                capacity = Sizes.parseBytes(mc.group(1));
            }
            Matcher hk = HEAP_KW.matcher(m);
            if (hk.find()) {
                heapBefore = Sizes.parseBytes(hk.group(1));
                heapAfter = Sizes.parseBytes(hk.group(2));
                capacity = Sizes.parseBytes(hk.group(3));
            } else {
                Matcher ht = HEAP_TRANSITION.matcher(withoutNonHeapPool(m));
                if (ht.find()) {
                    heapBefore = Sizes.parseBytes(ht.group(1));
                    heapAfter = Sizes.parseBytes(ht.group(2));
                    capacity = Sizes.parseBytes(ht.group(3));
                } else {
                    Matcher pct = PCT_TRANSITION.matcher(withoutNonHeapPool(m));
                    if (pct.find()) {
                        heapBefore = Sizes.parseBytes(pct.group(1));
                        heapAfter = Sizes.parseBytes(pct.group(2));
                    }
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

        /**
         * What this record took away from the application, in milliseconds. A summary line wins,
         * then the sum of the record's own {@code Pause …} phases, then anything else that was
         * measured in milliseconds against a stopped thread.
         */
        Double stopTheWorldMs() {
            if (summaryPauseMs != null) {
                return summaryPauseMs;
            }
            if (sawPhasePause) {
                return phasePauseSum;
            }
            return otherPauseMs;
        }

        GcEvent toEvent(int seq) {
            return new GcEvent(seq, kind, cause, atSec, stopTheWorldMs(), heapBefore, heapAfter, capacity,
                    oldAfter, metaspaceAfter, toSpaceExhausted, humongous, wall, firstLine);
        }
    }

    private static String truncate(String s) {
        return s.length() <= 80 ? s : s.substring(0, 80) + "…";
    }
}
