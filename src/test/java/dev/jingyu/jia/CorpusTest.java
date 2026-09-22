package dev.jingyu.jia;

import dev.jingyu.jia.analyze.AnalysisResult;
import dev.jingyu.jia.analyze.Engine;
import dev.jingyu.jia.analyze.Hypothesis;
import dev.jingyu.jia.model.Config;
import dev.jingyu.jia.model.Severity;
import dev.jingyu.jia.model.Snapshot;
import dev.jingyu.jia.parse.SnapshotLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The acceptance gate, run against {@code corpus/} — real jstack, jmap and GC output captured
 * from a live JVM running planted incidents. These are the assertions the design document sets:
 * the top-ranked hypothesis must name the planted truth, and the healthy capture must produce
 * nothing at all.
 *
 * <p>Skipped rather than failed when the corpus is absent, so a shallow clone still builds.
 */
@EnabledIf("dev.jingyu.jia.CorpusTest#corpusPresent")
@DisplayName("corpus acceptance")
class CorpusTest {

    static boolean corpusPresent() {
        return Files.isDirectory(Path.of("corpus", "healthy"));
    }

    /** scenario directory → the hypothesis that must come first. */
    @ParameterizedTest(name = "{0} ranks {1} first")
    @CsvSource({
            "incident-deadlock, H-DEADLOCK",
            "incident-heap-leak, H-HEAP-LEAK",
            "incident-gc-storm, H-ALLOCATION-STORM",
            "incident-thread-leak, H-THREAD-LEAK",
            "incident-exceptions, H-ERROR-DRIVER",
            "incident-zgc-leak, H-HEAP-LEAK"})
    void topHypothesisNamesTheTruth(String scenario, String expected) {
        AnalysisResult r = analyze(scenario);
        assertFalse(r.hypotheses().isEmpty(), () -> scenario + " produced no hypothesis; findings were "
                + r.findings());
        List<String> ranked = r.hypotheses().stream().map(Hypothesis::id).toList();
        assertEquals(expected, ranked.get(0), () -> "ranking was " + ranked + " with findings "
                + r.findings().stream().map(f -> f.ruleId() + "/" + f.severity()).toList());
    }

    /**
     * The exact rule set each scenario fires, pinned.
     *
     * <p>{@code TRUTH.md} documents a must-not-fire list per scenario, but the ranking check above
     * only looks at the top hypothesis — so a rule drifting into or out of a scenario stayed
     * invisible until somebody read the markdown. One scenario's list was already wrong that way
     * (TDA005 does fire on {@code incident-deadlock}: the deadlock starves the whole worker pool).
     * This is the assertion that keeps the documentation and the engine from separating again.
     */
    @ParameterizedTest(name = "{0} fires exactly [{1}]")
    @CsvSource({
            "incident-deadlock,    'TDA001 TDA002 TDA004 TDA005'",
            "incident-heap-leak,   'GCA001 GCA003 GCA004 HIS001'",
            "incident-gc-storm,    'GCA001 GCA004 GCA005 GCA006 HIS001'",
            "incident-thread-leak, 'TDA003 TDA005'",
            "incident-exceptions,  'EXC001 EXC002 EXC003'",
            "healthy,              ' '",
            "incident-zgc-leak,    'GCA001 GCA003 GCA007 HIS001'",
    })
    void firesExactlyTheDocumentedRules(String scenario, String expected) {
        List<String> fired = analyze(scenario).findings().stream()
                .map(f -> f.ruleId())
                .distinct()
                .sorted()
                .toList();
        List<String> want = expected.isBlank() ? List.of() : List.of(expected.trim().split("\\s+"));
        assertEquals(want, fired, () -> scenario + " actually fired " + fired);
    }

    @Test
    @DisplayName("the healthy capture yields zero findings — the hard gate")
    void healthyIsSilent() {
        AnalysisResult r = analyze("healthy");
        assertTrue(r.findings().isEmpty(), () -> "false positives on the healthy capture: "
                + r.findings().stream().map(f -> f.ruleId() + " " + f.summary()).toList());
        assertEquals(0L, r.countAtLeast(Severity.INFO));
    }

    @Test
    @DisplayName("every incident snapshot is parsed as all four artifacts")
    void inputsAreAllRecognised() {
        for (String scenario : List.of("incident-deadlock", "incident-heap-leak", "incident-gc-storm",
                "incident-thread-leak", "incident-exceptions", "incident-zgc-leak", "healthy")) {
            Snapshot s = load(scenario);
            assertEquals(2, s.threadDumps().size(), scenario + " should hold two dumps");
            assertTrue(s.gcLog().isPresent(), scenario + " has no GC log");
            assertTrue(s.histo().isPresent(), scenario + " has no histogram");
            assertFalse(s.exceptions().isEmpty() && scenario.startsWith("incident-exceptions"),
                    scenario + " has no stacks");
            // TRUTH.md is documentation, not an artifact; it must be reported as unread, not crash.
            assertTrue(s.unparsed().stream().anyMatch(u -> u.file().equals("TRUTH.md")),
                    scenario + " should list TRUTH.md as not-an-artifact");
        }
    }

    @Test
    @DisplayName("the heap-leak snapshot is separable from the allocation storm by the live set alone")
    void leakAndStormAreNotTheSameStory() {
        var leak = load("incident-heap-leak").gcLog().orElseThrow();
        var storm = load("incident-gc-storm").gcLog().orElseThrow();
        double leakFloor = leak.majorCollections().stream()
                .mapToDouble(e -> e.heapAfterMb() == null ? 0 : e.heapAfterMb()).average().orElse(0);
        double stormFloor = storm.majorCollections().stream()
                .mapToDouble(e -> e.heapAfterMb() == null ? 0 : e.heapAfterMb()).average().orElse(0);
        assertTrue(leakFloor > stormFloor,
                "the corpus should demonstrate the difference: post-GC live bytes " + leakFloor
                        + " vs " + stormFloor);
    }

    @Test
    @DisplayName("runtime is inside the budget for a 5 MB GC log")
    void fastEnough() {
        long start = System.nanoTime();
        AnalysisResult r = analyze("incident-gc-storm");
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertFalse(r.findings().isEmpty());
        assertTrue(ms < 5_000, () -> "analysed a multi-megabyte log in " + ms + " ms");
    }

    private static AnalysisResult analyze(String scenario) {
        return new Engine().analyze(load(scenario), Config.defaults());
    }

    private static Snapshot load(String scenario) {
        return SnapshotLoader.load(Path.of("corpus", scenario)).value();
    }
}
