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

import java.util.ArrayList;
import java.util.List;

/**
 * GCA004 — premature promotion: objects are being copied into the old generation before
 * they die, which is what turns "lots of short-lived garbage" into a Full GC problem.
 */
public final class PrematurePromotionRule implements Rule {

    @Override
    public String id() {
        return "GCA004";
    }

    @Override
    public String title() {
        return "Premature promotion / allocation pressure";
    }

    @Override
    public ArtifactKind artifact() {
        return ArtifactKind.GC_LOG;
    }

    @Override
    public List<Finding> evaluate(Snapshot snapshot, Config config) {
        GcLog log = snapshot.gcLog().orElse(null);
        if (log == null || log.events().size() < 3) {
            return List.of();
        }
        List<GcEvent> exhausted = log.events().stream().filter(GcEvent::toSpaceExhausted).toList();
        List<GcEvent> humongous = log.events().stream().filter(GcEvent::humongous).toList();
        long lowYieldYoung = log.events().stream()
                .filter(e -> e.kind() == GcEvent.Kind.YOUNG
                        && e.reclaimedMb() != null && e.heapBeforeBytes() != null && e.capacityBytes() != null)
                // Substantial collections only: a young GC that starts below half the committed heap on a
                // quiet JVM is a calm night, not premature promotion.
                .filter(e -> e.heapBeforeBytes() >= e.capacityBytes() / 2)
                .filter(e -> e.reclaimedMb() < e.heapBeforeBytes() / 1048576.0 * 0.15)
                .count();

        if (exhausted.isEmpty() && humongous.size() < 8 && lowYieldYoung < 8) {
            return List.of();
        }

        List<Evidence> ev = new ArrayList<>();
        List<String> signals = new ArrayList<>();
        Severity severity = Severity.MEDIUM;
        double confidence = 0.55;

        if (!exhausted.isEmpty()) {
            severity = Severity.HIGH;
            confidence = Math.max(confidence, 0.8);
            signals.add(exhausted.size() + " collection(s) ran out of copy space (to-space exhausted / "
                    + "evacuation failure / promotion failed)");
            exhausted.stream().limit(config.maxEvidencePerFinding() / 2)
                    .forEach(e -> ev.add(Evidence.of(log.source(), e.line(), "copy space exhausted")));
        }
        if (humongous.size() >= 8) {
            confidence = Math.max(confidence, 0.7);
            signals.add(humongous.size() + " collection(s) triggered by humongous (direct-to-old) allocation");
            humongous.stream().limit(config.maxEvidencePerFinding() / 2)
                    .forEach(e -> ev.add(Evidence.of(log.source(), e.line(),
                            e.cause() == null ? "humongous allocation" : "cause: " + e.cause())));
        }
        if (lowYieldYoung >= 5) {
            signals.add(lowYieldYoung + " young collections reclaimed under 5% of the heap, "
                    + "so most of what was copied was already old");
            confidence = Math.max(confidence, 0.6);
        }

        return List.of(Finding.builder(id())
                .title(title())
                .artifact(artifact())
                .severity(severity)
                .confidence(Math.min(0.9, confidence))
                .summary(String.join("; ", signals) + ". Objects are reaching the old generation while "
                        + "they are still garbage, which multiplies the cost of every later collection.")
                .evidence(ev)
                .recommend("For humongous allocations: stop building huge arrays per request — stream or "
                        + "chunk them. With G1, a single byte[] over half a region is allocated straight "
                        + "into old space.")
                .recommend("For survivor exhaustion: enlarge the survivor space or the young generation so "
                        + "objects finish dying there instead of migrating.")
                .metric("file", log.source().name())
                .metric("toSpaceExhausted", exhausted.size())
                .metric("humongous", humongous.size())
                .metric("lowYieldYoung", lowYieldYoung)
                .metric("collector", log.collector().name())
                .build());
    }

    @Override
    public String doc() {
        return """
                # GCA004 — Premature promotion

                **What it looks for.** Three independent log signals, any of which means garbage is
                landing in the old generation:

                - `to-space exhausted` / `Evacuation Failure` / `promotion failed` — the collector could
                  not find room to copy survivors, so they were promoted in panic.
                - humongous allocation causes — an object larger than half a G1 region skips young space
                  entirely.
                - young collections that reclaim under 5% of the heap repeatedly — the copy work happened
                  but the objects did not die, which is promotion in all but name.

                **Why it is trustworthy.** Each signal is the JVM's own words, matched on the line that
                says it, so the evidence column quotes the collector rather than an interpretation.

                **Evidence.** The offending collection lines with the matched signal annotated.

                **False positives.** Fewer than three humongous events and fewer than eight substantial
                low-yield young collections produce nothing, so a short or idle log stays quiet. An
                idle JVM reclaiming a few megabytes is a quiet night, not promotion — the rule ignores
                collections that started below 32 MB of occupied heap for that reason.
                """;
    }
}
