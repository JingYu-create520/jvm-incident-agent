package dev.jingyu.jia.analyze;

import dev.jingyu.jia.Fixtures;
import dev.jingyu.jia.llm.MockProvider;
import dev.jingyu.jia.llm.Provider;
import dev.jingyu.jia.model.Config;
import dev.jingyu.jia.model.Finding;
import dev.jingyu.jia.model.GcLog;
import dev.jingyu.jia.model.Histo;
import dev.jingyu.jia.model.Severity;
import dev.jingyu.jia.model.Snapshot;
import dev.jingyu.jia.model.TextSource;
import dev.jingyu.jia.parse.GcLogParser;
import dev.jingyu.jia.parse.HistoParser;
import dev.jingyu.jia.parse.SnapshotLoader;
import dev.jingyu.jia.parse.StackParser;
import dev.jingyu.jia.parse.TextFiles;
import dev.jingyu.jia.parse.ThreadDumpParser;
import dev.jingyu.jia.report.JsonReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("engine")
class EngineTest {

    private static final Config CFG = Config.defaults();

    /** A snapshot that looks like a working service: busy, boring, and healthy. */
    private static Snapshot healthy() {
        Snapshot.Builder b = Snapshot.builder();
        b.addDump(ThreadDumpParser.parse(Fixtures.source("jdk17-healthy.jstack")).value().get(0));
        String calm = Fixtures.gcUnified(
                List.of(new double[]{1, 60, 20, 5}, new double[]{4, 70, 22, 6}, new double[]{7, 66, 21, 4},
                        new double[]{10, 72, 23, 7}, new double[]{13, 68, 22, 5}),
                List.of(), null);
        b.gcLog(GcLogParser.parse(TextSource.of("gc.log",
                TextFiles.splitLines(calm, java.nio.charset.StandardCharsets.UTF_8))).value());
        Histo histo = HistoParser.parse(Fixtures.source("histo-jdk17.histo")).value();
        b.histo(histo);
        StackParser.parse(Fixtures.source("app-healthy.log")).value().forEach(b::addException);
        return b.build();
    }

    @Test
    @DisplayName("the hard gate: a healthy snapshot produces zero findings")
    void healthySnapshotIsSilent() {
        AnalysisResult r = new Engine().analyze(healthy(), CFG);
        assertTrue(r.findings().isEmpty(), () -> "false positives: " + r.findings());
        assertTrue(r.hypotheses().isEmpty());
        assertFalse(r.hasHighRisk());
        assertEquals(0L, r.countAtLeast(Severity.INFO));
    }

    @Test
    @DisplayName("every rule runs rather than being silently skipped")
    void allRulesExecute() {
        AnalysisResult r = new Engine().analyze(healthy(), CFG);
        assertEquals(Rules.all().size(), r.ruleStatus().size());
        r.ruleStatus().forEach((id, status) -> assertNotEquals("error", status, id + " threw"));
        assertTrue(r.ruleStatus().values().stream().allMatch(v -> v.equals("clean")
                || v.equals("not-applicable") || v.startsWith("declined: ") || v.endsWith("finding(s)")),
                () -> "a status outside the four meanings: " + r.ruleStatus());
    }

    @Test
    @DisplayName("a log too thin to measure says so instead of looking clean")
    void thinLogsAreNotAllClears() {
        // One 305 ms young pause in a gc.log someone truncated. The pause is a finding on its own --
        // GCA002 used to require three events and dropped it -- and the rules that cannot measure a
        // rate, a series or a percentage from a single event have to record that they abstained,
        // because "clean" is the word a reader takes home from a report with no findings.
        String text = Fixtures.gcUnified(List.of(new double[]{10, 200, 190, 305}), List.of(), null);
        Snapshot thin = Snapshot.builder().gcLog(GcLogParser.parse(TextSource.of("gc.log",
                TextFiles.splitLines(text, java.nio.charset.StandardCharsets.UTF_8))).value()).build();
        AnalysisResult r = new Engine().analyze(thin, CFG);

        assertTrue(r.findings().stream().anyMatch(f -> f.ruleId().equals("GCA002")
                        && f.severity() == Severity.MEDIUM),
                "a single 305 ms pause against a 200 ms SLA must be reported: " + r.findings());
        assertTrue(r.findings().stream().filter(f -> f.ruleId().equals("GCA002")).findFirst().orElseThrow()
                        .summary().contains("capped at MEDIUM"),
                "and the finding has to admit it is not a distribution");
        for (String id : List.of("GCA001", "GCA003", "GCA004", "GCA006")) {
            String status = r.ruleStatus().get(id);
            assertTrue(status != null && status.startsWith("declined: "),
                    id + " could not measure, so it may not be recorded as " + status);
        }
        assertTrue(r.ruleStatus().get("GCA006").contains("1 event"),
                () -> "the reason has to quote the data it saw: " + r.ruleStatus().get("GCA006"));
    }

    @Test
    @DisplayName("one thread dump unlocks the size half of TDA003 but not the growth half")
    void singleDumpDisclosesTheMissingSecond() {
        Snapshot one = Snapshot.builder()
                .addDump(ThreadDumpParser.parse(Fixtures.source("jdk17-healthy.jstack")).value().get(0))
                .build();
        AnalysisResult r = new Engine().analyze(one, CFG);
        assertTrue(r.ruleStatus().get("TDA003").startsWith("declined: "),
                () -> "a growth rule with one sample must not read as clean: " + r.ruleStatus().get("TDA003"));
        assertTrue(r.ruleStatus().get("TDA003").contains("second dump"),
                "and it should name the fix, which is one more capture 5-10 s apart");
    }

    @Test
    @DisplayName("report bytes do not depend on which JVM ran them")
    void jsonKeyOrderIsNotLeftToTheJvm() throws Exception {
        // ruleStatus was a Map.copyOf(), whose iteration order the JDK documents as unpredictable and
        // allowed to vary between runs -- and it did: the same input on the published 0.3.3 jar produced
        // three different MD5s in three JVM launches, while the README promised identical output for
        // identical input. The map is sorted now, and the assertion pins the serialised order rather
        // than trusting the container, because a per-JVM salt cannot be re-rolled inside one test run.
        String js = new JsonReport().render(new Engine().analyze(healthy(), CFG), null);
        com.fasterxml.jackson.databind.JsonNode node =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(js);
        List<String> keys = new java.util.ArrayList<>();
        node.path("ruleStatus").fieldNames().forEachRemaining(keys::add);
        assertEquals(Rules.all().size(), keys.size(), "every rule is in the status map");
        List<String> sorted = keys.stream().sorted().toList();
        assertEquals(sorted, keys, "JSON keys shipped to a diffing reader have to be in a fixed order");
    }

    @Test
    @DisplayName("--llm cannot change the findings: the lock that makes this tool trustworthy")
    void narrativeCannotMoveFindings() {
        Snapshot snap = snapshot("incident-deadlock");
        Engine engine = new Engine();
        AnalysisResult plain = engine.analyze(snap, CFG);
        List<Finding> before = plain.findings();

        Provider blabber = new Provider() {
            @Override
            public String name() {
                return "test";
            }

            @Override
            public String narrate(AnalysisResult result) {
                return "THERE IS NO DEADLOCK, EVERYTHING IS FINE, RESTART THE POD";
            }

            @Override
            public boolean remote() {
                return true;
            }
        };
        String narrative = blabber.narrate(plain);
        List<Finding> after = plain.findings();

        assertEquals(before, after, "narration mutated the finding list");
        assertEquals(before.size(), after.size());
        for (int i = 0; i < before.size(); i++) {
            assertEquals(before.get(i).ruleId(), after.get(i).ruleId());
            assertEquals(before.get(i).severity(), after.get(i).severity());
            assertEquals(before.get(i).summary(), after.get(i).summary());
            assertEquals(before.get(i).evidence(), after.get(i).evidence());
        }
        // And the lie in the narrative cannot leak into the findings section of the report.
        String report = new dev.jingyu.jia.report.MarkdownReport().render(plain, narrative);
        assertTrue(report.contains("TDA001"), "the rule finding must survive a contradicting narrative");
        assertTrue(report.contains("Deadlock"));
    }

    @Test
    @DisplayName("the offline narrator is deterministic")
    void mockNarrativeIsStable() {
        Snapshot snap = snapshot("incident-gc-storm");
        AnalysisResult r = new Engine().analyze(snap, CFG);
        Provider p = new MockProvider();
        assertEquals(p.narrate(r), p.narrate(r));
        assertEquals(p.narrate(r), new MockProvider().narrate(r), "a fresh provider must say the same thing");
    }

    @Test
    @DisplayName("the same problem in two dumps becomes one finding that names both files")
    void duplicatesMergeAcrossDumps() {
        TextSource original = Fixtures.source("jdk17-deadlock.jstack");
        var first = ThreadDumpParser.parse(original).value().get(0);
        var second = ThreadDumpParser.parse(TextSource.of("threads-2.dump", original.lines())).value().get(0);
        Snapshot twoDumps = Snapshot.builder().addDump(first).addDump(second).build();
        AnalysisResult r = new Engine().analyze(twoDumps, CFG);
        List<Finding> deadlocks = r.findings().stream().filter(f -> f.ruleId().equals("TDA001")).toList();
        assertEquals(1, deadlocks.size(), "two captures of one deadlock are one finding");
        assertEquals(List.of("jdk17-deadlock.jstack", "threads-2.dump"),
                deadlocks.get(0).metrics().get("seenIn"));
    }

    @Test
    @DisplayName("the ranking is deterministic and severity-first")
    void orderingIsStable() {
        Snapshot snap = snapshot("incident-deadlock");
        AnalysisResult a = new Engine().analyze(snap, CFG);
        AnalysisResult b = new Engine().analyze(snap, CFG);
        assertEquals(a.findings().stream().map(Finding::ruleId).toList(),
                b.findings().stream().map(Finding::ruleId).toList());
        assertEquals(a.hypotheses().stream().map(Hypothesis::id).toList(),
                b.hypotheses().stream().map(Hypothesis::id).toList());
        List<Severity> severities = a.findings().stream().map(Finding::severity).toList();
        for (int i = 1; i < severities.size(); i++) {
            assertTrue(severities.get(i - 1).atLeast(severities.get(i)),
                    "findings must be sorted by severity, got " + severities);
        }
    }

    @Test
    @DisplayName("thresholds are real knobs: tightening the SLA surfaces the pause finding")
    void thresholdsTakeEffect() {
        Snapshot storm = snapshot("incident-gc-storm");
        assertTrue(new Engine().analyze(storm, Config.defaults()).findings().stream()
                .noneMatch(f -> f.ruleId().equals("GCA002")), "200 ms SLA is beyond this JVM's pauses");
        var tight = new Engine().analyze(storm, Config.builder().slaPauseMs(2).build());
        assertTrue(tight.findings().stream().anyMatch(f -> f.ruleId().equals("GCA002")),
                "a 2 ms SLA must report the p99 it was given");
    }

    @Test
    @DisplayName("a snapshot of unparsable files degrades to INFO instead of failing")
    void degradedInput() {
        Snapshot junk = Snapshot.builder()
                .addUnparsed(new Snapshot.Unparsed("mystery.bin", "unrecognised artifact type")).build();
        AnalysisResult r = new Engine().analyze(junk, CFG);
        assertTrue(r.findings().isEmpty());
        assertFalse(r.parseNotes().isEmpty());
        assertEquals(0, r.hypotheses().size());
    }

    private static Snapshot snapshot(String corpusDir) {
        var dir = java.nio.file.Path.of("corpus", corpusDir);
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isDirectory(dir),
                () -> dir + " is not present in this checkout");
        return SnapshotLoader.load(dir).value();
    }
}
