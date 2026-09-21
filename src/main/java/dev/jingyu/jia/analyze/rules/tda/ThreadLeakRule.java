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
import java.util.regex.Pattern;

/**
 * TDA003 — thread leak: a name family that is implausibly large, or that only grows
 * between dumps taken seconds apart.
 */
public final class ThreadLeakRule implements Rule {

    /**
     * Containers the platform bounds itself. Matched against the normalised family name, where
     * digits have already become {@code N}, so {@code http-nio-8080-exec-12} arrives here as
     * {@code http-nio-N-exec}.
     */
    private static final Pattern SERVER_POOL = Pattern.compile(
            "^(http-\\w+-\\w+-\\w+|https?-jsse?-\\w+-\\w+|pool-\\w+-thread|\\w*exec\\b|\\w*executor\\b"
                    + "|grpc-\\w+|dubbo-\\w+|reactor-\\w+|\\w*xnio\\w* task|quartz-worker|catalina-\\w+"
                    + "|scheduled-thread|tomcat-handler|vertx-\\w+|netty-\\w+|kafka-\\w+|jetty-\\w+"
                    + "|qtp\\w+|activemq-\\w+|Thrift-\\w+)",
            Pattern.CASE_INSENSITIVE);

    private static final int SERVER_POOL_BAR = 200;

    @Override
    public String id() {
        return "TDA003";
    }

    @Override
    public String title() {
        return "Thread leak (growing or oversized thread family)";
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
        List<Finding> out = new ArrayList<>();
        out.addAll(oversized(dumps, config));
        if (dumps.size() >= 2) {
            out.addAll(growing(dumps, config));
        }
        return out;
    }

    private List<Finding> oversized(List<ThreadDump> dumps, Config config) {
        List<Finding> out = new ArrayList<>();
        for (ThreadDump dump : dumps) {
            Map<String, List<JThread>> families = dump.byNameFamily();
            List<Map.Entry<String, List<JThread>>> ordered = new ArrayList<>(families.entrySet());
            ordered.sort(Comparator.comparingInt((Map.Entry<String, List<JThread>> e) -> e.getValue().size())
                    .reversed());
            int reported = 0;
            for (Map.Entry<String, List<JThread>> e : ordered) {
                if (reported >= 2) {
                    break;
                }
                String family = e.getKey();
                if (ThreadNoise.jvmInternalName(family)) {
                    continue;
                }
                int n = e.getValue().size();
                int bar = SERVER_POOL.matcher(family).find() ? SERVER_POOL_BAR : config.threadLeakThreshold();
                if (n < bar) {
                    continue;
                }
                reported++;
                JThread sample = e.getValue().get(0);
                out.add(Finding.builder(id())
                        .title(title())
                        .artifact(artifact())
                        .severity(Severity.HIGH)
                        .confidence(Math.min(0.9, 0.6 + (n - bar) / 200.0))
                        .summary(n + " threads share the name family \"" + family + "\" in "
                                + dump.source().name() + ". Thread names like these come from one "
                                + "creation site; a pool that is supposed to be bounded does not reach "
                                + n + ", so threads are being created and never retired.")
                        .evidence(Evidence.of(dump.source(), sample.startLine(),
                                "first of " + n + " threads in this family"))
                        .recommend("Find the creation site by this name prefix in your code and make sure "
                                + "each path shuts the pool down (shutdown() in a finally block, or a "
                                + "shared pool instead of a per-request one).")
                        .recommend("Take a second dump a minute later: if the number only grows, the leak "
                                + "is live and an OOM: unable to create new native thread is coming.")
                        .metric("file", dump.source().name())
                        .metric("family", family)
                        .metric("count", n)
                        .metric("threshold", bar)
                        .build());
            }
        }
        return out;
    }

    private List<Finding> growing(List<ThreadDump> dumps, Config config) {
        List<Finding> out = new ArrayList<>();
        Map<String, List<Map.Entry<ThreadDump, Integer>>> series = new LinkedHashMap<>();
        for (ThreadDump dump : dumps) {
            for (Map.Entry<String, List<JThread>> fam : dump.byNameFamily().entrySet()) {
                if (ThreadNoise.jvmInternalName(fam.getKey())) {
                    continue;
                }
                series.computeIfAbsent(fam.getKey(), k -> new ArrayList<>())
                        .add(Map.entry(dump, fam.getValue().size()));
            }
        }
        for (Map.Entry<String, List<Map.Entry<ThreadDump, Integer>>> e : series.entrySet()) {
            List<Map.Entry<ThreadDump, Integer>> points = e.getValue();
            if (points.size() < 2) {
                continue;
            }
            int first = points.get(0).getValue();
            int last = points.get(points.size() - 1).getValue();
            boolean monotonic = true;
            for (int i = 1; i < points.size(); i++) {
                if (points.get(i).getValue() < points.get(i - 1).getValue()) {
                    monotonic = false;
                    break;
                }
            }
            double ratio = first == 0 ? (last > 0 ? Double.MAX_VALUE : 1.0) : (double) last / first;
            int famBar = SERVER_POOL.matcher(e.getKey()).find()
                    ? SERVER_POOL_BAR
                    : Math.min(config.threadLeakThreshold(), SERVER_POOL_BAR);
            if (!monotonic || last < famBar || ratio < config.threadLeakGrowthRatio()) {
                continue;
            }
            List<Evidence> ev = new ArrayList<>();
            for (Map.Entry<ThreadDump, Integer> p : points) {
                ThreadDump d = p.getKey();
                JThread sample = d.byNameFamily().get(e.getKey()).get(0);
                ev.add(Evidence.of(d.source(), sample.startLine(),
                        d.source().name() + ": " + p.getValue() + " threads"));
            }
            String family = e.getKey();
            out.add(Finding.builder(id())
                    .title("Thread leak (thread count only rises across dumps)")
                    .artifact(artifact())
                    .severity(Severity.HIGH)
                    .confidence(Math.min(0.95, 0.75 + ratio * 0.05))
                    .summary("\"" + family + "\" grew from " + first + " to " + last + " threads across "
                            + points.size() + " dumps without ever shrinking (x"
                            + String.format(Locale.ROOT, "%.2f", ratio) + "). A healthy pool oscillates; "
                            + "this one only adds.")
                    .evidence(ev)
                    .recommend("Diff the thread ids between the two dumps — the new ones are created on a "
                            + "request path that never closes its executor.")
                    .metric("family", family)
                    .metric("first", first)
                    .metric("last", last)
                    .metric("growthRatio", ratio)
                    .build());
        }
        return out;
    }

    @Override
    public String doc() {
        return """
                # TDA003 — Thread leak
                
                Thread names carry their origin: `pool-3-thread-17` came from one creation site, `http-nio-8080-exec-9`
                from Tomcat's connector. Strip the numeric parts and group by what is left, and a family is a
                group of threads somebody's code made.
                
                Two independent tests then run on those families.
                
                **Oversized.** A family larger than `--thread-leak-threshold` (default 40). Families the platform
                bounds itself — `http-nio-*-exec`, `pool-N-thread-M`, `grpc-*`, and friends listed in
                `SERVER_POOL` — need 200 or more before they fire, because a busy servlet container sitting at 150
                worker threads is a normal Tuesday and reporting it would be the single most reliable way to make
                people stop trusting this tool.
                
                **Only growing.** Given two or more dumps, a family whose count never decreases and grows by at
                least 25%. This is the test that catches a live leak rather than a large pool: bounded pools are
                big and flat, leaked pools are small and monotonically increasing.
                
                Evidence is one thread stanza per dump for the family, with the running count annotated, so the
                growth is visible in the report itself rather than asserted by it.
                
                Quiet on: a bespoke executor with a legitimately huge fixed pool under 200. Raise
                `--thread-leak-threshold`, or capture two dumps so the growth test can do its job.
                """;
    }
}
