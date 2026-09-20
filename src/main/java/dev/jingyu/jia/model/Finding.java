package dev.jingyu.jia.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One deterministic observation produced by a rule.
 *
 * <p>The LLM layer never creates, removes or reweights findings — that boundary
 * is locked by a test. Everything here comes from a parser plus a rule.
 */
public final class Finding implements Comparable<Finding> {

    private final String ruleId;
    private final String title;
    private final Severity severity;
    private final double confidence;
    private final ArtifactKind artifact;
    private final String summary;
    private final List<String> recommendations;
    private final List<Evidence> evidence;
    private final Map<String, Object> metrics;

    private Finding(Builder b) {
        this.ruleId = b.ruleId;
        this.title = b.title;
        this.severity = b.severity;
        this.confidence = b.confidence;
        this.artifact = b.artifact;
        this.summary = b.summary;
        this.recommendations = List.copyOf(b.recommendations);
        this.evidence = List.copyOf(b.evidence);
        this.metrics = Collections.unmodifiableMap(new LinkedHashMap<>(b.metrics));
    }

    public static Builder builder(String ruleId) {
        return new Builder(ruleId);
    }

    public String ruleId() {
        return ruleId;
    }

    public String title() {
        return title;
    }

    public Severity severity() {
        return severity;
    }

    public double confidence() {
        return confidence;
    }

    public ArtifactKind artifact() {
        return artifact;
    }

    public String summary() {
        return summary;
    }

    public List<String> recommendations() {
        return recommendations;
    }

    public List<Evidence> evidence() {
        return evidence;
    }

    public Map<String, Object> metrics() {
        return metrics;
    }

    /** Severity first, then confidence, then rule id so output is stable. */
    @Override
    public int compareTo(Finding o) {
        int c = Integer.compare(severity.ordinal(), o.severity.ordinal());
        if (c != 0) {
            return c;
        }
        c = Double.compare(o.confidence, confidence);
        if (c != 0) {
            return c;
        }
        return ruleId.compareTo(o.ruleId);
    }

    @Override
    public String toString() {
        return ruleId + " " + severity + " " + title;
    }

    /**
     * Value equality. Findings are data, and tests — including the one that locks the LLM layer
     * out of this list — compare them as values.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Finding f)) {
            return false;
        }
        return Double.compare(f.confidence, confidence) == 0
                && ruleId.equals(f.ruleId)
                && title.equals(f.title)
                && severity == f.severity
                && artifact == f.artifact
                && summary.equals(f.summary)
                && recommendations.equals(f.recommendations)
                && evidence.equals(f.evidence)
                && metrics.equals(f.metrics);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(ruleId, title, severity, artifact, summary, recommendations,
                evidence, metrics, confidence);
    }

    public static final class Builder {
        private final String ruleId;
        private String title = "";
        private Severity severity = Severity.INFO;
        private double confidence = 0.5;
        private ArtifactKind artifact = ArtifactKind.UNKNOWN;
        private String summary = "";
        private final List<String> recommendations = new ArrayList<>();
        private final List<Evidence> evidence = new ArrayList<>();
        private final Map<String, Object> metrics = new LinkedHashMap<>();

        private Builder(String ruleId) {
            this.ruleId = ruleId;
        }

        public Builder title(String v) {
            this.title = v;
            return this;
        }

        public Builder severity(Severity v) {
            this.severity = v;
            return this;
        }

        public Builder confidence(double v) {
            this.confidence = Math.max(0.0, Math.min(1.0, v));
            return this;
        }

        public Builder artifact(ArtifactKind v) {
            this.artifact = v;
            return this;
        }

        public Builder summary(String v) {
            this.summary = v;
            return this;
        }

        public Builder recommend(String v) {
            if (v != null && !v.isBlank()) {
                recommendations.add(v);
            }
            return this;
        }

        public Builder evidence(Evidence v) {
            if (v != null) {
                evidence.add(v);
            }
            return this;
        }

        public Builder evidence(List<Evidence> v) {
            evidence.addAll(v);
            return this;
        }

        public Builder metric(String k, Object v) {
            metrics.put(k, v);
            return this;
        }

        public Builder metrics(Map<String, Object> source) {
            source.forEach((k, v) -> metrics.putIfAbsent(k, v));
            return this;
        }

        public Finding build() {
            return new Finding(this);
        }
    }
}
