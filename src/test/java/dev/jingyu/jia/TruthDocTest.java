package dev.jingyu.jia;

import dev.jingyu.jia.analyze.AnalysisResult;
import dev.jingyu.jia.analyze.Engine;
import dev.jingyu.jia.analyze.Rule;
import dev.jingyu.jia.analyze.Rules;
import dev.jingyu.jia.model.Config;
import dev.jingyu.jia.model.Snapshot;
import dev.jingyu.jia.parse.SnapshotLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks each {@code corpus/<scenario>/TRUTH.md} against what the engine actually reports.
 *
 * <p>{@link CorpusTest} pins the fired rule set per scenario, so a rule drifting into or out of a
 * scenario fails the build. That is only half of it: the same rule set is written out again, by
 * hand, in prose that no test used to read. Two of the six folders had already separated from the
 * engine — {@code incident-gc-storm} and {@code incident-thread-leak} each listed under "must NOT
 * fire" a rule that does fire — and three more quietly omitted rules that fire. A ground-truth
 * file that disagrees with the ground truth is worse than no ground-truth file, because it is what
 * a reader trusts when they tune a threshold.
 *
 * <p>So this test parses the prose. Two shapes are accepted, since the folders grew up
 * differently: bullet lists under {@code ## Rules that SHOULD fire} / {@code ## Rules that must
 * NOT fire}, where each bullet opens with the rule id(s) in bold; and a table under a heading
 * mentioning "rule by rule", whose second column reads either {@code nothing} or the finding.
 * Ranges are expanded ({@code GCA001-006} is all six), as are slash groups
 * ({@code HIS001/HIS002}).
 *
 * <p>Three assertions per scenario: the SHOULD list is exactly the fired set, the must-NOT list
 * is disjoint from it, and together they name every registered rule — so a new rule cannot be
 * added without someone deciding, in prose, what it should say about each capture.
 */
@EnabledIf("dev.jingyu.jia.CorpusTest#corpusPresent")
@DisplayName("TRUTH.md says what the engine says")
class TruthDocTest {

    private static final Pattern HEADING = Pattern.compile("^##\\s+(.*)$");
    private static final Pattern BOLD = Pattern.compile("\\*\\*([^*]+)\\*\\*");
    private static final Pattern TABLE_ROW =
            Pattern.compile("^\\|\\s*([^|]+?)\\s*\\|\\s*([^|]*?)\\s*\\|");
    /** An id, optionally followed by {@code -NNN} meaning "through this number". */
    private static final Pattern ID_OR_RANGE = Pattern.compile("\\b([A-Z]{3})(\\d{3})(?:\\s*-\\s*(\\d{1,3}))?\\b");
    private static final Pattern LOOKS_LIKE_ID = Pattern.compile("^[A-Z]{3}\\d{3}");

    private static final Map<String, String> SECTIONS = Map.of(
            "rules that should fire", "fires",
            "rules that must not fire", "silent",
            "rule by rule", "table");

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"healthy", "incident-deadlock", "incident-heap-leak", "incident-gc-storm",
            "incident-thread-leak", "incident-exceptions"})
    void groundTruthMatchesTheEngine(String scenario) {
        AnalysisResult result = new Engine().analyze(load(scenario), Config.defaults());
        List<String> fired = result.findings().stream().map(f -> f.ruleId()).distinct().sorted().toList();
        Set<String> all = new LinkedHashSet<>(Rules.all().stream().map(Rule::id).toList());

        Path truth = Path.of("corpus", scenario, "TRUTH.md");
        assertTrue(Files.isRegularFile(truth), scenario + " has no TRUTH.md to check");
        Claims claims = read(truth, scenario);

        assertEquals(fired, claims.fires, () -> scenario + ": TRUTH.md's SHOULD-fire list is "
                + claims.fires + " but the engine reported " + fired
                + " — fix the prose, do not move the threshold to match it");
        for (String id : claims.silent) {
            assertTrue(!fired.contains(id), () -> scenario + ": TRUTH.md lists " + id
                    + " under \"must NOT fire\", and the engine does fire it. Either the rule is"
                    + " wrong (then fix the rule and say so) or the file is (then move the id"
                    + " into the SHOULD list and explain why it belongs there)");
        }
        Set<String> covered = new LinkedHashSet<>(claims.fires);
        covered.addAll(claims.silent);
        assertEquals(all, covered, () -> scenario + ": TRUTH.md is silent about "
                + diff(all, covered) + " — every registered rule has to be accounted for, and"
                + " new ones are exactly what a stale ground-truth file hides");
    }

    @Test
    @DisplayName("no folder claims a rule that does not exist")
    void groundTruthOnlyNamesRealRules() {
        Set<String> all = new LinkedHashSet<>(Rules.all().stream().map(Rule::id).toList());
        List<String> bogus = new ArrayList<>();
        for (Path dir : listCorpus()) {
            Path truth = dir.resolve("TRUTH.md");
            if (!Files.isRegularFile(truth)) {
                continue;
            }
            for (String id : concat(read(truth, dir.getFileName().toString()))) {
                if (!all.contains(id)) {
                    bogus.add(dir.getFileName() + " -> " + id);
                }
            }
        }
        assertTrue(bogus.isEmpty(), () -> "TRUTH.md files name rules that were never registered: " + bogus);
    }

    // ------------------------------------------------------------------ parsing

    private record Claims(List<String> fires, List<String> silent) {
    }

    private static Claims read(Path truth, String scenario) {
        List<String> lines;
        try {
            lines = Files.readAllLines(truth);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + truth, e);
        }
        List<String> fires = new ArrayList<>();
        List<String> silent = new ArrayList<>();
        String mode = null;
        for (String line : lines) {
            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                mode = SECTIONS.get(heading.group(1).trim().toLowerCase(Locale.ROOT));
                if (mode == null && heading.group(1).toLowerCase(Locale.ROOT).contains("rule by rule")) {
                    mode = "table";
                }
                continue;
            }
            if (mode == null) {
                continue;
            }
            if ("table".equals(mode) || line.stripLeading().startsWith("|")) {
                Matcher row = TABLE_ROW.matcher(line);
                if (!row.find()) {
                    continue;
                }
                List<String> ids = expand(row.group(1));
                if (ids.isEmpty()) {
                    continue;
                }
                if (row.group(2).toLowerCase(Locale.ROOT).contains("nothing")) {
                    silent.addAll(ids);
                } else {
                    fires.addAll(ids);
                }
                continue;
            }
            if (!line.stripLeading().startsWith("-")) {
                continue;
            }
            // Only the leading bold run(s) count, so prose later in the bullet cannot smuggle in
            // an id that is being discussed rather than claimed.
            int dash = line.indexOf("—");
            String head = dash < 0 ? line : line.substring(0, dash);
            Matcher bold = BOLD.matcher(head);
            while (bold.find()) {
                String span = bold.group(1);
                if (LOOKS_LIKE_ID.matcher(span).find()) {
                    if ("fires".equals(mode)) {
                        fires.addAll(expand(span));
                    } else if ("silent".equals(mode)) {
                        silent.addAll(expand(span));
                    }
                }
            }
        }
        return new Claims(fires.stream().distinct().sorted().toList(),
                silent.stream().distinct().sorted().toList());
    }

    /** {@code GCA001-006}, {@code HIS001/HIS002} and plain ids, in any mixture. */
    private static List<String> expand(String text) {
        List<String> ids = new ArrayList<>();
        Matcher m = ID_OR_RANGE.matcher(text);
        while (m.find()) {
            String prefix = m.group(1);
            int from = Integer.parseInt(m.group(2));
            int to = m.group(3) == null ? from : Integer.parseInt(m.group(3));
            for (int n = from; n <= to; n++) {
                ids.add(String.format(Locale.ROOT, "%s%03d", prefix, n));
            }
        }
        return ids;
    }

    private static List<String> concat(Claims c) {
        List<String> all = new ArrayList<>(c.fires());
        all.addAll(c.silent());
        return all;
    }

    private static List<String> diff(Set<String> want, Set<String> have) {
        return want.stream().filter(id -> !have.contains(id)).toList();
    }

    private static List<Path> listCorpus() {
        try (var dirs = Files.list(Path.of("corpus"))) {
            return dirs.filter(Files::isDirectory).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Snapshot load(String scenario) {
        return SnapshotLoader.load(Path.of("corpus", scenario)).value();
    }
}
