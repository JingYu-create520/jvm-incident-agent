package dev.jingyu.jia.analyze;

import dev.jingyu.jia.model.Evidence;
import dev.jingyu.jia.model.Finding;
import dev.jingyu.jia.model.Severity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Correlates findings into ranked root-cause hypotheses.
 *
 * <p>Each pattern names a predicate over the findings plus a specificity weight. The weights
 * exist because "GC storm" and "the live set is growing, which is causing the GC storm" come
 * out of the same log and only one of them is actionable: the more specific explanation of a
 * symptom outranks the symptom, and a hypothesis with several corroborating rules gets a bonus.
 */
public final class HypothesisBuilder {

    private final List<Finding> findings;

    public HypothesisBuilder(List<Finding> findings) {
        this.findings = List.copyOf(findings);
    }

    public List<Hypothesis> build() {
        List<Hypothesis> out = new ArrayList<>();
        add(out, "H-DEADLOCK", "Threads are permanently deadlocked: each holds a monitor the next "
                + "one waits for, so the affected requests will never return on their own.",
                byRules("TDA001"));
        add(out, "H-HEAP-LEAK", "The live set is growing — after each major collection more heap "
                + "survives than the last time. Full GC pressure is downstream of this, not the cause.",
                byRules("GCA003"));
        add(out, "H-THREAD-LEAK", "Threads are created and never retired. This ends as an OOM: unable "
                + "to create new native thread, and it already costs context-switch overhead.",
                byRules("TDA003"));
        add(out, "H-LOCK-CONTENTION", "One monitor is serialising the workload: many threads queue on "
                + "the same lock, so throughput is capped by whatever its holder does.",
                byRules("TDA002"));
        add(out, "H-POOL-STARVED", "The worker pool is saturated on one code path, so new requests "
                + "queue instead of being served.",
                byRules("TDA005"));
        add(out, "H-DOWNSTREAM-IO", "Workers are blocked on network reads. The outage is a dependency "
                + "or an unset timeout, not this JVM's own compute.",
                f -> isVariant(f, "TDA004", "socket-io"));
        add(out, "H-WAITING-PLACE", "A crowd of threads is stopped at the same line. That line is "
                + "where the queue forms, whatever is underneath it.",
                f -> isVariant(f, "TDA004", "stack-cluster"));
        add(out, "H-ERROR-DRIVER", "A failing code path repeats fast enough to be the incident itself "
                + "rather than noise around it.",
                byRules("EXC001", "EXC002", "EXC003"));
        add(out, "H-ALLOCATION-STORM", "Allocation pressure rather than a leak: short-lived large "
                + "objects promote early and force full collections while the post-GC live set stays flat.",
                f -> f.ruleId().equals("GCA001") || f.ruleId().equals("GCA004"));
        add(out, "H-HEAP-SHAPE", "A specific class or container dominates the heap, which is where the "
                + "memory question should be answered.",
                byRules("HIS001", "HIS002", "HIS003"));
        add(out, "H-CPU", "A thread is holding a whole core, which is usually a loop or a pathological "
                + "regular expression rather than genuine load.",
                byRules("TDA006"));
        add(out, "H-CONFIG", "The JVM's own log lines point at a configuration problem rather than a "
                + "code problem.",
                byRules("GCA005"));
        add(out, "H-GC-SLOWDOWN", "The collector is spending an unusual share of wall time in pauses.",
                f -> f.ruleId().equals("GCA001") || f.ruleId().equals("GCA006"));
        add(out, "H-PAUSE-TUNING", "Pauses exceed the stated SLA even though the collection pattern is "
                + "otherwise ordinary.",
                byRules("GCA002"));

        boolean leak = findings.stream().anyMatch(f -> f.ruleId().equals("GCA003"));
        if (leak) {
            // A Full GC storm is downstream of a growing live set. It must not outrank its own
            // explanation, or the report sends the reader to GC tuning for a memory leak.
            out.replaceAll(h -> "H-ALLOCATION-STORM".equals(h.id()) || "H-GC-SLOWDOWN".equals(h.id())
                    || "H-PAUSE-TUNING".equals(h.id())
                    ? h.withPenalty(0.55)
                    : h);
            out.replaceAll(h -> "H-HEAP-SHAPE".equals(h.id()) ? h.withPenalty(1.15) : h);
        }
        out.removeIf(h -> h.ruleIds().isEmpty());
        out.sort(Comparator.comparingDouble(this::score).reversed());
        return List.copyOf(out);
    }

    private double score(Hypothesis h) {
        List<Finding> anchors = findings.stream().filter(f -> h.ruleIds().contains(f.ruleId())).toList();
        Severity worst = anchors.stream().map(Finding::severity).min(Comparator.naturalOrder())
                .orElse(h.severity());
        double sev = switch (worst) {
            case CRITICAL -> 1.00;
            case HIGH -> 0.80;
            case MEDIUM -> 0.55;
            case LOW -> 0.35;
            case INFO -> 0.20;
        };
        double corroboration = Math.min(3.0, Math.max(0, h.corroboration() - 1)) * 0.05;
        double conf = anchors.stream().mapToDouble(Finding::confidence).max().orElse(0.5);
        double raw = sev * 0.40 + weight(h.id()) * 0.40 + corroboration + conf * 0.12 + h.confidence() * 0.08;
        return raw * h.penalty();
    }

    private static double weight(String id) {
        return switch (id) {
            case "H-DEADLOCK" -> 1.00;
            case "H-HEAP-LEAK" -> 0.96;
            case "H-THREAD-LEAK" -> 0.92;
            case "H-LOCK-CONTENTION" -> 0.88;
            case "H-POOL-STARVED" -> 0.86;
            case "H-DOWNSTREAM-IO" -> 0.84;
            case "H-ERROR-DRIVER" -> 0.82;
            case "H-WAITING-PLACE" -> 0.80;
            case "H-HEAP-SHAPE" -> 0.76;
            case "H-ALLOCATION-STORM" -> 0.74;
            case "H-CPU" -> 0.70;
            case "H-CONFIG" -> 0.66;
            case "H-GC-SLOWDOWN" -> 0.60;
            default -> 0.56;
        };
    }

    private void add(List<Hypothesis> out, String id, String claim, Predicate<Finding> matcher) {
        List<Finding> matched = findings.stream().filter(matcher).toList();
        if (matched.isEmpty()) {
            return;
        }
        List<Evidence> evidence = new ArrayList<>();
        List<String> actions = new ArrayList<>();
        for (Finding f : matched) {
            evidence.addAll(f.evidence());
            actions.addAll(f.recommendations());
        }
        Severity worst = matched.stream().map(Finding::severity).min(Comparator.naturalOrder()).orElse(Severity.MEDIUM);
        double conf = matched.stream().mapToDouble(Finding::confidence).max().orElse(0.6);
        List<String> ruleIds = matched.stream().map(Finding::ruleId).distinct().sorted().toList();
        String detail = " Corroborated by " + String.join(", ", ruleIds) + ".";
        out.add(new Hypothesis(id, claim + detail, worst, conf, ruleIds,
                actions.stream().distinct().limit(4).toList(),
                evidence.stream().distinct().limit(12).toList(), 1.0));
    }

    private static Predicate<Finding> byRules(String... ruleIds) {
        Set<String> ids = Set.of(ruleIds);
        return f -> ids.contains(f.ruleId());
    }

    private static boolean isVariant(Finding f, String ruleId, String variant) {
        return f.ruleId().equals(ruleId) && variant.equals(f.metrics().get("variant"));
    }
}
