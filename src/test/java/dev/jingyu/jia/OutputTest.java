package dev.jingyu.jia;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jingyu.jia.analyze.AnalysisResult;
import dev.jingyu.jia.analyze.Engine;
import dev.jingyu.jia.llm.MockProvider;
import dev.jingyu.jia.mcp.McpServer;
import dev.jingyu.jia.model.Config;
import dev.jingyu.jia.model.TextSource;
import dev.jingyu.jia.parse.ThreadDumpParser;
import dev.jingyu.jia.report.JsonReport;
import dev.jingyu.jia.report.MarkdownReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("output surfaces")
class OutputTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AnalysisResult deadlockResult() {
        var dump = ThreadDumpParser.parse(Fixtures.source("jdk17-deadlock.jstack")).value();
        var snap = dev.jingyu.jia.model.Snapshot.builder().addDump(dump.get(0)).build();
        return new Engine().analyze(snap, Config.defaults());
    }

    @Test
    @DisplayName("the Markdown report carries verdict, evidence and the limits of coverage")
    void markdownShape() {
        String md = new MarkdownReport().render(deadlockResult(), new MockProvider().narrate(deadlockResult()));
        for (String section : List.of("# JVM incident report", "## Verdict", "## Findings",
                "## Root-cause hypotheses, ranked", "## Findings in detail", "## Coverage and limits")) {
            assertTrue(md.contains(section), "missing section " + section);
        }
        assertTrue(md.contains("TDA001"));
        assertTrue(md.contains("jdk17-deadlock.jstack:"), "evidence must be quoted as file:line");
        assertTrue(md.contains("← "), "each evidence line should say why it is quoted");
        assertTrue(md.contains("no GC log in this snapshot"),
                "the report must admit what it could not look at");
        assertFalse(md.contains("null"), "a report that prints null looks broken: "
                + md.lines().filter(l -> l.contains("null")).findFirst().orElse(""));
    }

    @Test
    @DisplayName("JSON output is machine-consumable and carries the same facts")
    void jsonShape() {
        AnalysisResult r = deadlockResult();
        JsonNode n = readJson(new JsonReport().render(r, null));
        assertEquals("jvm-incident-agent/1", n.get("schema").asText());
        assertEquals(r.findings().size(), n.get("findings").size());
        assertEquals(r.hypotheses().get(0).id(), n.get("hypotheses").get(0).get("id").asText());
        JsonNode first = n.get("findings").get(0);
        assertEquals("TDA001", first.get("ruleId").asText());
        assertEquals("CRITICAL", first.get("severity").asText());
        assertTrue(first.get("evidence").get(0).has("startLine"));
        assertTrue(first.get("evidence").get(0).get("file").asText().endsWith(".jstack"));
        assertTrue(n.get("snapshot").get("threadDumps").get(0).get("byState").has("BLOCKED"));
    }

    @Test
    @DisplayName("the rule catalogue is valid JSON and complete")
    void rulesJson() {
        JsonNode n = readJson(new JsonReport().rulesJson());
        assertEquals(19, n.size());
        for (JsonNode rule : n) {
            assertTrue(rule.has("id") && rule.has("title") && rule.has("doc"));
        }
    }

    @Test
    @DisplayName("MCP: initialize, tools/list and tools/call over stdio")
    void mcpHandshake() throws Exception {
        String requests = String.join("\n",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}",
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"list_rules\",\"arguments\":{}}}",
                "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"explain_finding\","
                        + "\"arguments\":{\"ruleId\":\"TDA001\"}}}",
                "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"bogus.method\"}",
                "{ not json }",
                "");
        var in = new ByteArrayInputStream(requests.getBytes(StandardCharsets.UTF_8));
        var out = new ByteArrayOutputStream();
        PrintStream realOut = System.out;
        System.setIn(in);
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        try {
            new McpServer().serve();
        } finally {
            System.setOut(realOut);
        }
        List<String> lines = out.toString(StandardCharsets.UTF_8).lines().filter(l -> !l.isBlank()).toList();
        assertEquals(6, lines.size(), "the notification must not draw a response");
        JsonNode init = MAPPER.readTree(lines.get(0));
        assertEquals(McpServer.PROTOCOL_VERSION, init.get("result").get("protocolVersion").asText());
        assertEquals("jvm-incident-agent", init.get("result").get("serverInfo").get("name").asText());

        JsonNode tools = MAPPER.readTree(lines.get(1)).get("result").get("tools");
        assertEquals(3, tools.size());
        assertEquals("analyze_snapshot", tools.get(0).get("name").asText());
        assertTrue(tools.get(0).get("inputSchema").get("properties").has("content"));

        assertTrue(MAPPER.readTree(lines.get(2)).get("result").get("content").get(0).get("text")
                .asText().contains("GCA003"));
        assertTrue(MAPPER.readTree(lines.get(3)).get("result").get("content").get(0).get("text")
                .asText().startsWith("# TDA001"));
        assertEquals(-32601, MAPPER.readTree(lines.get(4)).get("error").get("code").asInt());
        assertEquals(-32700, MAPPER.readTree(lines.get(5)).get("error").get("code").asInt());
    }

    @Test
    @DisplayName("MCP analyze_snapshot works on pasted text, with no file involved")
    void mcpAnalyzePastedText() throws Exception {
        String dump = String.join("\n", Fixtures.source("jdk17-deadlock.jstack").lines());
        String req = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":"
                + "\"analyze_snapshot\",\"arguments\":{\"content\":" + MAPPER.writeValueAsString(dump)
                + ",\"fileName\":\"pasted.dump\"}}}";
        var in = new ByteArrayInputStream((req + "\n").getBytes(StandardCharsets.UTF_8));
        var out = new ByteArrayOutputStream();
        PrintStream realOut = System.out;
        System.setIn(in);
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        try {
            new McpServer().serve();
        } finally {
            System.setOut(realOut);
        }
        JsonNode reply = MAPPER.readTree(out.toString(StandardCharsets.UTF_8).lines().findFirst().orElseThrow());
        String text = reply.get("result").get("content").get(0).get("text").asText();
        JsonNode parsed = MAPPER.readTree(text);
        assertEquals("jvm-incident-agent/1", parsed.get("schema").asText());
        assertEquals("TDA001", parsed.get("findings").get(0).get("ruleId").asText());
        assertEquals("pasted.dump", parsed.get("findings").get(0).get("evidence").get(0).get("file").asText());
    }

    @Test
    @DisplayName("CLI: exit 1 when something is critical, 0 when clean, 2 on junk")
    void exitCodes(@TempDir Path tmp) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("incident"));
        Files.writeString(dir.resolve("threads.dump"), String.join("\n",
                Fixtures.source("jdk17-deadlock.jstack").lines()), StandardCharsets.UTF_8);
        assertEquals(1, Cli.run(new String[]{"analyze", dir.toString(), "--no-narrative", "-o",
                tmp.resolve("report.md").toString()}), "a deadlock must exit non-zero");
        assertTrue(Files.isRegularFile(tmp.resolve("report.md")));

        // The gate an operator configures. `never` turns the same snapshot into a pure report, and a
        // bad value is a usage error rather than a silent default back to "high".
        assertEquals(0, Cli.run(new String[]{"analyze", dir.toString(), "--no-narrative", "--fail-on",
                "never", "-f", "json", "-o", tmp.resolve("gate.json").toString()}),
                "--fail-on never must report without failing");
        assertEquals(1, Cli.run(new String[]{"analyze", dir.toString(), "--no-narrative", "--fail-on",
                "critical"}));
        assertEquals(1, Cli.run(new String[]{"analyze", dir.toString(), "--no-narrative", "--fail-on",
                "medium"}));
        assertEquals(2, Cli.run(new String[]{"analyze", dir.toString(), "--no-narrative", "--fail-on",
                "sometimes"}));

        Path clean = Files.createDirectories(tmp.resolve("clean"));
        Files.writeString(clean.resolve("threads.dump"), String.join("\n",
                Fixtures.source("jdk17-healthy.jstack").lines()), StandardCharsets.UTF_8);
        assertEquals(0, Cli.run(new String[]{"analyze", clean.toString(), "--no-narrative", "-f", "json",
                "-o", tmp.resolve("clean.json").toString()}));
        assertNotNull(MAPPER.readTree(tmp.resolve("clean.json").toFile()).get("findings"));

        Path junk = Files.createDirectories(tmp.resolve("junk"));
        Files.writeString(junk.resolve("notes.txt"), "meeting notes, nothing here\n", StandardCharsets.UTF_8);
        assertEquals(2, Cli.run(new String[]{"analyze", junk.toString()}));
        assertEquals(2, Cli.run(new String[]{"analyze", tmp.resolve("nope").toString()}));
        assertEquals(0, Cli.run(new String[]{"rules"}));
        assertEquals(0, Cli.run(new String[]{"explain", "GCA003"}));
        assertEquals(2, Cli.run(new String[]{"explain", "NOPE"}));
        assertEquals(0, Cli.run(new String[]{"doctor"}));
    }

    @Test
    @DisplayName("an input that was read off disk but not analysed is named in both reports")
    void partialWindowsAreDisclosed(@TempDir Path tmp) throws Exception {
        // Rotated -Xlog output (gc.log plus gc.log.0) is the normal shape of a GC log on a server, and a
        // snapshot carries one GC log. Halving the window silently is how a partial report becomes a
        // confident one; keeping the *second* file silently would be worse, since that is the one the
        // reader never saw mentioned. So: keep the first, name the loss, in Markdown and in JSON.
        Path dir = Files.createDirectories(tmp.resolve("rotated"));
        String storm = String.join("\n", Fixtures.source("gc-jdk17-storm.log").lines());
        Files.writeString(dir.resolve("gc.log"), storm, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("gc.log.0"), storm, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("gc.log.1"), storm, StandardCharsets.UTF_8);
        Path out = Files.createDirectories(tmp.resolve("rotated-out"));
        Cli.run(new String[]{"analyze", dir.toString(), "--no-narrative", "-f", "both", "-o", out.toString()});
        String md = Files.readString(out.resolve("report.md"), StandardCharsets.UTF_8);
        var json = MAPPER.readTree(out.resolve("report.json").toFile());
        assertTrue(md.contains("gc.log.0") && md.contains("not analysed"),
                "the report must name the file it could not use:\n" + md.lines()
                        .filter(l -> l.contains("gc.log")).reduce((a, b) -> a + "\n" + b).orElse("<none>"));
        assertEquals(1, md.lines().filter(l -> l.contains("read off disk and not analysed")).count(),
                "two rotations are one fact, so they get one bullet rather than two screens of it");
        assertEquals("gc.log", json.path("snapshot").path("gcLog").path("file").asText(),
                "the suffix-less rotation is the live window and the one that has to survive");
        var drop = json.path("ignoredInputs").get(0);
        assertEquals("gc.log", drop.path("kept").asText(),
                "the JSON has to say which window the findings describe, not only that something is missing");
        assertEquals("gc.log.0", drop.path("notAnalysed").get(0).asText());
        assertEquals("gc.log.1", drop.path("notAnalysed").get(1).asText());
        assertTrue(drop.path("note").asText().contains("not analysed"),
                "and carry the same sentence the human report shows");

        // Two logs passed as two paths is the same trap, reached a different way.
        Path two = Files.createDirectories(tmp.resolve("two"));
        Files.writeString(two.resolve("a.log"), storm, StandardCharsets.UTF_8);
        Files.writeString(two.resolve("b.log"), storm, StandardCharsets.UTF_8);
        Path twoOut = Files.createDirectories(tmp.resolve("two-out"));
        Cli.run(new String[]{"analyze", two.resolve("a.log").toString(), two.resolve("b.log").toString(),
                "--no-narrative", "-f", "both", "-o", twoOut.toString()});
        String md2 = Files.readString(twoOut.resolve("report.md"), StandardCharsets.UTF_8);
        assertTrue(md2.contains("b.log") && md2.contains("not analysed"),
                "a second GC log passed as its own path has to be disclosed too");
        assertEquals("a.log", MAPPER.readTree(twoOut.resolve("report.json").toFile())
                .path("snapshot").path("gcLog").path("file").asText(),
                "and the file passed first is the one the findings describe");

        // A heap histogram is one instant; two of them cannot both be "the" heap.
        Path his = Files.createDirectories(tmp.resolve("histos"));
        String histo = String.join("\n", Fixtures.source("histo-jdk17.histo").lines());
        Files.writeString(his.resolve("heap.histo"), histo, StandardCharsets.UTF_8);
        Files.writeString(his.resolve("heap2.histo"), histo, StandardCharsets.UTF_8);
        Path hisOut = Files.createDirectories(tmp.resolve("histo-out"));
        Cli.run(new String[]{"analyze", his.toString(), "--no-narrative", "-f", "both", "-o", hisOut.toString()});
        assertTrue(Files.readString(hisOut.resolve("report.md"), StandardCharsets.UTF_8).contains("heap2.histo"),
                "the dropped histogram has to be named as well");
    }

    @Test
    @DisplayName("CLI: an output target is honoured in full, or the run is a usage error")
    void outputTargetsAreNotHalfHonoured(@TempDir Path tmp) throws Exception {
        Path incident = Files.createDirectories(tmp.resolve("one"));
        Files.writeString(incident.resolve("threads.dump"), String.join("\n",
                Fixtures.source("jdk17-deadlock.jstack").lines()), StandardCharsets.UTF_8);

        // The README's own shape: a directory that does not exist yet, named with a trailing separator.
        // java.nio.Path drops that separator on Windows, which used to turn this request into "write
        // the Markdown into a file called results" — and silently discard the JSON half.
        for (String sep : new String[]{"/", "\\"}) {
            Path fresh = tmp.resolve("results" + (sep.equals("/") ? "Slash" : "Back"));
            assertEquals(0, Cli.run(new String[]{"analyze", incident.toString(), "--no-narrative",
                    "--fail-on", "never", "-f", "both", "-o", fresh + sep}), "the directory is creatable");
            assertTrue(Files.isRegularFile(fresh.resolve("report.md")), "markdown arrives via " + sep);
            assertTrue(Files.isRegularFile(fresh.resolve("report.json")), "and so does json, via " + sep);
        }

        Path file = tmp.resolve("both.md");
        assertEquals(2, Cli.run(new String[]{"analyze", incident.toString(), "--no-narrative", "-f", "both",
                "-o", file.toString()}),
                "two formats cannot fit one file name: that is a usage error, not a half report");
        assertFalse(Files.exists(file), "and the run has to stop before writing the half it could fit");

        assertEquals(2, Cli.run(new String[]{"analyze", incident.toString(), "--no-narrative", "-f", "yaml",
                "-o", tmp.resolve("nope.md").toString()}),
                "an unknown -f used to match neither writer, print nothing, and exit as if the run were clean");
        assertFalse(Files.exists(tmp.resolve("nope.md")));
    }

    @Test
    @DisplayName("no rule documents a flag that does not exist, and no knob is published that moves nothing")
    void knobsAreReachable() {
        java.util.Set<String> flags = new java.util.LinkedHashSet<>();
        for (picocli.CommandLine.Model.OptionSpec o : Cli.command()
                .getSubcommands().get("analyze").getCommandSpec().options()) {
            for (String n : o.names()) {
                if (n.startsWith("--")) {
                    flags.add(n.substring(2));
                }
            }
        }
        assertTrue(flags.size() > 20, "the analyze command should expose its tuning, saw " + flags.size());

        List<String> documentedButMissing = new ArrayList<>();
        for (dev.jingyu.jia.analyze.Rule r : dev.jingyu.jia.analyze.Rules.all()) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("--([a-z][a-z0-9-]+)").matcher(r.doc());
            while (m.find()) {
                if (!flags.contains(m.group(1))) {
                    documentedButMissing.add(r.id() + " documents --" + m.group(1));
                }
            }
        }
        assertTrue(documentedButMissing.isEmpty(),
                () -> "rule docs promise flags nobody implemented: " + documentedButMissing);

        // The report publishes Config.asMap() under `thresholds`. A key there that no flag can move
        // is a number the reader is meant to trust but cannot re-run — which is how cpu-hot-ratio
        // sat in Config for three releases reading like a knob and controlling nothing.
        List<String> unreachable = new ArrayList<>();
        java.util.Set<String> shapesOutput = java.util.Set.of("max-evidence", "cpu-hot-top-n");
        Config.defaults().asMap().forEach((k, v) -> {
            if (!flags.contains(k) && !shapesOutput.contains(k)) {
                unreachable.add(k + "=" + v);
            }
        });
        assertTrue(unreachable.isEmpty(),
                () -> "thresholds the JSON advertises that no flag controls: " + unreachable);
    }

    @Test
    @DisplayName("every threshold flag is accepted and reaches the JSON report")
    void thresholdFlagsAreWired(@TempDir Path tmp) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("snap"));
        Files.writeString(dir.resolve("threads.dump"), String.join("\n",
                Fixtures.source("jdk17-healthy.jstack").lines()), StandardCharsets.UTF_8);
        Path out = tmp.resolve("r.json");
        int code = Cli.run(new String[]{"analyze", dir.toString(), "--no-narrative", "-f", "json",
                "-o", out.toString(), "--mat-share", "0.09", "--mat-min-mb", "4", "--stall-min", "1",
                "--burst-bucket-sec", "30", "--burst-min", "3", "--pool-max-size", "50",
                "--cpu-min-cores", "0.2", "--container-min-instances", "1000",
                "--leak-pinned-share", "0.5", "--leak-stagnant-share", "0.05",
                "--leak-stalled-floor", "0.2", "--histo-top-min-bag-mb", "8",
                "--histo-top-min-leader-mb", "4", "--cpu-min-elapsed-sec", "0.5"});
        assertEquals(0, code, "the flags must parse and the clean dump must stay silent");
        JsonNode thresholds = MAPPER.readTree(out.toFile()).get("thresholds");
        assertNotNull(thresholds, "the report should say which thresholds were in force");
        assertEquals("0.09", thresholds.get("mat-share").asText());
        assertEquals("4", thresholds.get("mat-min-mb").asText());
        assertEquals("30", thresholds.get("burst-bucket-sec").asText());
    }

    @Test
    @DisplayName("the version the tool prints is the version the pom builds")
    void versionHasOneSource() throws Exception {
        String pom = Files.readString(Path.of("pom.xml"), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("<version>\\s*(\\d[^<\\s]*)\\s*</version>").matcher(pom);
        assertTrue(m.find(), "no project <version> in pom.xml");
        assertNotEquals("dev", Engine.VERSION, "filtered jia.properties never reached the classpath");
        assertEquals(m.group(1), Engine.VERSION,
                "the tool introduces itself as " + Engine.VERSION + " while the pom builds " + m.group(1));
    }

    @Test
    @DisplayName("--out with a trailing slash writes both report files")
    void writesBothFormats(@TempDir Path tmp) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("incident"));
        Files.writeString(dir.resolve("threads.dump"), String.join("\n",
                Fixtures.source("jdk17-deadlock.jstack").lines()), StandardCharsets.UTF_8);
        Path out = Files.createDirectories(tmp.resolve("out"));
        assertEquals(1, Cli.run(new String[]{"analyze", dir.toString(), "--format", "both",
                "-o", out.toString() + "/"}));
        assertTrue(Files.isRegularFile(out.resolve("report.md")));
        assertTrue(Files.isRegularFile(out.resolve("report.json")));
    }

    private static JsonNode readJson(String body) {
        try {
            return MAPPER.readTree(body);
        } catch (Exception e) {
            throw new AssertionError("output was not valid JSON: " + e + "\n" + body.substring(0,
                    Math.min(400, body.length())), e);
        }
    }
}
