package dev.jingyu.jia.llm;

import dev.jingyu.jia.analyze.AnalysisResult;
import dev.jingyu.jia.analyze.Hypothesis;
import dev.jingyu.jia.model.Finding;
import dev.jingyu.jia.model.Severity;

import java.util.ArrayList;
import java.util.List;

/**
 * Offline narrator. Produces the same three-paragraph shape a model would, from the
 * findings themselves, so the tool is useful on a plane and in CI with no credentials.
 *
 * <p>This is the default provider. It is deterministic by construction: same snapshot,
 * same bytes.
 */
public final class MockProvider implements Provider {

    @Override
    public String name() {
        return "mock";
    }

    @Override
    public String narrate(AnalysisResult result) {
        StringBuilder sb = new StringBuilder();
        List<Finding> high = new ArrayList<>();
        for (Finding f : result.findings()) {
            if (f.severity() == Severity.CRITICAL || f.severity() == Severity.HIGH) {
                high.add(f);
            }
        }
        sb.append("**What the evidence says.** ");
        if (result.hypotheses().isEmpty()) {
            sb.append("Nothing in these artifacts rises above ordinary noise. ");
            if (result.findings().isEmpty()) {
                sb.append("Every rule ran clean. ");
            } else {
                sb.append("Only informational notes were produced. ");
            }
            sb.append("Either the incident is not present in what was captured, or it lives in an ")
                    .append("artefact this tool does not open — a heap dump or a flame graph.\n");
            return sb.toString().strip();
        }
        Hypothesis top = result.hypotheses().get(0);
        sb.append(top.claim()).append('\n');

        if (!high.isEmpty()) {
            sb.append("\n**Supporting observations.** ");
            List<String> parts = new ArrayList<>();
            for (Finding f : high) {
                parts.add(f.ruleId() + " — " + firstSentence(f.summary()));
            }
            sb.append(String.join(" ", parts)).append('\n');
        }
        sb.append("\n**Order of work.** ");
        List<String> actions = top.actions();
        if (actions.isEmpty()) {
            sb.append("Re-capture with a second thread dump a minute apart, then re-run; a single ")
                    .append("snapshot cannot show direction of travel.\n");
        } else {
            for (int i = 0; i < Math.min(3, actions.size()); i++) {
                sb.append(i + 1).append(") ").append(actions.get(i)).append(' ');
            }
            sb.append('\n');
        }
        return sb.toString().strip();
    }

    private static String firstSentence(String s) {
        int cut = s.indexOf(". ");
        String t = cut < 0 ? s : s.substring(0, cut + 1);
        return t.replace("\n", " ").strip();
    }
}
