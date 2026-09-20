package dev.jingyu.jia.analyze.rules.gca;

import dev.jingyu.jia.analyze.Rule;
import dev.jingyu.jia.model.ArtifactKind;
import dev.jingyu.jia.model.Config;
import dev.jingyu.jia.model.Evidence;
import dev.jingyu.jia.model.Finding;
import dev.jingyu.jia.model.GcEvent;
import dev.jingyu.jia.model.GcLog;
import dev.jingyu.jia.model.Severity;
import dev.jingyu.jia.model.Snapshot;

import java.util.List;
import java.util.Locale;

/**
 * GCA006 — GC throughput: the single number that says "this JVM spent too much of its
 * life collecting".
 */
public final class GcThroughputRule implements Rule {

    @Override
    public String id() {
        return "GCA006";
    }

    @Override
    public String title() {
        return "GC throughput below target";
    }

    @Override
    public ArtifactKind artifact() {
        return ArtifactKind.GC_LOG;
    }

    @Override
    public List<Finding> evaluate(Snapshot snapshot, Config config) {
        GcLog log = snapshot.gcLog().orElse(null);
        if (log == null || log.events().size() < 10 || log.durationSec() < 10) {
            return List.of();
        }
        double throughput = log.throughput();
        if (throughput >= config.throughputFloor()) {
            return List.of();
        }
        double lostPct = (1 - throughput) * 100;
        List<GcEvent> worst = log.events().stream()
                .filter(e -> e.pauseMs() != null)
                .sorted((a, b) -> Double.compare(b.pauseMs(), a.pauseMs()))
                .limit(config.maxEvidencePerFinding())
                .toList();
        return List.of(Finding.builder(id())
                .title(title())
                .artifact(artifact())
                .severity(lostPct > 20 ? Severity.CRITICAL : (lostPct > 10 ? Severity.HIGH : Severity.MEDIUM))
                .confidence(0.8)
                .summary("Over " + String.format(Locale.ROOT, "%.0f", log.durationSec()) + " s of log, "
                        + String.format(Locale.ROOT, "%.1f", lostPct) + "% of wall time was spent in "
                        + "stop-the-world pauses (" + (int) log.pauseSumMs() + " ms across "
                        + log.pauses().size() + " pauses). Target is "
                        + String.format(Locale.ROOT, "%.0f", (1 - config.throughputFloor()) * 100) + "% or less.")
                .evidence(worst.stream()
                        .map(e -> Evidence.of(log.source(), e.line(),
                                e.kind() + " " + String.format(Locale.ROOT, "%.0f", e.pauseMs()) + " ms at +"
                                        + String.format(Locale.ROOT, "%.3f", e.atSec()) + "s"))
                        .toList())
                .recommend("Throughput is a budget, not a tuning goal: it is spent by allocation rate times "
                        + "the cost of copying. Either allocate less (GCA004) or give the collector more room "
                        + "to work in.")
                .metric("file", log.source().name())
                .metric("throughput", String.format(Locale.ROOT, "%.3f", throughput))
                .metric("pauseSumMs", (int) log.pauseSumMs())
                .metric("spanSec", String.format(Locale.ROOT, "%.0f", log.durationSec()))
                .build());
    }

    @Override
    public String doc() {
        return """
                # GCA006 — GC throughput

                **What it looks for.** Sum of all stop-the-world pauses divided by the wall-clock span of
                the log. Fires below 97% (tune with `--throughput`), and needs at least ten collections
                over ten seconds so a short excerpt cannot trip it.

                **Why it is trustworthy.** It is arithmetic over the same pause numbers GCA002 reports,
                and both the sum and the span are printed in the finding. The known limit is honest: a log
                with only the pauses visible to `-Xlog:gc*` cannot count safepoint work that is not a GC
                pause, so the real figure can be worse than this, never better.

                **Evidence.** The biggest contributing pauses with their share of the total.

                **False positives.** A log covering a mostly idle period with two long pauses at the start
                (class loading, JIT) divides by a small denominator. Minimum event and span counts handle
                the common case; for a five-line excerpt the tool stays quiet anyway.
                """;
    }
}
