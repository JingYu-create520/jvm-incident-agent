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
import java.util.Locale;

/**
 * GCA005 — configuration smells: metaspace pressure, explicit GC, and the JVM's own
 * "you configured this wrong" hints.
 */
public final class VmConfigSmellRule implements Rule {

    private record Hint(String needle, String what, String advice, Severity severity) {
    }

    private static final List<Hint> HINTS = List.of(
            new Hint("concurrent mode failure",
                    "CMS fell back to a serial Full GC after concurrent marking failed",
                    "Start marking earlier (-XX:CMSInitiatingOccupancyFraction=70 "
                            + "-XX:+UseCMSInitiatingOccupancyOnly) or move to G1/ZGC.",
                    Severity.HIGH),
            new Hint("Heap Dump Initiated GC",
                    "a heap dump was taken inside this window",
                    "Note the timestamp: everything after a heap dump is polluted by the dump itself.",
                    Severity.INFO),
            new Hint("GCLocker Initiated GC",
                    "JNI critical sections are forcing collections",
                    "Something uses GetPrimitiveArrayCritical (common in older native bindings, image and "
                            + "crypto libraries); switch to GetPrimitiveArrayCritical-free APIs.",
                    Severity.MEDIUM),
            new Hint("Could not reserve enough space for object heap",
                    "the JVM could not reserve its heap",
                    "Lower -Xmx, fix overcommit, or address a compressed-oops boundary: above ~32 GB the "
                            + "heap loses compressed pointers and needs roughly 40% more for the same graph.",
                    Severity.CRITICAL),
            new Hint("Try -XX:+UseCompressedOops",
                    "the JVM explicitly suggested compressed oops",
                    "Heap is above the 32 GB compressed-oops cliff. Either shrink it below 32 GB or accept "
                            + "the extra memory and set -XX:ReservedCodeCacheSize and object counts accordingly.",
                    Severity.HIGH));

    @Override
    public String id() {
        return "GCA005";
    }

    @Override
    public String title() {
        return "JVM configuration smell";
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
        List<Finding> out = new ArrayList<>();

        long metadata = log.events().stream()
                .filter(e -> e.cause() != null && e.cause().contains("Metadata GC Threshold"))
                .count();
        // Spring Boot, Hibernate metamodel generation and dynamic proxies all fill metaspace during
        // boot, so two threshold collections inside the first minute are normal. Pressure is a
        // metaspace collector that keeps coming back after the JVM has settled.
        long metadataAfterWarmup = log.events().stream()
                .filter(e -> e.cause() != null && e.cause().contains("Metadata GC Threshold"))
                .filter(e -> e.atSec() > 60)
                .count();
        long explicit = log.events().stream()
                .filter(e -> e.cause() != null && (e.cause().contains("System.gc()")
                        || e.cause().contains("Full GC (System")))
                .count();
        Long maxMetaspace = log.events().stream()
                .map(GcEvent::metaspaceAfterBytes)
                .filter(java.util.Objects::nonNull)
                .reduce(0L, Math::max);

        if (metadata >= 3 && metadataAfterWarmup >= 1) {
            out.add(Finding.builder(id())
                    .title("Metaspace-driven collections")
                    .artifact(artifact())
                    .severity(metadata >= 5 ? Severity.HIGH : Severity.MEDIUM)
                    .confidence(0.8)
                    .summary(metadata + " collection(s) in this window were triggered by metaspace rather "
                            + "than by heap pressure"
                            + (maxMetaspace > 0 ? ", peaking at " + mb(maxMetaspace) + " of metaspace" : "")
                            + ". The heap is not the problem; class metadata is.")
                    .evidence(log.events().stream()
                            .filter(e -> e.cause() != null && e.cause().contains("Metadata GC Threshold"))
                            .limit(config.maxEvidencePerFinding())
                            .map(e -> Evidence.of(log.source(), e.line(), "cause: " + e.cause()))
                            .toList())
                    .recommend("Find what generates classes: dynamic proxies, script engines, lambda "
                            + "factories in loops, or a redeploy that leaked its classloader.")
                    .metric("file", log.source().name())
                    .metric("metadataTriggers", metadata)
                    .build());
        }
        if (explicit >= 1) {
            out.add(Finding.builder(id())
                    .title("Someone calls System.gc()")
                    .artifact(artifact())
                    .severity(explicit >= 5 ? Severity.MEDIUM : Severity.LOW)
                    .confidence(0.9)
                    .summary(explicit + " Full GC(s) with cause System.gc() — somebody in the process, or a "
                            + "library such as RMI/NIO direct-buffer cleanup, is requesting full collections.")
                    .evidence(log.events().stream()
                            .filter(e -> e.cause() != null && e.cause().contains("System.gc()"))
                            .limit(config.maxEvidencePerFinding())
                            .map(e -> Evidence.of(log.source(), e.line(), "cause: " + e.cause()))
                            .toList())
                    .recommend("Before adding -XX:+DisableExplicitGC, check whether anything depends on it "
                            + "to reclaim direct buffers; -XX:+ExplicitGCInvokesConcurrent is usually the "
                            + "safer answer.")
                    .metric("file", log.source().name())
                    .metric("explicitGcs", explicit)
                    .build());
        }

        List<String> lines = log.source().lines();
        for (Hint h : HINTS) {
            if (h.what().isEmpty()) {
                continue;
            }
            int at = indexOf(lines, h.needle());
            if (at < 0) {
                continue;
            }
            out.add(Finding.builder(id())
                    .title("JVM hint: " + h.what())
                    .artifact(artifact())
                    .severity(h.severity())
                    .confidence(0.85)
                    .summary("The log itself says: \"" + h.needle() + "\" — " + h.what() + ".")
                    .evidence(Evidence.of(log.source(), at + 1))
                    .recommend(h.advice())
                    .metric("file", log.source().name())
                    .metric("needle", h.needle())
                    .build());
        }
        return out;
    }

    private static int indexOf(List<String> lines, String needle) {
        String n = needle.toLowerCase(Locale.ROOT);
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).toLowerCase(Locale.ROOT).contains(n)) {
                return i;
            }
        }
        return -1;
    }

    private static String mb(long bytes) {
        return String.format(Locale.ROOT, "%.0fM", bytes / 1048576.0);
    }

    @Override
    public String doc() {
        return """
                # GCA005 — JVM configuration smell

                **What it looks for.** Two computed signals — collections whose recorded cause is
                `Metadata GC Threshold` (metaspace, not heap, is driving GC) and any Full GC caused by
                `System.gc()` — plus a table of verbatim strings the JVM prints when it wants you to
                change a flag: `concurrent mode failure`, `Could not reserve enough space for object
                heap`, `Try -XX:+UseCompressedOops`, `GCLocker Initiated GC`, `Heap Dump Initiated GC`.

                **Why it is trustworthy.** The string table quotes the JVM's own complaint and prints the
                line it came from, so nobody has to take the recommendation on faith. Cause-based signals
                need two or more occurrences before they fire.

                **Evidence.** The matching log line, or the collection lines carrying the cause.

                **False positives.** `Heap Dump Initiated GC` is reported at INFO, not as a fault — it is
                a normal consequence of taking a dump, and the report says so.
                """;
    }
}
