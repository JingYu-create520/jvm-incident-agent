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

import java.util.List;
import java.util.Locale;

/**
 * HIS002 — the shortlist worth opening in a real heap dump: your own classes, ranked by
 * shallow size, that are big enough to matter.
 */
public final class MatWorthyRule implements Rule {


    @Override
    public String id() {
        return "HIS002";
    }

    @Override
    public String title() {
        return "Application class holds a large share of the heap";
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
        List<ClassStat> business = histo.businessClasses();
        if (business.isEmpty()) {
            return List.of();
        }
        ClassStat leader = business.stream()
                .max((a, b) -> Long.compare(a.bytes(), b.bytes()))
                .orElseThrow();
        double share = histo.shareOf(leader);
        if (leader.bytes() < config.histoMatMinBytes() || share < config.histoMatMinShare()) {
            return List.of();
        }
        List<ClassStat> next = business.stream()
                .filter(c -> c.bytes() >= config.histoMatMinBytes() / 4)
                .sorted((a, b) -> Long.compare(b.bytes(), a.bytes()))
                .limit(config.maxEvidencePerFinding())
                .toList();

        return List.of(Finding.builder(id())
                .title(title())
                .artifact(artifact())
                .severity(share >= 0.3 ? Severity.HIGH : Severity.MEDIUM)
                .confidence(Math.min(0.88, 0.55 + share))
                .summary(leader.className() + " alone accounts for " + Histo.humanBytes(leader.bytes())
                        + " (" + String.format(Locale.ROOT, "%.1f", share * 100) + "% of counted bytes) across "
                        + String.format(Locale.ROOT, "%,d", leader.instances()) + " instances averaging "
                        + String.format(Locale.ROOT, "%.0f", leader.avgBytes()) + " bytes. That is application "
                        + "state, not runtime overhead, and it is the first thing to open in a heap dump.")
                .evidence(next.stream()
                        .map(c -> Evidence.of(histo.source(), c.line(),
                                Histo.humanBytes(c.bytes()) + ", " + String.format(Locale.ROOT, "%,d", c.instances())
                                        + " instances"))
                        .toList())
                .recommend("In MAT: Histogram → list this class → Merge Shortest Paths to GC Roots (exclude "
                            + "weak/soft references). The root that survives is the field holding your leak.")
                .recommend("Instance count matters as much as bytes: " + String.format(Locale.ROOT, "%,d",
                        leader.instances()) + " objects of one class is usually a collection keyed by "
                        + "something per-request.")
                .metric("file", histo.source().name())
                .metric("class", leader.className())
                .metric("bytes", leader.bytes())
                .metric("instances", leader.instances())
                .metric("share", String.format(Locale.ROOT, "%.3f", share))
                .build());
    }

    @Override
    public String doc() {
        return """
                # HIS002 — Worth a heap dump
                
                The same histogram as HIS001, restricted to classes that are not `java.*`, `jdk.*`, `sun.*` or
                arrays of them. If one of your own classes holds at least 10% of counted bytes and 8 MB absolute (`--mat-share`, `--mat-min-mb`),
                it is named, along with the sibling classes that are large too.
                
                The distinction matters because a `byte[]` at the top tells you a buffer grew, while a domain class
                at the top tells you *which state* grew — and only the second one is actionable without a dump. The
                recommendation is therefore a concrete MAT query: Histogram → the class → Merge Shortest Paths to
                GC Roots, excluding weak and soft references.
                
                Evidence: every application class above 2 MB, ranked, with instance counts. Wrong when: a
                long-lived cache of your own objects, which is legitimately large — read the instance count
                ("10 MB across 4 entries" and "10 MB across 240,000 entries" are different bugs).
                """;
    }
}
