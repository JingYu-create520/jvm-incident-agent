package dev.jingyu.jia.analyze;

import dev.jingyu.jia.model.Evidence;
import dev.jingyu.jia.model.Severity;

import java.util.List;

/**
 * A ranked root-cause hypothesis: the correlation of several findings into one claim.
 *
 * <p>The report's first line is a hypothesis, not a finding, because findings are
 * observations and a reader under pressure asks the other question — "so what is wrong,
 * and in which order should I believe them?"
 */
public record Hypothesis(String id,
                         String claim,
                         Severity severity,
                         double confidence,
                         List<String> ruleIds,
                         List<String> actions,
                         List<Evidence> evidence,
                         double penalty) {

    public int corroboration() {
        return ruleIds.size();
    }

    /** A hypothesis that explains another one should not compete with it on equal terms. */
    public Hypothesis withPenalty(double factor) {
        return new Hypothesis(id, claim, severity, confidence, ruleIds, actions, evidence,
                penalty * factor);
    }
}
