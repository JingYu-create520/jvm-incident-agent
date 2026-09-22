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
        if (log == null || log.pauses().isEmpty()) {
            // A floor of three events used to sit here, which threw away the most common thin capture of
            // all: one or two pauses in a log someone truncated. A 305 ms stop against a 200 ms SLA is a
            // finding; what it is not is a distribution, so the severity below says so.
            return List.of();
        }
        List<GcEvent> over = log.events().stream()
                .filter(e -> e.pauseMs() != null && e.pauseMs() > config.slaPauseMs())
                .sorted(Comparator.comparingDouble((GcEvent e) -> e.pauseMs()).reversed())
                .toList();
        if (over.isEmpty()) {
            return List.of();
        }
        boolean thin = log.pauses().size() < 3;
        double p50 = log.pausePercentile(50);
        double p95 = log.pausePercentile(95);
        double p99 = log.pausePercentile(99);
        Severity severity = thin ? Severity.MEDIUM
                : p99 > config.slaPauseMs() * 5 || over.get(0).pauseMs() > 2000
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
                        + " ms, p99 " + r(p99) + " ms, worst " + r(over.get(0).pauseMs()) + " ms."
                        + (thin ? " With " + Rule.count(log.pauses().size(), "pause") + " in this window the "
                                + "percentiles are the maximum wearing a distribution's clothes, so the "
                                + "severity is capped at MEDIUM and the number to trust is the worst one."
                                : ""))
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

    @Override
    public String declined(Snapshot snapshot, Config config) {
        return snapshot.gcLog().filter(l -> l.pauses().isEmpty()).isPresent()
                ? "this GC log contains no stop-the-world pause at all, so there is nothing to compare "
                        + "against the SLA (a concurrent-only window, or a log cut before the first "
                        + "collection)"
                : "";
    }

    private static String r(double v) {
        return String.format(Locale.ROOT, "%.0f", v);
    }

    @Override
    public String doc() {
        return """
                # GCA002 — Pause over SLA
                
                Collect every stop-the-world pause, rank them, compare the distribution against `--sla-ms`
                (default 200 ms). p50, p95, p99 and the maximum all appear in the finding, because they answer
                different questions: the maximum is what shows up in an incident review, p99 is what every tenth
                request feels. Severity follows how far p99 sits over the line, not how bad the single worst pause
                was.

                Each duration is one collection's own stop-the-world time. On a JDK 7/8 traditional line that means
                the *last* `N secs` before `[Times: …]`, not the first: a ParNew line prints the young phase and
                then the collection, and a CMS Final Remark prints Rescan, reference processing and two scrubs
                before its total — taking the first turn made a 13.345 ms remark into a 9.102 ms one. ZGC
                `Allocation Stall` lines are not in this distribution either: one of those stops a thread, not the
                world (GCA007 reads them).
                
                Evidence is the five worst pause lines with their kind and cause.
                
                Wrong when: the log is dominated by startup or shutdown, where long pauses are not the
                application's fault — check the uptime printed next to each event before resizing anything.
                A window with one or two pauses is not a distribution: p50/p95/p99 collapse onto the
                maximum, so the finding says so and stays at MEDIUM however long the pause was. A log with
                no stop-the-world pause at all is reported as declined rather than clean, because
                "every rule ran clean" over a truncated capture would be a sentence about the file, not
                about the JVM.
                """;
    }
}
