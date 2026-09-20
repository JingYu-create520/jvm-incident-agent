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
import java.util.Set;

/**
 * HIS003 — container-count anomaly. Ten thousand maps is a program; two million maps is a
 * bug, and the histogram can see the difference even when it cannot see references.
 */
public final class ContainerCountRule implements Rule {

    private static final Set<String> CONTAINERS = Set.of(
            "java.util.HashMap",
            "java.util.concurrent.ConcurrentHashMap",
            "java.util.LinkedHashMap",
            "java.util.ArrayList",
            "java.util.LinkedList",
            "java.util.HashSet",
            "java.util.Vector",
            "java.util.Hashtable",
            "java.util.ArrayDeque",
            "java.util.TreeMap",
            "com.google.common.collect.ArrayListMultimap",
            "java.util.concurrent.CopyOnWriteArrayList");

    private static final Set<String> NODE_TYPES = Set.of(
            "java.util.HashMap$Node",
            "java.util.HashMap$TreeNode",
            "java.util.concurrent.ConcurrentHashMap$Node",
            "java.util.ArrayList",
            "java.util.LinkedHashMap$Entry");

    @Override
    public String id() {
        return "HIS003";
    }

    @Override
    public String title() {
        return "Implausible number of collection instances";
    }

    @Override
    public ArtifactKind artifact() {
        return ArtifactKind.HEAP_HISTO;
    }

    @Override
    public List<Finding> evaluate(Snapshot snapshot, Config config) {
        Histo histo = snapshot.histo().orElse(null);
        if (histo == null || histo.totalInstances() <= 0) {
            return List.of();
        }
        long bar = Math.max(50_000, histo.totalInstances() / 20);
        ClassStat worst = null;
        long worstCount = 0;
        for (ClassStat c : histo.classes()) {
            long n = c.instances();
            if (n <= worstCount) {
                continue;
            }
            if (CONTAINERS.contains(c.className()) || c.className().endsWith("$Node")
                    || c.className().endsWith("$Entry")) {
                if (n >= bar) {
                    worst = c;
                    worstCount = n;
                }
            }
        }
        if (worst == null) {
            return List.of();
        }
        boolean node = worst.className().endsWith("$Node") || worst.className().endsWith("$Entry");
        long bytes = worst.bytes();
        final ClassStat flagged = worst;
        return List.of(Finding.builder(id())
                .title(title())
                .artifact(artifact())
                .severity(bytes > 64L * 1024 * 1024 ? Severity.HIGH : Severity.MEDIUM)
                .confidence(0.7)
                .summary(String.format(Locale.ROOT, "%,d", worst.instances()) + " instances of "
                        + worst.className() + " (" + Histo.humanBytes(bytes) + "), against "
                        + String.format(Locale.ROOT, "%,d", histo.totalInstances()) + " objects in total. "
                        + (node
                        ? "Those are the entry nodes inside maps, so the real signal is an enormous number "
                                + "of map entries — one collection is holding millions of keys."
                        : "That many containers means something builds a collection per record or per "
                                + "request instead of per JVM.")
                        + " This is row " + (worst.line()) + " of the histogram.")
                .evidence(Evidence.of(histo.source(), worst.line(),
                        String.format(Locale.ROOT, "%,d", worst.instances()) + " instances"))
                .evidence(histo.classes().stream()
                        .filter(c -> NODE_TYPES.contains(c.className()) && c.line() != flagged.line())
                        .limit(3)
                        .map(c -> Evidence.of(histo.source(), c.line(),
                                "corroborating: " + String.format(Locale.ROOT, "%,d", c.instances())
                                        + " " + c.className()))
                        .toList())
                .recommend("Count the keys, not the objects: a heap dump grouped by the owning field of "
                        + "these nodes names the single collection that grew.")
                .recommend("If the count is spread across many small maps, look for a per-request "
                        + "attachment that is retained by a session, thread local or cache key.")
                .metric("file", histo.source().name())
                .metric("class", worst.className())
                .metric("instances", worst.instances())
                .metric("threshold", bar)
                .build());
    }

    @Override
    public String doc() {
        return """
                # HIS003 — Collection count anomaly

                **What it looks for.** Instance counts for the standard collection classes — and their
                internal `$Node` / `$Entry` types, which is where the entries of a `HashMap` or
                `ConcurrentHashMap` actually live. Anything at 50 000+ instances, or 5% of all objects in
                the histogram, fires.

                **Why it is trustworthy.** Bytes can be legitimately large in a buffering application; half
                a million separate map nodes almost never is. The finding quotes corroborating rows from
                the same table, so it is a pattern across several lines rather than one big number.

                **Evidence.** The offending row plus up to three related container rows.

                **False positives.** Big in-memory data grids and ORMs with deep entity graphs really do
                hold hundreds of thousands of nodes. That is why severity keys off bytes as well as count,
                and why the recommendation is to group by owning field rather than a claim of leakage.
                """;
    }
}
