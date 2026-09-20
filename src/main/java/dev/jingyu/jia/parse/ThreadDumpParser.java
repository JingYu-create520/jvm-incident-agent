package dev.jingyu.jia.parse;

import dev.jingyu.jia.model.Frame;
import dev.jingyu.jia.model.JThread;
import dev.jingyu.jia.model.LockRef;
import dev.jingyu.jia.model.TextSource;
import dev.jingyu.jia.model.ThreadDump;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * jstack / {@code jcmd Thread.print} text → {@link ThreadDump} model.
 *
 * <p>Written as an explicit state machine rather than one giant regex, because the
 * format has drifted between JDK 8 and 21 (the {@code cpu=}/{@code elapsed=} and
 * module-qualified frame prefixes are JDK 11+; the deadlock trailer repeats thread
 * stanzas that must not be counted twice). Anything unrecognised is skipped and
 * reported as a note instead of failing the whole file.
 */
public final class ThreadDumpParser {

    private static final Pattern DATE_HEADER =
            Pattern.compile("^\\d{4}[-/]\\d{2}[-/]\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}[^\\n]*$");
    private static final Pattern FULL_DUMP = Pattern.compile("^Full thread dump (.+?)?:\\s*$");
    private static final Pattern THREAD_HEADER = Pattern.compile("^\"(.*?)\"\\s*(.*)$");
    private static final Pattern STATE_LINE =
            Pattern.compile("^\\s*java\\.lang\\.Thread\\.State:\\s*(\\w+)\\s*(?:\\(([^)]*)\\))?\\s*$");
    private static final Pattern FRAME_LINE = Pattern.compile("^\\s*at\\s+(\\S+)\\((.*?)\\)\\s*(?:~\\[.*\\])?\\s*$");
    private static final Pattern LOCK_LINE = Pattern.compile(
            "^\\s*-\\s*(locked|waiting to lock|waiting on|waiting to re-lock in wait\\(\\)|parking to wait for)"
                    + "\\s+<(0x[0-9a-fA-F]+)>\\s*(?:\\(a\\s+(.*?)\\))?\\s*$");
    private static final Pattern OWNABLE_LINE =
            Pattern.compile("^\\s*-\\s+<(0x[0-9a-fA-F]+)>\\s*(?:\\(a\\s+(.*?)\\))?\\s*$");
    private static final Pattern DEADLOCK_FOUND =
            Pattern.compile("^Found (one|another) Java-level deadlock", Pattern.CASE_INSENSITIVE);    private static final Pattern DEADLOCK_COUNT =
            Pattern.compile("^Found (\\d+) deadlocks?\\.", Pattern.CASE_INSENSITIVE);
    private static final Pattern OWNABLE_HEADER =
            Pattern.compile("^\\s*Locked ownable synchronizers:\\s*$");
    private static final Pattern ATTR_INDEX = Pattern.compile("#(\\d+)");
    private static final Pattern ATTR_PRIO = Pattern.compile("\\bprio=(\\d+)");
    private static final Pattern ATTR_OS_PRIO = Pattern.compile("\\bos_prio=(\\d+)");
    private static final Pattern ATTR_CPU = Pattern.compile("\\bcpu=([0-9.]+)ms");
    private static final Pattern ATTR_ELAPSED = Pattern.compile("\\belapsed=([0-9.]+)s");
    private static final Pattern ATTR_TID = Pattern.compile("\\btid=(0x[0-9a-fA-F]+)");
    private static final Pattern ATTR_NID = Pattern.compile("\\bnid=(0x[0-9a-fA-Fx]+)");

    private ThreadDumpParser() {
    }

    public static ParseReport<List<ThreadDump>> parse(TextSource src) {
        List<ThreadDump> dumps = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Build cur = new Build(src);
        boolean inOwnableSection = false;
        boolean inTrailer = false;
        int skippedTopLevel = 0;

        List<String> lines = src.lines();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int lineNo = i + 1;
            String trimmed = line.strip();

            Matcher full = FULL_DUMP.matcher(trimmed);
            if (full.matches()) {
                if (cur.hasContent()) {
                    dumps.add(cur.finish());
                }
                cur = new Build(src);
                inTrailer = false;
                inOwnableSection = false;
                cur.vmDescription = full.group(1);
                continue;
            }
            if (DATE_HEADER.matcher(trimmed).matches()) {
                if (cur.hasContent() || inTrailer) {
                    // A second capture appended to the same file.
                    dumps.add(cur.finish());
                    cur = new Build(src);
                    inTrailer = false;
                    inOwnableSection = false;
                }
                cur.capturedAtRaw = trimmed;
                continue;
            }
            if (trimmed.startsWith("Date:") || trimmed.startsWith("JVM:") || trimmed.isEmpty()) {
                continue;
            }
            // JDK 11+ prints an SMR block and sometimes a GC-thread section before the threads.
            if (trimmed.startsWith("Threads class SMR info:") || trimmed.startsWith("_java_thread_list=")
                    || trimmed.startsWith("Garbage Collector Threads") || trimmed.startsWith("JNI global refs:")
                    || trimmed.startsWith("No compile task") || trimmed.startsWith("0x")
                    || trimmed.startsWith("}")) {
                continue;
            }

            Matcher dlFound = DEADLOCK_FOUND.matcher(trimmed);
            // find(), not matches(): the real line ends with a colon.
            if (dlFound.find()) {
                if (!inTrailer && cur.hasContent()) {
                    inTrailer = true;
                    cur.vmReportedDeadlock = true;
                }
                cur.deadlockLines.add(line);
                continue;
            }
            Matcher dlCount = DEADLOCK_COUNT.matcher(trimmed);
            if (dlCount.matches()) {
                cur.vmDeadlockCount = Integer.parseInt(dlCount.group(1));
                cur.vmReportedDeadlock = cur.vmDeadlockCount > 0 || cur.vmReportedDeadlock;
                inTrailer = false;
                continue;
            }
            if (inTrailer) {
                cur.deadlockLines.add(line);
                continue;
            }

            if (OWNABLE_HEADER.matcher(line).find()) {
                inOwnableSection = true;
                cur.closeOwnableIfEmpty();
                continue;
            }

            Matcher th = THREAD_HEADER.matcher(line);
            if (th.matches() && looksLikeThreadHeader(th.group(2))) {
                inOwnableSection = false;
                cur.startThread(th.group(1), th.group(2), lineNo);
                continue;
            }

            Matcher st = STATE_LINE.matcher(line);
            if (st.matches()) {
                cur.setState(JThread.ThreadState.parse(st.group(1)), st.group(2));
                continue;
            }

            Matcher fr = FRAME_LINE.matcher(line);
            if (fr.matches() && cur.hasThread() && cur.contiguous(lineNo)) {
                inOwnableSection = false;
                cur.addFrame(fr.group(1), fr.group(2), lineNo);
                continue;
            }

            Matcher lk = LOCK_LINE.matcher(line);
            if (lk.matches() && cur.hasThread() && cur.contiguous(lineNo)) {
                cur.addLock(lk.group(1), lk.group(2), lk.group(3), lineNo);
                continue;
            }

            if (inOwnableSection && cur.hasThread() && cur.contiguous(lineNo)) {
                Matcher own = OWNABLE_LINE.matcher(line);
                if (own.matches()) {
                    cur.addOwnable(own.group(1), own.group(2), lineNo);
                    continue;
                }
                if (line.contains("<empty>") || trimmed.equals("- None")) {
                    continue;
                }
            }

            if (!trimmed.isEmpty() && !line.startsWith(" ") && !line.startsWith("\t") && !trimmed.equals("=====")
                    && !trimmed.matches("^=+$")) {
                skippedTopLevel++;
            }
        }

        if (cur.hasContent()) {
            dumps.add(cur.finish());
        }
        if (dumps.isEmpty()) {
            notes.add("no thread stanzas found in " + src.name());
        } else if (skippedTopLevel > 0) {
            notes.add(skippedTopLevel + " unrecognised line(s) in " + src.name());
        }
        return ParseReport.of(dumps, notes);
    }

    /** A thread header carries attributes; the deadlock trailer writes {@code "name":} instead. */
    private static boolean looksLikeThreadHeader(String attrs) {
        String a = attrs.strip();
        if (a.equals(":") || a.isEmpty()) {
            return false;
        }
        return a.startsWith("#") || a.contains("prio=") || a.contains("tid=") || a.contains("nid=")
                || a.contains("runnable") || a.contains("waiting") || a.contains("blocked")
                || a.contains("sleeping") || a.contains("parking");
    }

    private static JThread.ThreadState deriveState(String waitStatus) {
        String s = waitStatus.toLowerCase();
        if (s.startsWith("runnable")) {
            return JThread.ThreadState.RUNNABLE;
        }
        if (s.startsWith("waiting for monitor entry") || s.startsWith("blocked")) {
            return JThread.ThreadState.BLOCKED;
        }
        if (s.startsWith("in object.wait")) {
            return JThread.ThreadState.WAITING;
        }
        if (s.startsWith("sleeping")) {
            return JThread.ThreadState.TIMED_WAITING;
        }
        if (s.startsWith("waiting on condition") || s.startsWith("parking") || s.startsWith("waiting")) {
            return JThread.ThreadState.WAITING;
        }
        return JThread.ThreadState.UNKNOWN;
    }

    /** Mutable accumulator for one dump under construction. */
    private static final class Build {
        private final TextSource src;
        private String vmDescription;
        private String capturedAtRaw;
        private final List<JThread> threads = new ArrayList<>();
        private final List<String> deadlockLines = new ArrayList<>();
        private boolean vmReportedDeadlock;
        private Integer vmDeadlockCount;

        private String name;
        private Integer index;
        private Integer prio;
        private Long osPrio;
        private String tid;
        private String nid;
        private Double cpu;
        private Double elapsed;
        private JThread.ThreadState state = JThread.ThreadState.UNKNOWN;
        private String waitStatus;
        private final List<Frame> frames = new ArrayList<>();
        private final List<LockRef> locks = new ArrayList<>();
        private final List<LockRef> ownable = new ArrayList<>();
        private int startLine;
        private int endLine;
        private int lastContentLine;

        Build(TextSource src) {
            this.src = src;
        }

        boolean hasContent() {
            return !threads.isEmpty() || vmReportedDeadlock || vmDescription != null;
        }

        boolean hasThread() {
            return name != null;
        }

        /**
         * Frames and lock lines belong to the open stanza only while they keep coming.
         * The deadlock trailer repeats stanzas, and without this a dump's last thread would
         * be handed monitors it never locked.
         */
        boolean contiguous(int lineNo) {
            return name != null && (lastContentLine == 0 || lineNo <= lastContentLine + 4);
        }

        private void touched(int lineNo) {
            lastContentLine = Math.max(lastContentLine, lineNo);
            endLine = Math.max(endLine, lineNo);
        }

        void startThread(String threadName, String attrs, int lineNo) {
            flush();
            name = threadName;
            startLine = lineNo;
            endLine = lineNo;
            lastContentLine = lineNo;
            index = intAttr(ATTR_INDEX, attrs);
            prio = intAttr(ATTR_PRIO, attrs);
            Long os = longAttr(ATTR_OS_PRIO, attrs);
            osPrio = os;
            cpu = doubleAttr(ATTR_CPU, attrs);
            elapsed = doubleAttr(ATTR_ELAPSED, attrs);
            Matcher t = ATTR_TID.matcher(attrs);
            tid = t.find() ? t.group(1) : null;
            Matcher n = ATTR_NID.matcher(attrs);
            nid = n.find() ? n.group(1) : null;
            waitStatus = stripKnownAttrs(attrs);
            state = deriveState(waitStatus);
        }

        void setState(JThread.ThreadState s, String paren) {
            state = s;
            if (paren != null && (waitStatus == null || waitStatus.isBlank())) {
                waitStatus = paren;
            }
        }

        void addFrame(String qualified, String paren, int lineNo) {
            frames.add(Frames.parse(qualified, paren, lineNo));
            touched(lineNo);
        }

        void addLock(String verb, String address, String cls, int lineNo) {
            LockRef.Kind kind = switch (verb.toLowerCase()) {
                case "locked" -> LockRef.Kind.HELD;
                case "waiting to lock" -> LockRef.Kind.WAITING_TO_LOCK;
                case "waiting on" -> LockRef.Kind.WAITING_ON;
                case "parking to wait for" -> LockRef.Kind.PARKING;
                default -> LockRef.Kind.RE_LOCK_AFTER_WAIT;
            };
            locks.add(new LockRef(kind, address.toLowerCase(), cls, lineNo));
            touched(lineNo);
        }

        void addOwnable(String address, String cls, int lineNo) {
            ownable.add(new LockRef(LockRef.Kind.OWNABLE_HELD, address.toLowerCase(), cls, lineNo));
            touched(lineNo);
        }

        void closeOwnableIfEmpty() {
            // "Locked ownable synchronizers:" with "- <empty>" is normal; nothing to do.
        }

        JThread finishThread() {
            if (name == null) {
                return null;
            }
            List<LockRef> all = new ArrayList<>(locks);
            all.addAll(ownable);
            JThread t = new JThread(name, index, prio, osPrio, tid, nid, cpu, elapsed, state, waitStatus,
                    List.copyOf(frames), List.copyOf(all), List.copyOf(ownable), startLine, endLine);
            name = null;
            frames.clear();
            locks.clear();
            ownable.clear();
            state = JThread.ThreadState.UNKNOWN;
            waitStatus = null;
            return t;
        }

        void flush() {
            JThread t = finishThread();
            if (t != null) {
                threads.add(t);
            }
        }

        ThreadDump finish() {
            flush();
            return new ThreadDump(src, vmDescription, capturedAtRaw, List.copyOf(threads),
                    vmReportedDeadlock, vmDeadlockCount, List.copyOf(deadlockLines));
        }

        private static Integer intAttr(Pattern p, String attrs) {
            Matcher m = p.matcher(attrs);
            return m.find() ? Integer.parseInt(m.group(1)) : null;
        }

        private static Long longAttr(Pattern p, String attrs) {
            Matcher m = p.matcher(attrs);
            return m.find() ? Long.parseLong(m.group(1)) : null;
        }

        private static Double doubleAttr(Pattern p, String attrs) {
            Matcher m = p.matcher(attrs);
            return m.find() ? Double.parseDouble(m.group(1)) : null;
        }

        private static String stripKnownAttrs(String attrs) {
            String s = attrs
                    .replaceAll("#\\d+", " ")
                    .replaceAll("\\bdaemon\\b", " ")
                    .replaceAll("\\bprio=\\d+", " ")
                    .replaceAll("\\bos_prio=\\d+", " ")
                    .replaceAll("\\bcpu=[0-9.]+ms", " ")
                    .replaceAll("\\belapsed=[0-9.]+s", " ")
                    .replaceAll("\\btid=0x[0-9a-fA-F]+", " ")
                    .replaceAll("\\bnid=0x[0-9a-fA-Fx]+", " ")
                    .replaceAll("\\[0x[0-9a-fA-F]+]", " ")
                    .trim();
            return s.replaceAll("\\s+", " ").replace("--", "").trim();
        }
    }
}
