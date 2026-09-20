package dev.jingyu.jia.analyze;

import dev.jingyu.jia.model.ArtifactKind;
import dev.jingyu.jia.model.Config;
import dev.jingyu.jia.model.ExceptionCluster;
import dev.jingyu.jia.model.ExceptionOccurrence;
import dev.jingyu.jia.model.Finding;
import dev.jingyu.jia.model.GcEvent;
import dev.jingyu.jia.model.GcLog;
import dev.jingyu.jia.model.Snapshot;
import dev.jingyu.jia.model.ThreadDump;
import dev.jingyu.jia.parse.Epochs;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Rebuilds the incident in time from whichever clocks the inputs happen to carry. */
public final class Timeline {

    private final List<TimelineEntry> entries;
    private final boolean singleAxis;
    private final Long bootEpochMillis;

    private Timeline(List<TimelineEntry> entries, boolean singleAxis, Long bootEpochMillis) {
        this.entries = List.copyOf(entries);
        this.singleAxis = singleAxis;
        this.bootEpochMillis = bootEpochMillis;
    }

    public List<TimelineEntry> entries() {
        return entries;
    }

    /** True when every entry could be placed on one JVM-uptime axis. */
    public boolean singleAxis() {
        return singleAxis;
    }

    public Long bootEpochMillis() {
        return bootEpochMillis;
    }

    public static Timeline empty() {
        return new Timeline(List.of(), true, null);
    }

    public static Timeline build(Snapshot snapshot, Config config, List<Finding> findings) {
        List<TimelineEntry> raw = new ArrayList<>();
        Long boot = snapshot.gcLog().map(GcLog::bootEpochMillis).orElse(null);

        snapshot.gcLog().ifPresent(log -> {
            double p99 = log.pausePercentile(99);
            for (GcEvent e : log.events()) {
                boolean major = e.kind() == GcEvent.Kind.FULL;
                boolean badPause = e.pauseMs() != null && e.pauseMs() > config.slaPauseMs();
                if (!major && !badPause) {
                    continue;
                }
                raw.add(new TimelineEntry(boot == null ? null : boot + (long) (e.atSec() * 1000.0),
                        e.atSec(),
                        (major ? "Full GC" : e.kind() + " pause")
                                + (e.pauseMs() == null ? "" : " " + String.format(Locale.ROOT, "%.0f", e.pauseMs()) + " ms")
                                + (e.heapAfterMb() == null ? "" : ", heap after "
                                + String.format(Locale.ROOT, "%.0fM", e.heapAfterMb())),
                        e.cause() == null ? "" : "cause: " + e.cause(),
                        ArtifactKind.GC_LOG, log.source().name(), e.line(),
                        major || (e.pauseMs() != null && e.pauseMs() >= p99)));
            }
        });

        for (ThreadDump dump : snapshot.threadDumps()) {
            Long epoch = dump.capturedAtRaw() == null ? null : Epochs.toMillis(dump.capturedAtRaw());
            raw.add(new TimelineEntry(epoch, null,
                    "Thread dump " + dump.source().name() + " (" + dump.size() + " threads)",
                    dump.vmReportedDeadlock() ? "jstack reported a deadlock in this capture" : "",
                    ArtifactKind.THREAD_DUMP, dump.source().name(), 1, dump.vmReportedDeadlock()));
        }

        List<ExceptionOccurrence> exceptions = snapshot.exceptions();
        if (!exceptions.isEmpty()) {
            ExceptionOccurrence first = exceptions.stream()
                    .filter(o -> o.epochMillis() != null)
                    .min(Comparator.comparingLong(ExceptionOccurrence::epochMillis))
                    .orElse(exceptions.get(0));
            raw.add(new TimelineEntry(first.epochMillis(), null,
                    "First exception: " + first.outermost().className(),
                    first.rootCause().className() + " at line " + first.startLine(),
                    ArtifactKind.APP_LOG, first.source().name(), first.startLine(), true));
            for (List<ExceptionOccurrence> group : ExceptionCluster.group(exceptions, 5).values()) {
                if (group.size() < Math.max(5, config.exceptionClusterThreshold())) {
                    continue;
                }
                ExceptionOccurrence rep = group.get(0);
                raw.add(new TimelineEntry(rep.epochMillis(), null,
                        group.size() + " × " + rep.rootCause().className(),
                        "first occurrence of this stack",
                        ArtifactKind.APP_LOG, rep.source().name(), rep.startLine(), group.size() >= 20));
            }
        }

        boolean anyUptime = raw.stream().anyMatch(e -> e.uptimeSec() != null);
        boolean anyEpochOnly = raw.stream()
                .anyMatch(e -> e.epochMillis() != null && e.uptimeSec() == null && boot == null);
        boolean singleAxis = !anyUptime || !anyEpochOnly;

        raw.sort(Comparator
                .comparingDouble((TimelineEntry e) -> sortKey(e, boot))
                .thenComparingInt(e -> e.line() == null ? 0 : e.line()));
        return new Timeline(raw, singleAxis, boot);
    }

    private static double sortKey(TimelineEntry e, Long boot) {
        if (e.uptimeSec() != null) {
            return e.uptimeSec();
        }
        if (e.epochMillis() != null) {
            return boot == null ? Double.MAX_VALUE / 2 : (e.epochMillis() - boot) / 1000.0;
        }
        return 0;
    }
}
