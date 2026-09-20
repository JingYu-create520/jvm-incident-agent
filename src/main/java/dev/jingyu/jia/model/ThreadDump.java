package dev.jingyu.jia.model;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** One jstack capture. A snapshot may legitimately hold several of these. */
public record ThreadDump(TextSource source,
                         String vmDescription,
                         String capturedAtRaw,
                         List<JThread> threads,
                         boolean vmReportedDeadlock,
                         Integer vmDeadlockCount,
                         List<String> deadlockLines) {

    /** Trailing "-1", "-2", "pool-3-thread-7", "http-nio-8080-exec-12" etc. */
    private static final Pattern TRAILING_INDEX = Pattern.compile("[\\-_#]?\\d+$");
    private static final Pattern INNER_INDEX = Pattern.compile("(?<=\\D)[-_]\\d+(?=\\D)");

    public int size() {
        return threads.size();
    }

    public Map<JThread.ThreadState, Long> byState() {
        return threads.stream()
                .collect(Collectors.groupingBy(JThread::state, Collectors.counting()));
    }

    /** Thread name with numeric suffixes removed, so "pool-2-thread-9" groups with "pool-2-thread-3". */
    public static String nameFamily(String name) {
        String s = name == null ? "" : name.strip();
        s = INNER_INDEX.matcher(s).replaceAll("-N");
        s = TRAILING_INDEX.matcher(s).replaceAll("");
        return s.isBlank() ? name : s;
    }

    public Map<String, List<JThread>> byNameFamily() {
        return threads.stream().collect(Collectors.groupingBy(
                t -> nameFamily(t.name()), java.util.LinkedHashMap::new, Collectors.toList()));
    }

    /**
     * Stack fingerprint: the frames that actually distinguish waiting places,
     * truncated so deep recursion does not split clusters.
     */
    public static String stackFingerprint(JThread t, int depth) {
        StringBuilder sb = new StringBuilder(t.state().name()).append('|');
        List<Frame> frames = t.stack();
        int n = Math.min(frames.size(), Math.max(1, depth));
        for (int i = 0; i < n; i++) {
            sb.append(frames.get(i).id()).append(';');
        }
        return sb.toString();
    }
}
