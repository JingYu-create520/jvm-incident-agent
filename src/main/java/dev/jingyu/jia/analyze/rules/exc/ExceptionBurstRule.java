package dev.jingyu.jia.analyze.rules.exc;

import dev.jingyu.jia.analyze.Rule;
import dev.jingyu.jia.model.ArtifactKind;
import dev.jingyu.jia.model.Config;
import dev.jingyu.jia.model.Evidence;
import dev.jingyu.jia.model.ExceptionCluster;
import dev.jingyu.jia.model.ExceptionOccurrence;
import dev.jingyu.jia.model.Finding;
import dev.jingyu.jia.model.Severity;
import dev.jingyu.jia.model.Snapshot;
import dev.jingyu.jia.parse.Epochs;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * EXC003 — time clustering. An exception type that appears once a minute is background
 * noise; one that appears four hundred times in ten seconds is the moment something broke,
 * and it is the timestamp the rest of the analysis should be aligned to.
 */
public final class ExceptionBurstRule implements Rule {

    private static final long BUCKET_MILLIS = 60_000L;
    private static final int MIN_BURST = 10;

    @Override
    public String id() {
        return "EXC003";
    }

    @Override
    public String title() {
        return "Exception burst in time";
    }

    @Override
    public ArtifactKind artifact() {
        return ArtifactKind.APP_LOG;
    }

    @Override
    public List<Finding> evaluate(Snapshot snapshot, Config config) {
        List<ExceptionOccurrence> all = snapshot.exceptions();
        long timed = all.stream().filter(o -> o.epochMillis() != null).count();
        if (timed < MIN_BURST) {
            return List.of();
        }
        List<Finding> out = new ArrayList<>();
        int reported = 0;
        for (ExceptionCluster c : ExceptionCluster.group(all, ExceptionClusterRule.DEPTH).values().stream()
                .map(g -> new ExceptionCluster(g.get(0).fingerprint(ExceptionClusterRule.DEPTH), g))
                .toList()) {
            if (reported >= 2) {
                break;
            }
            Optional<ExceptionCluster.Burst> burst = c.burst(BUCKET_MILLIS);
            if (burst.isEmpty() || burst.get().count() < MIN_BURST) {
                continue;
            }
            reported++;
            ExceptionOccurrence rep = c.representative();
            out.add(Finding.builder(id())
                    .title(title())
                    .artifact(artifact())
                    .severity(burst.get().count() >= 100 ? Severity.HIGH : Severity.MEDIUM)
                    .confidence(0.75)
                    .summary(rep.rootCause().className() + " arrives " + burst.get().count()
                            + " times inside a single minute (total " + c.count() + " in this log), starting "
                            + Epochs.format(burst.get().bucketStartMillis(), java.time.ZoneId.systemDefault())
                            + ". Use that minute as the start of the incident: whatever changed at that "
                            + "time is the candidate cause.")
                    .evidence(c.items().stream()
                            .filter(o -> o.epochMillis() != null
                                    && o.epochMillis() >= burst.get().bucketStartMillis()
                                    && o.epochMillis() < burst.get().bucketStartMillis() + BUCKET_MILLIS)
                            .limit(config.maxEvidencePerFinding())
                            .map(o -> Evidence.of(o.source(), o.startLine(),
                                    o.timestampRaw() == null ? "in burst" : o.timestampRaw()))
                            .toList())
                    .recommend("Line up the GC log and any deploy, config push or traffic spike against this "
                            + "minute rather than against the end of the log.")
                    .metric("burstInWindow", burst.get().count())
                    .metric("windowStartMillis", burst.get().bucketStartMillis())
                    .metric("total", c.count())
                    .metric("rootClass", rep.rootCause().className())
                    .metric("file", rep.source().name())
                    .build());
        }
        return out;
    }

    @Override
    public String doc() {
        return """
                # EXC003 — Exception burst

                **What it looks for.** Occurrences of one stack fingerprint are bucketed into one-minute
                windows by their parsed log timestamp. Ten or more inside a single window fires; the finding
                reports when the window starts.

                **Why it is trustworthy.** Rate needs time, and time only exists if the log lines carry
                stamps — this rule returns nothing at all when fewer than ten occurrences have parseable
                timestamps, instead of guessing. The reported minute is a real bucket boundary, not a mean.

                **Evidence.** The occurrences inside the burst window, annotated with their own timestamps.

                **False positives.** A log that repeats one stack during a restart loop shows a burst per
                restart. The count next to each line makes the pattern visible.
                """;
    }
}
