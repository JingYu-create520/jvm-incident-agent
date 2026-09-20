package dev.jingyu.jia.analyze;

import dev.jingyu.jia.model.Config;
import dev.jingyu.jia.model.Finding;
import dev.jingyu.jia.model.Severity;
import dev.jingyu.jia.model.Snapshot;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Everything one analysis run produced. */
public record AnalysisResult(Snapshot snapshot,
                            Config config,
                            List<Finding> findings,
                            List<Hypothesis> hypotheses,
                            Timeline timeline,
                            List<String> parseNotes,
                            Map<String, String> ruleStatus,
                            Instant generatedAt,
                            long elapsedMillis,
                            String toolVersion) {

    public boolean hasHighRisk() {
        return findings.stream().anyMatch(f -> f.severity().atLeast(Severity.HIGH)
                || f.severity() == Severity.CRITICAL);
    }

    public List<Finding> bySeverity(Severity max) {
        return findings.stream().filter(f -> f.severity().atLeast(max)).toList();
    }

    public long countAtLeast(Severity max) {
        return findings.stream().filter(f -> f.severity().atLeast(max)).count();
    }

    public java.util.Map<Severity, Long> census() {
        Map<Severity, Long> out = new java.util.EnumMap<>(Severity.class);
        for (Severity s : Severity.values()) {
            out.put(s, 0L);
        }
        findings.forEach(f -> out.merge(f.severity(), 1L, Long::sum));
        return out;
    }

    public String topHypothesis() {
        return hypotheses.isEmpty() ? "nothing actionable was found" : hypotheses.get(0).claim();
    }
}
