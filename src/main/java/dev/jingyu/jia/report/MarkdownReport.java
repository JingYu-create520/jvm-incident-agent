package dev.jingyu.jia.report;

import dev.jingyu.jia.analyze.AnalysisResult;
import dev.jingyu.jia.analyze.Hypothesis;
import dev.jingyu.jia.analyze.Rule;
import dev.jingyu.jia.analyze.Rules;
import dev.jingyu.jia.analyze.TimelineEntry;
import dev.jingyu.jia.model.Evidence;
import dev.jingyu.jia.model.Finding;
import dev.jingyu.jia.model.Histo;
import dev.jingyu.jia.model.Snapshot;
import dev.jingyu.jia.model.ThreadDump;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Deterministic Markdown rendering — no LLM is involved in producing this file. */
public final class MarkdownReport {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    public String render(AnalysisResult r, String narrative) {
        StringBuilder sb = new StringBuilder(16 * 1024);
        Snapshot s = r.snapshot();
        header(sb, r, s);
        verdict(sb, r, narrative);
        summaryTable(sb, r);
        hypotheses(sb, r);
        timeline(sb, r);
        findings(sb, r);
        coverage(sb, r);
        appendix(sb);
        return sb.toString();
    }

    private void header(StringBuilder sb, AnalysisResult r, Snapshot s) {
        sb.append("# JVM incident report\n\n");
        sb.append("| | |\n|---|---|\n");
        row(sb, "Generated", STAMP.format(r.generatedAt()) + " · local time");
        row(sb, "Tool", "jvm-incident-agent " + r.toolVersion() + " · " + r.elapsedMillis()
                + " ms · " + Rules.all().size() + " rules evaluated");
        row(sb, "Inputs", md(s.describe().orElse("nothing recognised")));
        row(sb, "Files", s.filesSeen().isEmpty() ? "—" : md(String.join(", ", s.filesSeen())));
        row(sb, "Pause SLA", r.config().slaPauseMs() + " ms");
        sb.append('\n');
    }

    private static void row(StringBuilder sb, String k, String v) {
        sb.append("| **").append(k).append("** | ").append(v).append(" |\n");
    }

    private void verdict(StringBuilder sb, AnalysisResult r, String narrative) {
        sb.append("## Verdict\n\n");
        if (r.hypotheses().isEmpty()) {
            sb.append("**No high-confidence problem found in the supplied artifacts.** ")
                    .append(r.findings().isEmpty()
                            ? "Every rule ran clean against this snapshot.\n\n"
                            : "Only informational notes were produced; see Coverage and limits.\n\n");
        } else {
            Hypothesis top = r.hypotheses().get(0);
            sb.append("**").append(md(top.claim())).append("**\n\n");
            sb.append("Confidence ").append(pct(top.confidence()))
                    .append(" · severity ").append(top.severity())
                    .append(" · corroborated by ").append(String.join(", ", top.ruleIds()))
                    .append("\n\n");
        }
        if (narrative != null && !narrative.isBlank()) {
            sb.append(stripDuplicatedVerdict(r, narrative)).append("\n\n");
        }
    }

    /**
     * The offline narrator opens by restating the top hypothesis, which the deterministic verdict
     * line has already printed in bold. Saying it twice makes the report look generated; drop the
     * echo and keep what follows.
     */
    private static String stripDuplicatedVerdict(AnalysisResult r, String narrative) {
        String text = narrative.strip();
        if (r.hypotheses().isEmpty()) {
            return text;
        }
        String claim = r.hypotheses().get(0).claim().strip();
        for (String candidate : List.of(claim, "**What the evidence says.** " + claim)) {
            if (text.startsWith(candidate)) {
                String rest = text.substring(candidate.length()).strip();
                if (!rest.isBlank()) {
                    return rest;
                }
            }
        }
        return text;
    }

    private void summaryTable(StringBuilder sb, AnalysisResult r) {
        if (r.findings().isEmpty()) {
            return;
        }
        sb.append("## Findings\n\n");
        sb.append("| Severity | Rule | Finding | Where | Confidence |\n|---|---|---|---|---|\n");
        for (Finding f : r.findings()) {
            Evidence first = f.evidence().isEmpty() ? null : f.evidence().get(0);
            sb.append("| ").append(f.severity())
                    .append(" | `").append(f.ruleId()).append('`')
                    .append(" | ").append(md(f.title()))
                    .append(" | ").append(first == null ? "—" : "`" + first.file() + ":" + first.startLine() + "`")
                    .append(" | ").append(pct(f.confidence()))
                    .append(" |\n");
        }
        sb.append('\n');
    }

    private void hypotheses(StringBuilder sb, AnalysisResult r) {
        if (r.hypotheses().isEmpty()) {
            return;
        }
        sb.append("## Root-cause hypotheses, ranked\n\n");
        int i = 0;
        for (Hypothesis h : r.hypotheses()) {
            if (++i > 6) {
                sb.append("- … and ")
                        .append(r.hypotheses().size() - i + 1)
                        .append(" lower-ranked hypotheses (see `--format json`)\n");
                break;
            }
            sb.append(i).append(". **").append(md(h.claim())).append("**\n");
            sb.append("   - confidence ").append(pct(h.confidence()))
                    .append(", severity ").append(h.severity())
                    .append(", rules ").append(String.join(", ", h.ruleIds())).append('\n');
            for (String a : h.actions()) {
                sb.append("   - action: ").append(a).append('\n');
            }
        }
        sb.append('\n');
    }

    private void timeline(StringBuilder sb, AnalysisResult r) {
        List<TimelineEntry> entries = r.timeline().entries();
        if (entries.isEmpty()) {
            return;
        }
        sb.append("## Timeline\n\n");
        if (!r.timeline().singleAxis()) {
            sb.append("> The GC log carried only JVM uptime while the application log carried only wall ")
                    .append("clock, with no shared origin between them. Entries are grouped by source and ")
                    .append("ordered correctly inside each group.\n\n");
        }
        sb.append("| When | What | Detail |\n|---|---|---|\n");
        int shown = 0;
        for (TimelineEntry e : entries) {
            if (shown++ >= 40) {
                sb.append("| … | ").append(entries.size() - shown).append(" more entries omitted | |\n");
                break;
            }
            sb.append("| `").append(timeLabel(e, r.timeline().bootEpochMillis())).append('`')
                    .append(" | ").append(e.major() ? "**" + md(e.label()) + "**" : md(e.label()))
                    .append(" | ").append(md(safe(e.detail())))
                    .append(e.file() == null || e.line() == null
                            ? "" : " — `" + e.file() + ":" + e.line() + "`")
                    .append(" |\n");
        }
        sb.append('\n');
    }

    private static String timeLabel(TimelineEntry e, Long boot) {
        if (e.uptimeSec() != null) {
            return String.format(Locale.ROOT, "+%.3fs", e.uptimeSec());
        }
        if (e.epochMillis() != null) {
            if (boot != null) {
                return String.format(Locale.ROOT, "+%.3fs", (e.epochMillis() - boot) / 1000.0);
            }
            return STAMP.format(java.time.Instant.ofEpochMilli(e.epochMillis()));
        }
        return "—";
    }

    private void findings(StringBuilder sb, AnalysisResult r) {
        if (r.findings().isEmpty()) {
            return;
        }
        sb.append("## Findings in detail\n\n");
        for (Finding f : r.findings()) {
            sb.append("### ").append(f.ruleId()).append(" · ").append(f.title()).append('\n');
            sb.append("_").append(f.severity()).append(" · confidence ").append(pct(f.confidence()))
                    .append(" · from ").append(f.artifact().label()).append("_\n\n");
            sb.append(f.summary()).append("\n\n");
            if (!f.evidence().isEmpty()) {
                sb.append("**Evidence**\n\n```\n");
                for (Evidence e : f.evidence()) {
                    sb.append(e.file()).append(':').append(e.startLine());
                    if (e.endLine() != e.startLine()) {
                        sb.append('-').append(e.endLine());
                    }
                    sb.append("  ").append(collapse(e.quote()));
                    if (e.note() != null && !e.note().isBlank()) {
                        sb.append("   ← ").append(e.note());
                    }
                    sb.append('\n');
                }
                sb.append("```\n\n");
            }
            if (!f.metrics().isEmpty()) {
                sb.append("**Measured**\n\n");
                for (Map.Entry<String, Object> m : f.metrics().entrySet()) {
                    if ("variant".equals(m.getKey()) || "fingerprint".equals(m.getKey())) {
                        continue;
                    }
                    sb.append("- `").append(m.getKey()).append("` = ").append(m.getValue()).append('\n');
                }
                sb.append('\n');
            }
            if (!f.recommendations().isEmpty()) {
                sb.append("**What to do**\n\n");
                for (String rec : f.recommendations()) {
                    sb.append("- ").append(rec).append('\n');
                }
                sb.append('\n');
            }
        }
    }

    private void coverage(StringBuilder sb, AnalysisResult r) {
        sb.append("## Coverage and limits\n\n");
        Snapshot s = r.snapshot();
        for (String note : s.skippedSentences()) {
            sb.append("- ").append(md(note)).append('\n');
        }
        if (s.threadDumps().isEmpty()) {
            sb.append("- no thread dump in this snapshot — the TDA rules had nothing to read\n");
        } else {
            for (ThreadDump d : s.threadDumps()) {
                sb.append("- `").append(d.source().name()).append("`: ").append(d.size()).append(" threads");
                if (d.vmDescription() != null) {
                    sb.append(" · ").append(md(d.vmDescription()));
                }
                sb.append('\n');
            }
        }
        if (s.gcLog().isPresent()) {
            var g = s.gcLog().get();
            sb.append("- `").append(g.source().name()).append("`: ").append(g.events().size())
                    .append(" collected events · ").append(g.collector()).append(" · ")
                    .append(g.unified() ? "unified -Xlog format" : "JDK 8 traditional format").append('\n');
        } else {
            sb.append("- no GC log in this snapshot — the GCA rules had nothing to read\n");
        }
        if (s.histo().isPresent()) {
            Histo h = s.histo().get();
            sb.append("- `").append(h.source().name()).append("`: ").append(h.classes().size())
                    .append(" classes · ").append(Histo.humanBytes(h.totalBytes())).append(" counted\n");
        } else {
            sb.append("- no heap histogram in this snapshot — the HIS rules had nothing to read\n");
        }
        sb.append("- ").append(s.exceptions().size()).append(" exception stacks parsed")
                .append(s.exceptions().isEmpty() ? " — the EXC rules had nothing to read" : "").append('\n');
        for (var u : s.unparsed()) {
            sb.append("- `").append(u.file()).append("` was not understood: ").append(u.reason()).append('\n');
        }
        for (String note : r.parseNotes()) {
            sb.append("- note: ").append(note).append('\n');
        }
        sb.append("\nNothing was uploaded. Parsing, rules and rendering all happen in this process. ")
                .append("Findings come from fixed rules over parsed text, so identical input always produces ")
                .append("identical findings — the narrative section is the only part that can differ ")
                .append("between runs.\n\n");
    }

    private void appendix(StringBuilder sb) {
        sb.append("---\n\n")
                .append("Every rule is documented and stable: `jia explain TDA001`. ")
                .append("Machine-readable output: `jia analyze <dir> --format json`. ")
                .append("Agent-facing: `jia mcp` serves the same rules over MCP.\n");
    }

    private static String pct(double v) {
        return String.format(Locale.ROOT, "%.0f%%", v * 100);
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    /** Keep table cells on one line and let pipes through as text. */
    private static String md(String s) {
        return s == null ? "" : s.replace("|", "\\|").replace("\n", " ");
    }

    private static String collapse(String s) {
        if (s == null) {
            return "";
        }
        String t = s.strip().replaceAll("\\s+", " ");
        return t.length() <= 160 ? t : t.substring(0, 160) + "…";
    }
}
