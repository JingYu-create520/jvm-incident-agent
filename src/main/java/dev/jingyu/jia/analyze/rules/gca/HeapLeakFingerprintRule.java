package dev.jingyu.jia.analyze.rules.gca;

import dev.jingyu.jia.analyze.Rule;
import dev.jingyu.jia.model.ArtifactKind;
import dev.jingyu.jia.model.Config;
import dev.jingyu.jia.model.Evidence;
import dev.jingyu.jia.model.Finding;
import dev.jingyu.jia.model.GcEvent;
import dev.jingyu.jia.model.GcLog;
import dev.jingyu.jia.model.Histo;
import dev.jingyu.jia.model.Severity;
import dev.jingyu.jia.model.Snapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * GCA003 — the heap-leak fingerprint: after every Full GC the heap is emptied of garbage,
 * so what is left is the live set. If the live set only grows, something is holding on.
 */
public final class HeapLeakFingerprintRule implements Rule {

    @Override
    public String id() {
        return "GCA003";
    }

    @Override
    public String title() {
        return "Rising post-GC live set (memory leak fingerprint)";
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
        List<Point> points = new ArrayList<>();
        for (GcEvent e : log.events()) {
            Long v = e.oldAfterBytes() != null ? e.oldAfterBytes() : e.heapAfterBytes();
            if (v != null && v > 0 && (e.kind() == GcEvent.Kind.FULL || e.kind() == GcEvent.Kind.MIXED)) {
                points.add(new Point(e, v));
            }
        }
        if (points.size() < Math.max(3, config.heapLeakMinFullGc())) {
            return List.of();
        }
        int dips = 0;
        for (int i = 1; i < points.size(); i++) {
            if (points.get(i).value() < points.get(i - 1).value()) {
                dips++;
            }
        }
        double tolerance = Math.max(1, points.size() * 0.25);
        if (dips > tolerance) {
            return List.of();
        }
        long first = points.get(0).value();
        long last = points.get(points.size() - 1).value();
        double rise = first == 0 ? 0 : (double) (last - first) / first;
        Long capacity = points.stream().map(p -> p.event.capacityBytes()).filter(java.util.Objects::nonNull)
                .reduce((a, b) -> b).orElse(null);
        double ofCapacity = capacity == null || capacity == 0 ? 0
                : (double) points.stream().mapToLong(Point::value).max().orElse(last) / capacity;

        // Two shapes mean the same thing. A climb is the textbook leak; a plateau pinned against the
        // ceiling is the same leak after it has filled the heap — collections stop reclaiming
        // anything at all, which a purely monotonic test would miss.
        long pinned = capacity == null ? 0 : points.stream()
                .filter(p -> p.value() >= capacity * 0.85)
                .count();
        boolean climbing = rise >= config.heapLeakRiseRatio();
        boolean saturated = pinned >= Math.max(3, config.heapLeakMinFullGc());
        if (!climbing && !saturated) {
            return List.of();
        }
        Severity severity = ofCapacity > 0.85 ? Severity.CRITICAL : (ofCapacity > 0.6 ? Severity.HIGH : Severity.MEDIUM);
        String unit = points.get(0).event.oldAfterBytes() != null ? "old gen" : "heap";

        List<Evidence> ev = new ArrayList<>();
        int step = Math.max(1, points.size() / config.maxEvidencePerFinding());
        for (int i = 0; i < points.size(); i += step) {
            Point p = points.get(i);
            ev.add(Evidence.of(log.source(), p.event.line(),
                    p.event.kind() + " leaves " + mb(p.value()) + " of " + unit + " live"));
        }
        ev.add(Evidence.of(log.source(), points.get(points.size() - 1).event.line(),
                "last measured live set: " + mb(last)));

        Histo histo = snapshot.histo().orElse(null);
        String hint = histo == null ? "" : " The heap histogram in this same snapshot points at "
                + histo.topByBytes(1).get(0).className() + " as the largest consumer.";

        String shape = climbing && saturated
                ? "climbed and is now pinned against the ceiling"
                : saturated
                ? "sits at " + pinned + " of " + points.size() + " collections with "
                        + String.format(Locale.ROOT, "%.0f", ofCapacity * 100) + "% of the heap still live "
                        + "after a Full GC — nothing is being reclaimed any more"
                : "climbed steadily";

        return List.of(Finding.builder(id())
                .title(title())
                .artifact(artifact())
                .severity(severity)
                .confidence(Math.min(0.95, saturated ? 0.9 : 0.6 + rise * 0.6))
                .summary("Across " + points.size() + " major collections the " + unit + " low-water mark "
                        + (climbing ? "climbed from " + mb(first) + " to " + mb(last) + " (+"
                        + String.format(Locale.ROOT, "%.0f", rise * 100) + "%), " : "is " + mb(last) + ", ")
                        + shape
                        + (capacity == null ? "" : " (capacity " + mb(capacity) + ")")
                        + ". Garbage is being collected; the objects that survive each Full GC keep "
                        + "growing, which means something is still strongly reachable." + hint)
                .evidence(ev)
                .recommend("Capture a heap dump at the low point right after a Full GC and compare it with "
                        + "one an hour later — the classes that grew are the leak, and MAT's "
                        + "dominator tree names the retainer.")
                .recommend("Usual suspects in order: an unbounded cache or collection keyed by request, "
                        + "ThreadLocal values never removed on a pooled thread, and listeners registered "
                        + "per request.")
                .metric("file", log.source().name())
                .metric("samples", points.size())
                .metric("firstMb", mb(first))
                .metric("lastMb", mb(last))
                .metric("riseRatio", String.format(Locale.ROOT, "%.3f", rise))
                .metric("capacityMb", capacity == null ? "unknown" : mb(capacity))
                .build());
    }

    private static String mb(long bytes) {
        return String.format(Locale.ROOT, "%.0fM", bytes / 1048576.0);
    }

    private record Point(GcEvent event, long value) {
    }

    @Override
    public String doc() {
        return """
                # GCA003 — Rising post-GC live set

                **What it looks for.** After every Full GC (and G1 mixed collection) the heap holds only
                live objects. Those post-collection values are sampled in time order: if they climb by at
                least 10% end to end (`--heap-leak-rise`) without more than a quarter of the steps
                dipping, the live set is growing and something is holding references.

                When the log exposes old-generation detail — G1 `Old regions:` scaled by the region size,
                or a JDK 8 `[ParOldGen: …]` figure — the rule measures the old generation directly;
                otherwise it uses post-GC heap used, which is the same quantity for a Full GC.

                **Why it is trustworthy.** This is the definition of a leak from GC data alone, not a
                heuristic about heap size. Capacity is read from the log so the finding can say how close
                to the wall you already are, which is what decides severity.

                **Evidence.** Evenly spaced major-collection lines, each annotated with the live bytes it
                left behind, ending with the last measurement.

                **False positives.** A cache that legitimately fills to its configured maximum produces a
                rising then flat series; the dip tolerance rejects the flat part, but a snapshot taken
                entirely during fill-up will look like this. Check `samples` and the capacity share.
                """;
    }
}
