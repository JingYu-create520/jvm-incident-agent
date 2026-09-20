package dev.jingyu.jia.model;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/** Occurrences that share one stack fingerprint. */
public record ExceptionCluster(String fingerprint, List<ExceptionOccurrence> items) {

    public int count() {
        return items.size();
    }

    public ExceptionOccurrence first() {
        return items.get(0);
    }

    public ExceptionOccurrence representative() {
        return items.stream()
                .max((a, b) -> Integer.compare(a.chain().size() + a.rootCause().frames().size(),
                        b.chain().size() + b.rootCause().frames().size()))
                .orElse(items.get(0));
    }

    public String outerClass() {
        return representative().outermost().className();
    }

    public String rootClass() {
        return representative().rootCause().className();
    }

    public Optional<String> message() {
        String m = representative().rootCause().message();
        return (m == null || m.isBlank()) ? Optional.empty() : Optional.of(m);
    }

    /** Wall-clock span in seconds across the cluster, when timestamps were parseable. */
    public Optional<Double> spanSec() {
        List<Long> ts = items.stream()
                .map(ExceptionOccurrence::epochMillis)
                .filter(java.util.Objects::nonNull)
                .sorted()
                .toList();
        if (ts.size() < 2) {
            return Optional.empty();
        }
        return Optional.of((ts.get(ts.size() - 1) - ts.get(0)) / 1000.0);
    }

    /** Longest burst of repeats, and how tight it is — a spike is a symptom of an incident start. */
    public Optional<Burst> burst(long bucketMillis) {
        List<Long> ts = items.stream()
                .map(ExceptionOccurrence::epochMillis)
                .filter(java.util.Objects::nonNull)
                .sorted()
                .toList();
        if (ts.size() < 2) {
            return Optional.empty();
        }
        Map<Long, Integer> buckets = new java.util.TreeMap<>();
        for (Long t : ts) {
            buckets.merge(t / bucketMillis, 1, Integer::sum);
        }
        int max = 0;
        long at = 0;
        for (Map.Entry<Long, Integer> e : buckets.entrySet()) {
            if (e.getValue() > max) {
                max = e.getValue();
                at = e.getKey();
            }
        }
        if (max < 2) {
            return Optional.empty();
        }
        return Optional.of(new Burst(max, at * bucketMillis));
    }

    public record Burst(int count, long bucketStartMillis) {
        @Override
        public String toString() {
            return String.format(Locale.ROOT, "%d in one window", count);
        }
    }

    public static Map<String, List<ExceptionOccurrence>> group(List<ExceptionOccurrence> all, int depth) {
        return all.stream().collect(Collectors.groupingBy(
                e -> e.fingerprint(depth), java.util.LinkedHashMap::new, Collectors.toList()));
    }
}
