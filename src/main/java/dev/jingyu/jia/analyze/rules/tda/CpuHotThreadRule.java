package dev.jingyu.jia.analyze.rules.tda;

import dev.jingyu.jia.analyze.Rule;
import dev.jingyu.jia.analyze.rules.ThreadNoise;
import dev.jingyu.jia.model.ArtifactKind;
import dev.jingyu.jia.model.Config;
import dev.jingyu.jia.model.Evidence;
import dev.jingyu.jia.model.Finding;
import dev.jingyu.jia.model.JThread;
import dev.jingyu.jia.model.Severity;
import dev.jingyu.jia.model.Snapshot;
import dev.jingyu.jia.model.ThreadDump;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * TDA006 — CPU-burning threads.
 *
 * <p>{@code cpu=} in a jstack header is cumulative since thread creation, so a single
 * dump only yields a lifetime average. When two dumps exist, the delta of {@code cpu}
 * over the delta of {@code elapsed} is the real utilisation over that interval, and the
 * finding says which of the two it computed.
 */
public final class CpuHotThreadRule implements Rule {

    private static final double MIN_CORES = 0.5;
    private static final double MIN_ELAPSED_SEC = 1.0;

    @Override
    public String id() {
        return "TDA006";
    }

    @Override
    public String title() {
        return "Thread burning CPU";
    }

    @Override
    public ArtifactKind artifact() {
        return ArtifactKind.THREAD_DUMP;
    }

    @Override
    public List<Finding> evaluate(Snapshot snapshot, Config config) {
        List<ThreadDump> dumps = snapshot.threadDumps();
        if (dumps.isEmpty()) {
            return List.of();
        }
        boolean delta = dumps.size() >= 2;
        Map<String, JThread> previous = new LinkedHashMap<>();
        if (delta) {
            for (JThread t : dumps.get(0).threads()) {
                previous.put(identity(t), t);
            }
        }
        ThreadDump last = dumps.get(dumps.size() - 1);
        List<Hot> hot = new ArrayList<>();
        boolean anyCpu = false;
        double totalCores = 0.0;

        for (JThread t : last.threads()) {
            if (t.cpuMillis() == null || t.elapsedSeconds() == null || ThreadNoise.jvmInternal(t)) {
                continue;
            }
            anyCpu = true;
            double cores;
            if (delta) {
                JThread before = previous.get(identity(t));
                if (before == null || before.cpuMillis() == null || before.elapsedSeconds() == null) {
                    continue;
                }
                double dCpu = t.cpuMillis() - before.cpuMillis();
                double dWall = (t.elapsedSeconds() - before.elapsedSeconds()) * 1000.0;
                if (dWall <= 0 || dCpu < 0) {
                    continue;
                }
                cores = dCpu / dWall;
            } else {
                if (t.elapsedSeconds() < MIN_ELAPSED_SEC) {
                    continue;
                }
                cores = t.cpuMillis() / (t.elapsedSeconds() * 1000.0);
            }
            totalCores += cores;
            if (cores >= MIN_CORES) {
                hot.add(new Hot(t, cores));
            }
        }
        if (!anyCpu) {
            return List.of();
        }
        hot.sort(Comparator.comparingDouble((Hot h) -> h.cores).reversed());
        List<Hot> top = hot.subList(0, Math.min(hot.size(), config.cpuHotThreadTopN()));
        if (top.isEmpty()) {
            return List.of();
        }
        double shareOfVm = totalCores <= 0 ? 0 : top.stream().mapToDouble(h -> h.cores).sum() / totalCores;

        List<Finding> out = new ArrayList<>();
        for (Hot h : top) {
            if (h.cores < MIN_CORES) {
                continue;
            }
            String where = h.thread.topFrame().map(f -> f.display()).orElse("(no frames)");
            out.add(Finding.builder(id())
                    .title(title())
                    .artifact(artifact())
                    .severity(h.cores >= 0.95 ? Severity.HIGH : Severity.MEDIUM)
                    .confidence(delta ? 0.85 : 0.6)
                    .summary("\"" + h.thread.name() + "\" holds "
                            + String.format(Locale.ROOT, "%.2f", h.cores) + " of one core ("
                            + (delta ? "measured between the two dumps" : "lifetime average, single dump")
                            + ") while " + h.thread.stateLabel() + ", stopped at " + where)
                    .evidence(Evidence.of(last.source(), h.thread.startLine(),
                            "cpu=" + fmt(h.thread.cpuMillis()) + "ms elapsed=" + fmt(h.thread.elapsedSeconds()) + "s"))
                    .evidence(h.thread.stack().stream().findFirst()
                            .map(f -> Evidence.of(last.source(), f.line(), "hot frame"))
                            .orElse(null))
                    .recommend("Re-capture twice ten seconds apart to confirm it is still burning, then read "
                            + "the quoted frame: an unbounded loop, a regex on hostile input, or hashing into "
                            + "a collapsed structure are the usual suspects.")
                    .metric("file", last.source().name())
                    .metric("thread", h.thread.name())
                    .metric("cores", String.format(Locale.ROOT, "%.3f", h.cores))
                    .metric("basis", delta ? "delta" : "lifetime-average")
                    .metric("vmCpuShare", String.format(Locale.ROOT, "%.2f", shareOfVm))
                    .build());
        }
        return out;
    }

    private static String identity(JThread t) {
        return t.nid() != null ? t.nid() : t.name();
    }

    private static String fmt(Double v) {
        return v == null ? "?" : String.format(Locale.ROOT, "%.1f", v);
    }

    private record Hot(JThread thread, double cores) {
    }

    @Override
    public String doc() {
        return """
                # TDA006 — Thread burning CPU

                **What it looks for.** `cpu=` and `elapsed=` in each thread header. With one dump the
                only honest number is a lifetime average (`cpu / elapsed`), so the finding labels it as
                such and confidence is capped. With two dumps the delta of both columns gives
                utilisation over the interval between captures, which is what "hot right now" means.

                A thread fires at 0.5 of a core or more. JVM housekeeping (JIT compiler, GC threads)
                is excluded — those burn CPU on purpose.

                **Evidence.** The thread header line, annotated with the raw `cpu=`/`elapsed=` values
                the number came from, and the top frame line.

                **False positives.** A thread that spun hard during startup and has been idle since
                still shows a high lifetime average. That is why the metric carries `basis`; trust
                `delta` readings over single-dump ones.
                """;
    }
}
