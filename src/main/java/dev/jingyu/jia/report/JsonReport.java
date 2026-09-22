package dev.jingyu.jia.report;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.jingyu.jia.analyze.AnalysisResult;
import dev.jingyu.jia.analyze.Hypothesis;
import dev.jingyu.jia.analyze.Rule;
import dev.jingyu.jia.analyze.Rules;
import dev.jingyu.jia.analyze.TimelineEntry;
import dev.jingyu.jia.model.Evidence;
import dev.jingyu.jia.model.Finding;
import dev.jingyu.jia.model.GcEvent;
import dev.jingyu.jia.model.GcLog;
import dev.jingyu.jia.model.Histo;
import dev.jingyu.jia.model.JThread;
import dev.jingyu.jia.model.Snapshot;
import dev.jingyu.jia.model.ThreadDump;

/** Structured output for programs and agents. Same facts as the Markdown, no prose. */
public final class JsonReport {

    private final ObjectMapper mapper = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public String render(AnalysisResult r, String narrative) {
        try {
            return mapper.writeValueAsString(root(r, narrative));
        } catch (Exception e) {
            throw new IllegalStateException("cannot serialise analysis: " + e.getMessage(), e);
        }
    }

    private ObjectNode root(AnalysisResult r, String narrative) {
        ObjectNode n = mapper.createObjectNode();
        n.put("schema", "jvm-incident-agent/1");
        n.put("tool", "jvm-incident-agent");
        n.put("version", r.toolVersion());
        n.put("generatedAt", r.generatedAt().toString());
        n.put("elapsedMillis", r.elapsedMillis());
        // Not findings and not warnings: inputs that were read off disk and deliberately left out of
        // these findings. An agent that cannot see this list cannot tell a covered window from a half one.
        ArrayNode ignored = n.putArray("ignoredInputs");
        for (Snapshot.Skipped s : r.snapshot().skipped()) {
            ObjectNode drop = ignored.addObject();
            drop.put("kind", s.kind());
            drop.put("kept", s.kept());
            drop.set("notAnalysed", mapper.valueToTree(s.dropped()));
            drop.put("note", s.sentence());
        }
        n.set("snapshot", snapshot(r.snapshot()));
        n.set("config", config(r));
        n.set("thresholds", thresholds(r.config()));
        n.set("census", census(r));
        n.set("hypotheses", hypotheses(r.hypotheses()));
        n.set("findings", findings(r.findings()));
        n.set("timeline", timeline(r));
        n.set("ruleStatus", mapper.valueToTree(r.ruleStatus()));
        ArrayNode notes = n.putArray("notes");
        r.parseNotes().forEach(notes::add);
        if (narrative != null && !narrative.isBlank()) {
            n.put("narrative", narrative);
        }
        return n;
    }

    private ObjectNode snapshot(Snapshot s) {
        ObjectNode n = mapper.createObjectNode();
        s.root().ifPresent(p -> n.put("path", p.toString()));
        ArrayNode files = n.putArray("files");
        s.filesSeen().forEach(files::add);
        ArrayNode dumps = n.putArray("threadDumps");
        for (ThreadDump d : s.threadDumps()) {
            ObjectNode t = dumps.addObject();
            t.put("file", d.source().name());
            t.put("threads", d.size());
            t.put("vm", d.vmDescription());
            t.put("capturedAt", d.capturedAtRaw());
            t.put("jstackReportedDeadlock", d.vmReportedDeadlock());
            ObjectNode states = t.putObject("byState");
            d.byState().forEach((k, v) -> states.put(k.name(), v));
        }
        s.gcLog().ifPresent(g -> {
            ObjectNode t = n.putObject("gcLog");
            t.put("file", g.source().name());
            t.put("collector", g.collector().name());
            t.put("format", g.unified() ? "unified" : "jdk8-traditional");
            t.put("events", g.events().size());
            t.put("majorCollections", g.majorCollections().size());
            t.put("spanSeconds", round(g.durationSec()));
            t.put("p50PauseMs", round(g.pausePercentile(50)));
            t.put("p95PauseMs", round(g.pausePercentile(95)));
            t.put("p99PauseMs", round(g.pausePercentile(99)));
            t.put("maxPauseMs", round(g.pauseMax()));
            t.put("throughput", round(g.throughput()));
            t.put("bootEpochMillis", g.bootEpochMillis());
            ArrayNode evs = t.putArray("events");
            for (GcEvent e : g.events()) {
                ObjectNode x = evs.addObject();
                x.put("line", e.line());
                x.put("kind", e.kind().name());
                x.put("atSec", round(e.atSec()));
                x.put("pauseMs", e.pauseMs() == null ? null : round(e.pauseMs()));
                x.put("cause", e.cause());
                x.put("heapBeforeMb", e.heapBeforeMb() == null ? null : round(e.heapBeforeMb()));
                x.put("heapAfterMb", e.heapAfterMb() == null ? null : round(e.heapAfterMb()));
                x.put("capacityMb", e.capacityMb() == null ? null : round(e.capacityMb()));
                x.put("toSpaceExhausted", e.toSpaceExhausted());
                x.put("humongous", e.humongous());
            }
        });
        s.histo().ifPresent(h -> {
            ObjectNode t = n.putObject("histo");
            t.put("file", h.source().name());
            t.put("live", h.live());
            t.put("totalBytes", h.totalBytes());
            t.put("totalInstances", h.totalInstances());
            ArrayNode top = t.putArray("topByBytes");
            h.topByBytes(15).forEach(c -> {
                ObjectNode x = top.addObject();
                x.put("line", c.line());
                x.put("class", c.className());
                x.put("instances", c.instances());
                x.put("bytes", c.bytes());
                x.put("share", round(h.shareOf(c)));
            });
        });
        ArrayNode excs = n.putArray("exceptions");
        s.exceptions().forEach(o -> {
            ObjectNode x = excs.addObject();
            x.put("file", o.source().name());
            x.put("line", o.startLine());
            x.put("outer", o.outermost().className());
            x.put("root", o.rootCause().className());
            x.put("message", o.rootCause().message());
            x.put("chainDepth", o.chain().size());
            x.put("timestamp", o.timestampRaw());
            x.put("epochMillis", o.epochMillis());
            x.put("rootFrame", o.rootCause().frames().isEmpty() ? null
                    : o.rootCause().frames().get(0).display());
        });
        ArrayNode unparsed = n.putArray("unparsed");
        s.unparsed().forEach(u -> {
            ObjectNode x = unparsed.addObject();
            x.put("file", u.file());
            x.put("reason", u.reason());
        });
        return n;
    }

    private ObjectNode config(AnalysisResult r) {
        ObjectNode n = mapper.createObjectNode();
        n.put("slaPauseMs", r.config().slaPauseMs());
        n.put("fullGcPerMinute", r.config().fullGcPerMinute());
        n.put("fullGcWindowSec", r.config().fullGcWindowSec());
        n.put("threadLeakThreshold", r.config().threadLeakThreshold());
        n.put("lockWaiterThreshold", r.config().lockWaiterThreshold());
        n.put("stackClusterThreshold", r.config().stackClusterThreshold());
        n.put("exceptionClusterThreshold", r.config().exceptionClusterThreshold());
        n.put("throughputFloor", r.config().throughputFloor());
        n.put("histoDominanceRatio", r.config().histoDominanceRatio());
        return n;
    }

    /**
     * Every threshold in force, keyed by the CLI flag that moves it. A finding that depends on a
     * boundary is only as trustworthy as the reader's ability to re-run it at another boundary, so
     * the numbers that decided the report travel with the report.
     */
    private ObjectNode thresholds(dev.jingyu.jia.model.Config config) {
        ObjectNode n = mapper.createObjectNode();
        config.asMap().forEach(n::put);
        return n;
    }

    private ObjectNode census(AnalysisResult r) {
        ObjectNode n = mapper.createObjectNode();
        r.census().forEach((k, v) -> n.put(k.name(), v));
        return n;
    }

    private ArrayNode hypotheses(java.util.List<Hypothesis> list) {
        ArrayNode a = mapper.createArrayNode();
        for (Hypothesis h : list) {
            ObjectNode n = a.addObject();
            n.put("id", h.id());
            n.put("claim", h.claim());
            n.put("severity", h.severity().name());
            n.put("confidence", round(h.confidence()));
            n.set("rules", mapper.valueToTree(h.ruleIds()));
            n.set("actions", mapper.valueToTree(h.actions()));
            n.set("evidence", evidence(h.evidence()));
        }
        return a;
    }

    private ArrayNode findings(java.util.List<Finding> list) {
        ArrayNode a = mapper.createArrayNode();
        for (Finding f : list) {
            ObjectNode n = a.addObject();
            n.put("ruleId", f.ruleId());
            n.put("title", f.title());
            n.put("severity", f.severity().name());
            n.put("confidence", round(f.confidence()));
            n.put("artifact", f.artifact().label());
            n.put("summary", f.summary());
            n.set("recommendations", mapper.valueToTree(f.recommendations()));
            n.set("evidence", evidence(f.evidence()));
            n.set("metrics", mapper.valueToTree(f.metrics()));
        }
        return a;
    }

    private ArrayNode evidence(java.util.List<Evidence> list) {
        ArrayNode a = mapper.createArrayNode();
        for (Evidence e : list) {
            ObjectNode n = a.addObject();
            n.put("file", e.file());
            n.put("startLine", e.startLine());
            n.put("endLine", e.endLine());
            n.put("quote", e.quote());
            n.put("note", e.note());
        }
        return a;
    }

    private ArrayNode timeline(AnalysisResult r) {
        ArrayNode a = mapper.createArrayNode();
        for (TimelineEntry e : r.timeline().entries()) {
            ObjectNode n = a.addObject();
            n.put("epochMillis", e.epochMillis());
            n.put("uptimeSec", e.uptimeSec() == null ? null : round(e.uptimeSec()));
            n.put("label", e.label());
            n.put("detail", e.detail());
            n.put("source", e.source().label());
            n.put("file", e.file());
            n.put("line", e.line());
            n.put("major", e.major());
        }
        return a;
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    /** Rule catalogue for {@code jia rules --format json} and the MCP {@code list_rules} tool. */
    public String rulesJson() {
        ArrayNode a = mapper.createArrayNode();
        for (Rule r : Rules.sorted()) {
            ObjectNode n = a.addObject();
            n.put("id", r.id());
            n.put("title", r.title());
            n.put("artifact", r.artifact().label());
            n.put("doc", r.doc());
        }
        try {
            return mapper.writeValueAsString(a);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
