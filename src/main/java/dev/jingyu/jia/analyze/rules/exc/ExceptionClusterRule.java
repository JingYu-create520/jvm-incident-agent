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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * EXC001 — the exception leaderboard: stacks grouped by root-cause class plus its first
 * frames.
 */
public final class ExceptionClusterRule implements Rule {

    static final int DEPTH = 5;

    @Override
    public String id() {
        return "EXC001";
    }

    @Override
    public String title() {
        return "Repeated exception cluster";
    }

    @Override
    public ArtifactKind artifact() {
        return ArtifactKind.APP_LOG;
    }

    @Override
    public List<Finding> evaluate(Snapshot snapshot, Config config) {
        List<ExceptionOccurrence> all = snapshot.exceptions();
        if (all.isEmpty()) {
            return List.of();
        }
        List<ExceptionCluster> clusters = new ArrayList<>();
        for (List<ExceptionOccurrence> group : ExceptionCluster.group(all, DEPTH).values()) {
            clusters.add(new ExceptionCluster(group.get(0).fingerprint(DEPTH), group));
        }
        clusters.sort(Comparator.comparingInt(ExceptionCluster::count).reversed());

        List<Finding> out = new ArrayList<>();
        int reported = 0;
        for (ExceptionCluster c : clusters) {
            if (c.count() < config.exceptionClusterThreshold()) {
                break;
            }
            if (reported >= 3) {
                break;
            }
            reported++;
            ExceptionOccurrence rep = c.representative();
            boolean fatal = Exceptions.isFatal(rep.rootCause().className());
            Finding.Builder b = Finding.builder(id())
                    .title(title())
                    .artifact(artifact())
                    .severity(fatal ? Severity.HIGH : Severity.MEDIUM)
                    .confidence(Math.min(0.9, 0.55 + c.count() * 0.02))
                    .summary(String.format(Locale.ROOT, "%,d", c.count()) + " occurrences of "
                            + rep.rootCause().className()
                            + c.message().map(m -> ": " + trim(m)).orElse("")
                            + " share one root-cause stack. The exception that reaches the log is "
                            + rep.outermost().className() + "."
                            + (fatal ? " Errors of this kind kill threads rather than requests." : ""))
                    .recommend("Fix the one stack, not the count: identical repeated exceptions are a "
                            + "single bug executed many times.")
                    .recommend("If the message varies while the stack does not, the varying part is data — "
                            + "capture it at the throw site instead of pasting it into a log line.")
                    .metric("count", c.count())
                    .metric("rootClass", rep.rootCause().className())
                    .metric("outerClass", rep.outermost().className())
                    .metric("file", rep.source().name())
                    .metric("fingerprint", c.fingerprint());
            List<dev.jingyu.jia.model.Frame> rootFrames = rep.rootCause().frames();
            for (int i = 0; i < Math.min(rootFrames.size(), 4); i++) {
                dev.jingyu.jia.model.Frame f = rootFrames.get(i);
                b.evidence(Evidence.of(rep.source(), f.line(), f.display()));
            }
            int shown = 0;
            for (ExceptionOccurrence o : c.items()) {
                if (shown++ >= config.maxEvidencePerFinding()) {
                    b.metric("moreOccurrences", c.count() - shown);
                    break;
                }
                b.evidence(Evidence.of(o.source(), o.startLine(),
                        "occurrence" + (o.timestampRaw() == null ? "" : " at " + o.timestampRaw())));
            }
            out.add(b.build());
        }
        return out;
    }

    private static String trim(String s) {
        String t = s.strip();
        return t.length() <= 160 ? t : t.substring(0, 160) + "…";
    }

    @Override
    public String doc() {
        return """
                # EXC001 — Repeated exception cluster
                
                Reduce every throwable block in the log to a fingerprint — root-cause class plus the first five
                frames, line numbers stripped — then count.
                
                Grouping on the *root cause* rather than the wrapper is the whole design. A service that logs
                `DataIntegrityViolationException` for thirty different constraint failures should produce thirty
                clusters, and the four hundred identical `NullPointerException`s behind them should collapse into
                one. Group by the outer class instead and you get the reverse: one meaningless row of five hundred.
                
                Severity stays MEDIUM unless the root cause is an `Error`. The log cannot tell you whether an
                exception was handled gracefully upstream, and pretending otherwise is how these tools get
                ignored; what it can tell you is that the same stack came back five or more times
                (`--exception-threshold`).
                
                Evidence: the root-cause frames themselves, then one line per occurrence with its log timestamp
                where the logger printed one.
                
                Wrong when: one expected, handled failure that simply happens often. From the log alone that is
                indistinguishable from a bug, which is why the finding names the class and the stack and stops
                there — it never claims the exception went unhandled, and severity stays MEDIUM unless the root
                cause is an `Error`.
                """;
    }

    static boolean isFatalClass(String cls) {
        return Exceptions.isFatal(cls);
    }

    private static final class Exceptions {
        private Exceptions() {
        }

        static boolean isFatal(String cls) {
            return cls.contains("OutOfMemoryError")
                    || cls.contains("StackOverflowError")
                    || cls.contains("NoClassDefFoundError")
                    || cls.contains("LinkageError")
                    || (cls.endsWith("Error") && !cls.contains("Exception"));
        }
    }
}
