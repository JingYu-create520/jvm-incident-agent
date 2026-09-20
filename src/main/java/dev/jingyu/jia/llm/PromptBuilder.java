package dev.jingyu.jia.llm;

import dev.jingyu.jia.analyze.AnalysisResult;
import dev.jingyu.jia.analyze.Hypothesis;
import dev.jingyu.jia.model.Evidence;
import dev.jingyu.jia.model.Finding;

import java.util.List;

/** Builds the two prompts the LLM layer is allowed to use. */
public final class PromptBuilder {

    public static final String SYSTEM = """
            You are the narrative layer of jvm-incident-agent, a deterministic JVM incident analyzer.

            The findings below were produced by fixed rules over parsed thread dumps, GC logs, heap
            histograms and exception stacks. They are already ranked and each one quotes file:line
            evidence.

            Your job is exactly three things:
            1. say, in plain language, what happened and in what order;
            2. explain what each top finding means for a running service and what to do first;
            3. state what this snapshot cannot tell us.

            Hard limits: you may not add a finding, drop a finding, change a severity, invent a metric,
            or name a file, class or line that is not in the input. If you are unsure whether something
            is in the input, do not write it. Answer in the same language as the request. No preamble.
            """;

    public String narrate(AnalysisResult r) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("## Snapshot\n").append(r.snapshot().describe().orElse("unknown")).append('\n');
        if (!r.snapshot().filesSeen().isEmpty()) {
            sb.append("Files: ").append(String.join(", ", r.snapshot().filesSeen())).append('\n');
        }
        sb.append("\n## Ranked hypotheses (from the rule engine)\n");
        int i = 0;
        for (Hypothesis h : r.hypotheses()) {
            sb.append(++i).append(". [").append(h.severity()).append(" | ")
                    .append(String.format(java.util.Locale.ROOT, "%.0f%%", h.confidence() * 100))
                    .append("] ").append(h.claim()).append('\n');
            sb.append("   rules: ").append(String.join(", ", h.ruleIds())).append('\n');
        }
        sb.append("\n## Findings (evidence already verified by the parser)\n");
        for (Finding f : r.findings()) {
            sb.append("- ").append(f.ruleId()).append(" [").append(f.severity()).append(" ")
                    .append(String.format(java.util.Locale.ROOT, "%.0f%%", f.confidence() * 100))
                    .append("] ").append(f.title()).append('\n');
            sb.append("  ").append(f.summary().replace('\n', ' ')).append('\n');
            for (Evidence e : limited(f.evidence(), 3)) {
                sb.append("  evidence ").append(e.file()).append(':').append(e.startLine());
                if (e.quote() != null && !e.quote().isBlank()) {
                    sb.append("  \"").append(trim(e.quote())).append('"');
                }
                sb.append('\n');
            }
        }
        sb.append("\n## Measured totals\n");
        r.census().forEach((k, v) -> sb.append(k).append('=').append(v).append(' '));
        sb.append('\n');
        sb.append("\nWrite the report's narrative section: what happened, what to do first, and what ")
                .append("this snapshot cannot tell us. Stay inside the findings above.");
        return sb.toString();
    }

    public String explain(Finding f) {
        return "Explain this JVM incident finding to its on-call engineer: what it means, why the "
                + "evidence supports it, and the smallest next action. Use only the finding below.\n\n"
                + f.ruleId() + " — " + f.title() + "\nSeverity " + f.severity() + ", confidence "
                + String.format(java.util.Locale.ROOT, "%.0f%%", f.confidence() * 100) + "\n"
                + f.summary() + "\n"
                + "Evidence:\n"
                + f.evidence().stream().map(e -> "  " + e.file() + ":" + e.startLine() + " " + trim(e.quote()))
                .reduce((a, b) -> a + "\n" + b).orElse("(none)")
                + "\n";
    }

    private static List<Evidence> limited(List<Evidence> in, int n) {
        return in.size() <= n ? in : in.subList(0, n);
    }

    private static String trim(String s) {
        if (s == null) {
            return "";
        }
        String t = s.strip().replaceAll("\\s+", " ");
        return t.length() <= 120 ? t : t.substring(0, 120) + "…";
    }
}
