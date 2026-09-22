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
import java.util.List;
import java.util.Locale;

/**
 * GCA001 — Full GC frequency: the most direct "the JVM is thrashing" signal.
 */
public final class FullGcFrequencyRule implements Rule {

    @Override
    public String id() {
        return "GCA001";
    }

    @Override
    public String title() {
        return "Full GC storm";
    }

    @Override
    public ArtifactKind artifact() {
        return ArtifactKind.GC_LOG;
    }

    @Override
    public List<Finding> evaluate(Snapshot snapshot, Config config) {
        GcLog log = snapshot.gcLog().orElse(null);
        if (log == null) {
            return List.of();
        }
        // A startup metaspace collection and a jmap-forced inspection are not heap pressure; a
        // rate rule cannot tell them from a storm, so the causes are filtered before the counting.
        List<GcEvent> full = log.majorCollections().stream()
                .filter(e -> !GcNoise.notHeapPressure(e, config.gcSettleSec()))
                .toList();
        if (full.size() < 2) {
            return List.of();
        }
        double window = config.fullGcWindowSec();
        int bestCount = 0;
        int bestFrom = 0;
        double bestStart = 0;
        for (int i = 0; i < full.size(); i++) {
            int count = 0;
            for (int j = i; j < full.size(); j++) {
                if (full.get(j).atSec() - full.get(i).atSec() <= window) {
                    count++;
                } else {
                    break;
                }
            }
            if (count > bestCount) {
                bestCount = count;
                bestFrom = i;
                bestStart = full.get(i).atSec();
            }
        }
        double span = Math.min(window, full.get(full.size() - 1).atSec() - bestStart);
        double minutes = Math.max(span / 60.0, Math.min(1.0, window / 60.0));
        double perMinute = bestCount / minutes;
        if (perMinute < config.fullGcPerMinute() || bestCount < 2) {
            return List.of();
        }
        List<GcEvent> burst = full.subList(bestFrom, Math.min(full.size(), bestFrom + bestCount));
        double worstPause = burst.stream().map(GcEvent::pauseMs).filter(java.util.Objects::nonNull)
                .mapToDouble(Double::doubleValue).max().orElse(0);
        long stopMs = (long) burst.stream().mapToDouble(e -> e.pauseMs() == null ? 0 : e.pauseMs()).sum();

        List<Finding> out = new ArrayList<>();
        out.add(Finding.builder(id())
                .title(title())
                .artifact(artifact())
                .severity(perMinute >= config.fullGcPerMinute() * 3 ? Severity.CRITICAL : Severity.HIGH)
                .confidence(Math.min(0.95, 0.7 + bestCount * 0.02))
                .summary(bestCount + " " + log.majorNoun() + " inside " + String.format(Locale.ROOT, "%.1f", minutes)
                        + " minute(s) (" + String.format(Locale.ROOT, "%.1f", perMinute)
                        + "/min, threshold " + config.fullGcPerMinute()
                        + "), stopping the world for " + stopMs + " ms in total, worst pause "
                        + String.format(Locale.ROOT, "%.0f", worstPause) + " ms.")
                .evidence(burst.stream().limit(config.maxEvidencePerFinding())
                        .map(e -> Evidence.of(log.source(), e.line(),
                                log.majorLabel() + " at +" + fmt(e.atSec()) + "s"
                                        + (e.cause() == null ? "" : ", cause " + e.cause())))
                        .toList())
                .recommend("A Full GC storm is always downstream of something: either the live set does not "
                        + "fit the heap (see GCA003) or garbage is being created faster than it can be "
                        + "collected (see GCA004).")
                .recommend("Short term: raise the heap or shed load. Do not add `-XX:+DisableExplicitGC` "
                        + "before checking whether something calls System.gc() on purpose.")
                .metric("file", log.source().name())
                .metric("majorCollections", full.size())
                .metric("burstCount", bestCount)
                .metric("perMinute", String.format(Locale.ROOT, "%.2f", perMinute))
                .metric("stopTheWorldMs", stopMs)
                .metric("collector", log.collector().name())
                .build());
        return out;
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }

    @Override
    public String doc() {
        return """
                # GCA001 — Full GC storm

                **What it looks for.** Full GC events are placed on the uptime axis and a sliding
                window (`--gc-window`, default 300 s) finds the densest burst. The burst rate is
                compared with `--full-gc-per-min` (default 1/min).

                **Why it is trustworthy.** Counting Full GCs over the whole log dilutes the burst that
                actually hurt: twenty collections spread over a day of warm-up is tuning noise, twenty
                inside five minutes is an outage. The window reports the burst, and the total is kept
                as a separate metric.

                **Evidence.** Each Full GC line in the densest window, annotated with uptime and cause.

                **False positives.** Full GCs caused by `Metadata GC Threshold` inside the first `--gc-settle-sec`
                (default 60 s of uptime) and ones an outside actor forced (`Heap Inspection Initiated GC` from
                `jmap -histo:live`, a heap dump) are dropped before the rate is computed: they are real collections
                and they say nothing about memory pressure. That filter is the reason a 78-line startup slice of a
                healthy Parallel GC service produces no findings; without it the same bytes came back as a storm.
                Nothing filters `GCLocker Initiated GC` -- that one is the JVM complaining, and GCA005 quotes it.

                A log covering less than one window at the very end of the JVM's
                life, or shutdown-time `System.gc()` calls, can look dense. The causes printed next to
                each event let a reader check: `Allocation Failure`/`Ergonomics` is pressure,
                `System.gc()` is somebody's code.
                """;
    }
}
