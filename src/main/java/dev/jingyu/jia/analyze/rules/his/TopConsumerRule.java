package dev.jingyu.jia.analyze.rules.his;

import dev.jingyu.jia.analyze.Rule;
import dev.jingyu.jia.model.ArtifactKind;
import dev.jingyu.jia.model.ClassStat;
import dev.jingyu.jia.model.Config;
import dev.jingyu.jia.model.Evidence;
import dev.jingyu.jia.model.Finding;
import dev.jingyu.jia.model.Histo;
import dev.jingyu.jia.model.Severity;
import dev.jingyu.jia.model.Snapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * HIS001 — who actually owns the heap, and whether a histogram can answer that at all.
 */
public final class TopConsumerRule implements Rule {

    private static final long MIN_LEADER_BYTES = 16L * 1024 * 1024;
    private static final long MIN_BAG_BYTES = 32L * 1024 * 1024;

    @Override
    public String id() {
        return "HIS001";
    }

    @Override
    public String title() {
        return "Heap dominated by one class";
    }

    @Override
    public ArtifactKind artifact() {
        return ArtifactKind.HEAP_HISTO;
    }

    @Override
    public List<Finding> evaluate(Snapshot snapshot, Config config) {
        Histo histo = snapshot.histo().orElse(null);
        if (histo == null || histo.totalBytes() <= 0) {
            return List.of();
        }
        List<ClassStat> top = histo.topByBytes(10);
        ClassStat leader = top.get(0);
        double share = histo.shareOf(leader);
        double bag = histo.primitiveBagShare();

        List<Finding> out = new ArrayList<>();
        // On a 13 MB heap "byte[] is a quarter of everything" proves nothing. Absolute floors keep
        // a small or freshly started JVM from looking like an incident.
        if (share >= config.histoDominanceRatio() && leader.bytes() >= MIN_LEADER_BYTES) {
            boolean opaque = leader.isPrimitiveArray() || Histo.STRING.equals(leader.className());
            out.add(Finding.builder(id())
                    .title(opaque ? "Heap is mostly byte[]/char[]/String" : title())
                    .artifact(artifact())
                    .severity(share >= 0.6 ? Severity.HIGH : Severity.MEDIUM)
                    .confidence(Math.min(0.9, 0.5 + share))
                    .summary(leader.className() + " holds " + Histo.humanBytes(leader.bytes()) + " of "
                            + Histo.humanBytes(histo.totalBytes()) + " ("
                            + pct(share) + " of all bytes counted, "
                            + String.format(Locale.ROOT, "%,d", leader.instances()) + " instances, "
                            + String.format(Locale.ROOT, "%.0f", leader.avgBytes()) + " bytes each)."
                            + (histo.live() ? " This histogram was taken with :live, so it is the live set."
                            : " This histogram includes garbage; add :live next time if you can."))
                    .evidence(Evidence.of(histo.source(), leader.line(), "top of the histogram"))
                    .recommend(opaque
                            ? "A histogram cannot say who references these — it counts classes, not edges. "
                                    + "Take a heap dump and group by inbound references in MAT, or diff two "
                                    + "histograms taken a minute apart."
                            : "Open the histogram's own class in MAT's dominator tree; the retaining path is "
                                    + "the answer.")
                    .metric("file", histo.source().name())
                    .metric("class", leader.className())
                    .metric("bytes", leader.bytes())
                    .metric("share", pct(share))
                    .metric("instances", leader.instances())
                    .build());
        }
        if (bag >= 0.6 && histo.primitiveBagBytes() >= MIN_BAG_BYTES
                && share < config.histoDominanceRatio()) {
            out.add(Finding.builder(id())
                    .title("Heap is raw buffers, not identifiable objects")
                    .artifact(artifact())
                    .severity(Severity.MEDIUM)
                    .confidence(0.6)
                    .summary("byte[], char[] and java.lang.String together account for " + pct(bag)
                            + " of the histogram. That is what a cache of serialised payloads, log buffering, "
                            + "or unbounded StringBuilder use looks like from the outside.")
                    .evidence(top.stream().limit(config.maxEvidencePerFinding())
                            .map(c -> Evidence.of(histo.source(), c.line(),
                                    Histo.humanBytes(c.bytes()) + " / " + pct(histo.shareOf(c))))
                            .toList())
                    .recommend("Correlate with the thread dump: the threads that build these buffers name "
                            + "the code path. A heap dump is needed to attribute the bytes themselves.")
                    .metric("file", histo.source().name())
                    .metric("bagShare", pct(bag))
                    .build());
        }
        return out;
    }

    private static String pct(double v) {
        return String.format(Locale.ROOT, "%.1f%%", v * 100);
    }

    @Override
    public String doc() {
        return """
                # HIS001 — Top heap consumers

                **What it looks for.** Rows of `jmap -histo` ranked by shallow bytes. A single class at
                35% or more of counted bytes fires; separately, when `byte[] + char[] + String` together
                exceed 60% the rule says the quiet part out loud — a histogram cannot tell you who holds
                those, only that something does.

                **Why it is trustworthy.** Shallow bytes are what the tool printed; nothing is inferred.
                The `:live` marker is read from the capture so the report can tell you whether you are
                looking at the live set or at garbage that a GC will remove.

                **Evidence.** The leading rows with bytes, instance counts and their share.

                **False positives.** An app whose job genuinely is buffering (file transfer, image
                pipeline) has a boring, correct 70% of `byte[]`. Severity stays MEDIUM unless one class
                takes over, and the recommendation is "diff two histograms", which distinguishes steady
                state from growth.
                """;
    }
}
