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

                **What it looks for.** Every throwable block in the application log is reduced to a
                fingerprint: root-cause class plus the first five frames, with line numbers removed so a
                rebuild does not split one bug into two clusters. Anything appearing five or more times
                (`--exception-threshold`) is reported, most frequent first.

                **Why it is trustworthy.** The grouping key is the *root cause* stack, not the wrapper. A
                service that logs `DataIntegrityViolationException` for 30 different constraint failures
                produces 30 clusters, while the 400 identical `NullPointerException`s that caused them all
                collapse into one — which is exactly what you want to see first.

                **Evidence.** The root-cause frames themselves, then one line per occurrence with its log
                timestamp where available.

                **False positives.** A single expected, handled failure that happens often looks identical to
                a bug from the log alone. The finding says which class and stack it is, never that it is
                unhandled — that judgement stays with the reader, which is why severity is MEDIUM unless the
                root cause is an `Error`.
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
