package dev.jingyu.jia.analyze;

import dev.jingyu.jia.model.Config;
import dev.jingyu.jia.model.Finding;
import dev.jingyu.jia.model.Snapshot;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Runs the registered rules over a snapshot and assembles the result. */
public final class Engine {

    /**
     * The version this build was made from. It comes out of {@code jia.properties}, which Maven
     * filters from the pom, so the number in a report and the number on the release page cannot be
     * two different hand edits. {@code dev} means the classes were compiled without a pom-driven
     * build (an IDE run configuration), which is the only case the fallback is for.
     */
    public static final String VERSION = readVersion();

    private static String readVersion() {
        try (var in = Engine.class.getResourceAsStream("/jia.properties")) {
            if (in == null) {
                return "dev";
            }
            java.util.Properties p = new java.util.Properties();
            p.load(in);
            String v = p.getProperty("version", "").trim();
            return v.isEmpty() || v.startsWith("${") ? "dev" : v;
        } catch (java.io.IOException e) {
            return "dev";
        }
    }

    /** Ceiling on the evidence lines of a merged finding; two dumps prove the same fact once. */
    private static final int MAX_MERGED_EVIDENCE = 8;

    private final List<Rule> rules;

    public Engine() {
        this(Rules.all());
    }

    public Engine(List<Rule> rules) {
        this.rules = List.copyOf(rules);
    }

    public List<Rule> rules() {
        return rules;
    }

    public AnalysisResult analyze(Snapshot snapshot, Config config) {
        long start = System.nanoTime();
        List<Finding> findings = new ArrayList<>();
        Map<String, String> status = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        List<String> notes = new ArrayList<>(snapshotNotes(snapshot));

        for (Rule rule : rules) {
            if (!rule.applies(snapshot)) {
                status.put(rule.id(), "not-applicable");
                continue;
            }
            try {
                List<Finding> produced = rule.evaluate(snapshot, config);
                findings.addAll(produced);
                if (!produced.isEmpty()) {
                    status.put(rule.id(), produced.size() + " finding(s)");
                    continue;
                }
                // Empty is not one thing: it can mean "I measured and nothing is wrong" or "I could not
                // measure". Only the first is allowed to be read as an all-clear.
                String why = rule.declined(snapshot, config);
                status.put(rule.id(), why == null || why.isBlank() ? "clean" : "declined: " + why);
            } catch (RuntimeException | Error e) {
                // A broken rule must never cost the reader the rest of the report.
                status.put(rule.id(), "error");
                errors.add(rule.id() + " failed: " + e);
            }
        }
        List<Finding> merged = dedupe(findings);
        Collections.sort(merged);

        List<Hypothesis> hypotheses = new HypothesisBuilder(merged).build();
        Timeline timeline;
        try {
            timeline = Timeline.build(snapshot, config, findings);
        } catch (RuntimeException e) {
            timeline = Timeline.empty();
            errors.add("timeline could not be assembled: " + e);
        }
        long elapsed = (System.nanoTime() - start) / 1_000_000L;
        return new AnalysisResult(snapshot, config, List.copyOf(merged), hypotheses, timeline,
                List.copyOf(concat(notes, errors)),
                // Sorted, not Map.copyOf: the immutable-map copy iterates in an order the JDK documents
                // as unpredictable and allowed to vary between runs, and this tool promises the same
                // input renders the same bytes. Rule ids sorted is both stable and nicer to diff.
                java.util.Collections.unmodifiableSortedMap(new java.util.TreeMap<>(status)),
                Instant.now(), elapsed, VERSION);
    }

    private static final java.util.regex.Pattern FILE_TOKEN =
            java.util.regex.Pattern.compile("\\S+\\.(?:dump|log|txt|histo|out|jstack)\\b");

    /**
     * Two dumps of the same JVM describe the same problem. A finding that recurs across dumps is
     * merged into one, with the files it was seen in kept as a metric — repeating the identical
     * paragraph twice costs the reader attention they should spend on the fix.
     */
    static List<Finding> dedupe(List<Finding> in) {
        Map<String, List<Finding>> groups = new LinkedHashMap<>();
        for (Finding f : in) {
            String key = f.ruleId() + "|" + f.title() + "|"
                    + FILE_TOKEN.matcher(f.summary()).replaceAll("<file>");
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(f);
        }
        List<Finding> out = new ArrayList<>();
        for (List<Finding> group : groups.values()) {
            if (group.size() == 1) {
                out.add(group.get(0));
                continue;
            }
            Finding first = group.get(0);
            var b = Finding.builder(first.ruleId())
                    .title(first.title())
                    .artifact(first.artifact())
                    .summary(first.summary())
                    .confidence(group.stream().mapToDouble(Finding::confidence).max().orElse(0.5))
                    .severity(group.stream().map(Finding::severity).min(java.util.Comparator.naturalOrder())
                            .orElse(first.severity()));
            var files = new ArrayList<String>();
            for (Finding f : group) {
                b.evidence(f.evidence());
                f.recommendations().forEach(b::recommend);
                b.metrics(f.metrics());
                Object file = f.metrics().get("file");
                if (file != null) {
                    files.add(String.valueOf(file));
                }
            }
            if (files.size() > 1) {
                b.metric("seenIn", files.stream().distinct().toList());
            }
            Finding mergedOne = b.build();
            out.add(mergedOne.evidence().size() > MAX_MERGED_EVIDENCE
                    ? trimEvidence(mergedOne)
                    : mergedOne);
        }
        return out;
    }

    /** Merging two dumps would otherwise double the evidence block for no added proof. */
    private static Finding trimEvidence(Finding f) {
        var b = Finding.builder(f.ruleId())
                .title(f.title())
                .artifact(f.artifact())
                .severity(f.severity())
                .summary(f.summary())
                .confidence(f.confidence())
                .metrics(f.metrics());
        b.evidence(f.evidence().subList(0, MAX_MERGED_EVIDENCE));
        f.recommendations().forEach(b::recommend);
        b.metric("evidenceTruncated", f.evidence().size() - MAX_MERGED_EVIDENCE);
        return b.build();
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    private static List<String> snapshotNotes(Snapshot snapshot) {
        List<String> out = new ArrayList<>();
        snapshot.unparsed().forEach(u -> out.add(u.file() + ": " + u.reason()));
        snapshot.gcLog().ifPresent(log -> log.notes().forEach(n -> out.add(log.source().name() + ": " + n)));
        return out;
    }
}
