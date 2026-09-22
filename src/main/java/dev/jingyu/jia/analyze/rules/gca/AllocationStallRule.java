package dev.jingyu.jia.analyze.rules.gca;

import dev.jingyu.jia.analyze.Rule;
import dev.jingyu.jia.model.ArtifactKind;
import dev.jingyu.jia.model.Config;
import dev.jingyu.jia.model.Evidence;
import dev.jingyu.jia.model.Finding;
import dev.jingyu.jia.model.GcEvent;
import dev.jingyu.jia.model.GcLog;
import dev.jingyu.jia.model.Severity;
import dev.jingyu.jia.model.Snapshot;

import java.util.List;
import java.util.Locale;

/**
 * GCA007 — allocation stalls: ZGC stopping the thread that asked for memory.
 *
 * <p>This is what a ZGC heap in trouble looks like, and it is the event the other GC rules cannot
 * see, because ZGC never runs a stop-the-world collection to make room.
 */
public final class AllocationStallRule implements Rule {

    private static final int MIN_STALLS = 3;

    @Override
    public String id() {
        return "GCA007";
    }

    @Override
    public String title() {
        return "Allocation stalled waiting for memory";
    }

    @Override
    public ArtifactKind artifact() {
        return ArtifactKind.GC_LOG;
    }

    @Override
    public List<Finding> evaluate(Snapshot snapshot, Config config) {
        GcLog log = snapshot.gcLog().orElse(null);
        if (log == null) {
            return List.of();
        }
        List<GcEvent> stalls = log.allocationStalls();
        double longest = stalls.stream().mapToDouble(e -> e.pauseMs() == null ? 0 : e.pauseMs())
                .max().orElse(0);
        if (stalls.size() < MIN_STALLS && longest < config.slaPauseMs()) {
            return List.of();
        }
        Severity severity = stalls.size() >= 20 || longest >= 1_000 ? Severity.CRITICAL : Severity.HIGH;
        String victims = stalls.stream()
                .map(GcEvent::cause)
                .filter(c -> c != null && !c.isBlank())
                .distinct()
                .limit(3)
                .reduce((a, b) -> a + ", " + b)
                .orElse("no thread name printed");

        return List.of(Finding.builder(id())
                .title(title())
                .artifact(artifact())
                .severity(severity)
                .confidence(stalls.size() >= MIN_STALLS * 2 ? 0.92 : 0.8)
                .summary(stalls.size() + " allocation stall(s), longest "
                        + String.format(Locale.ROOT, "%.0f", longest) + " ms, affecting: " + victims
                        + ". Under ZGC this is what a heap that cannot keep up looks like — there is"
                        + " no Full GC to count, because the collector stops the allocating thread"
                        + " instead of stopping the world.")
                .evidence(stalls.stream()
                        .sorted((a, b) -> Double.compare(
                                b.pauseMs() == null ? 0 : b.pauseMs(),
                                a.pauseMs() == null ? 0 : a.pauseMs()))
                        .limit(config.maxEvidencePerFinding())
                        .map(e -> Evidence.of(log.source(), e.line(),
                                (e.cause() == null ? "stall" : e.cause()) + ": "
                                        + String.format(Locale.ROOT, "%.0f", e.pauseMs() == null ? 0 : e.pauseMs())
                                        + " ms at +" + String.format(Locale.ROOT, "%.3f", e.atSec()) + "s"))
                        .toList())
                .recommend("An allocation stall means the mutator outran the collector: either the heap is "
                        + "too small for the live set (GCA003 decides which) or the allocation rate is "
                        + "(GCA004). Both are answered before any ZGC tuning flag is worth touching.")
                .recommend("ZGC scales its concurrent work with -XX:ConcGCThreads and reserves headroom "
                        + "through -XX:SoftMaxHeapSize; raise the heap only after the live set is known, "
                        + "or the stalls just move further apart.")
                .metric("file", log.source().name())
                .metric("stalls", stalls.size())
                .metric("longestMs", String.format(Locale.ROOT, "%.0f", longest))
                .metric("stallMsTotal", String.format(Locale.ROOT, "%.0f", log.stallSumMs()))
                .metric("collector", String.valueOf(log.collector()))
                .build());
    }

    @Override
    public String doc() {
        return """
                # GCA007 — Allocation stalls

                ZGC has no Full GC to give you. When it cannot hand out memory fast enough it stops the
                one thread that asked for it and prints `Allocation Stall (http-nio-8080-exec-8)
                31.866ms` against that thread's name. Counting those lines is the only way this tool
                can see a ZGC heap in trouble, because every other GC rule here is reading a pause
                vocabulary ZGC does not use.

                Fires at three or more stalls, or at a single stall longer than the pause SLA. The
                thread names come out of the log, so "who is being starved" is part of the finding —
                if it is your request threads and not a batch job, you are deciding about latency, not
                about throughput.

                Evidence: the stall lines themselves, longest first, each quoting the thread the JVM
                named and the milliseconds it waited. One quotation per occurrence, never a count on
                its own.

                Wrong when: a stall is a moment, not a trend. One stall in a log covering an hour means
                a hiccup; ten in a minute is the shape of an undersized heap. And ZGC is not the only
                collector that stalls — under G1 the same pressure surfaces as to-space exhausted and
                shows up as GCA004, not here.
                """;
    }
}
