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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * GCA002 — pauses over the stated SLA, reported as a distribution rather than one number.
 */
public final class LongPauseRule implements Rule {

    @Override
    public String id() {
        return "GCA002";
    }

    @Override
    public String title() {
        return "GC pause over SLA";
    }

    @Override
    public ArtifactKind artifact() {
        return ArtifactKind.GC_LOG;
    }

    @Override
    public List<Finding> evaluate(Snapshot snapshot, Config config) {
        GcLog log = snapshot.gcLog().orElse(null);
        if (log == null || log.events().size() < 3) {
            return List.of();
        }
        List<GcEvent> over = log.events().stream()
                .filter(e -> e.pauseMs() != null && e.pauseMs() > config.slaPauseMs())
                .sorted(Comparator.comparingDouble((GcEvent e) -> e.pauseMs()).reversed())
                .toList();
        if (over.isEmpty()) {
            return List.of();
        }
        double p50 = log.pausePercentile(50);
        double p95 = log.pausePercentile(95);
        double p99 = log.pausePercentile(99);
        Severity severity = p99 > config.slaPauseMs() * 5 || over.get(0).pauseMs() > 2000
                ? Severity.CRITICAL
                : (p99 > config.slaPauseMs() * 2 ? Severity.HIGH : Severity.MEDIUM);

        List<String> offenders = new ArrayList<>();
        for (GcEvent e : over.subList(0, Math.min(over.size(), 5))) {
            offenders.add(e.kind() + " " + String.format(Locale.ROOT, "%.0f", e.pauseMs()) + "ms at +"
                    + String.format(Locale.ROOT, "%.3f", e.atSec()) + "s");
        }
        return List.of(Finding.builder(id())
                .title(title())
                .artifact(artifact())
                .severity(severity)
                .confidence(Math.min(0.95, 0.6 + over.size() * 0.03))
                .summary(over.size() + " of " + log.pauses().size() + " pauses exceed the "
                        + config.slaPauseMs() + " ms SLA. p50 " + r(p50) + " ms, p95 " + r(p95)
                        + " ms, p99 " + r(p99) + " ms, worst " + r(over.get(0).pauseMs()) + " ms.")
                .evidence(over.subList(0, Math.min(over.size(), config.maxEvidencePerFinding())).stream()
                        .map(e -> Evidence.of(log.source(), e.line(),
                                e.kind() + (e.cause() == null ? "" : " (" + e.cause() + ")")))
                        .toList())
                .recommend("p99 rather than the maximum is what users feel repeatedly; fix the shape of the "
                        + "distribution, not only the single worst pause.")
                .recommend("Long young pauses usually mean a large live young set or reference processing — "
                        + "check GCA004 for premature promotion before resizing the heap.")
                .metric("file", log.source().name())
                .metric("slaMs", config.slaPauseMs())
                .metric("p50Ms", r(p50))
                .metric("p95Ms", r(p95))
                .metric("p99Ms", r(p99))
                .metric("maxMs", r(over.get(0).pauseMs()))
                .metric("offenders", List.copyOf(offenders))
                .build());
    }

    private static String r(double v) {
        return String.format(Locale.ROOT, "%.0f", v);
    }

    @Override
    public String doc() {
        return """
                # GCA002 — Pause over SLA

                **What it looks for.** Every stop-the-world pause is collected and ranked. The
                distribution (p50 / p95 / p99 / max) is compared with `--sla-ms` (default 200 ms), and
                severity follows how far p99 — not the maximum — sits over the line.

                **Why it is trustworthy.** A single 4 s pause in a six-hour log is a fluke; a p99 of
                400 ms is what every tenth request feels. Reporting both numbers is the point.

                **Evidence.** The five worst pause lines with their kind and cause.

                **False positives.** A log that includes startup and shutdown, or that was captured
                while the machine was swapping, shows long pauses that are not the app's fault. The
                uptime next to each event lets you see whether they cluster at boot.
                """;
    }
}
