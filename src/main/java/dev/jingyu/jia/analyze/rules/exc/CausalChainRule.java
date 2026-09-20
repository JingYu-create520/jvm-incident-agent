package dev.jingyu.jia.analyze.rules.exc;

import dev.jingyu.jia.analyze.Rule;
import dev.jingyu.jia.model.ArtifactKind;
import dev.jingyu.jia.model.Config;
import dev.jingyu.jia.model.Evidence;
import dev.jingyu.jia.model.ExceptionOccurrence;
import dev.jingyu.jia.model.Finding;
import dev.jingyu.jia.model.Frame;
import dev.jingyu.jia.model.Severity;
import dev.jingyu.jia.model.Snapshot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * EXC002 — causal-chain attribution: walk each {@code Caused by:} chain to the bottom and
 * name the first frame in the caller's own code.
 */
public final class CausalChainRule implements Rule {

    /**
     * An attribution site. Keyed by {@code class#method} rather than by the {@link Frame} record:
     * a frame carries the line it was read from, so grouping on the whole record would give every
     * occurrence its own site and a twelve-fold failure would never reach the threshold.
     */
    private record Site(String exceptionClass, String frameId) {
    }

    @Override
    public String id() {
        return "EXC002";
    }

    @Override
    public String title() {
        return "Root cause attributed to application code";
    }

    @Override
    public ArtifactKind artifact() {
        return ArtifactKind.APP_LOG;
    }

    @Override
    public List<Finding> evaluate(Snapshot snapshot, Config config) {
        Map<Site, List<ExceptionOccurrence>> bySite = new LinkedHashMap<>();
        int chained = 0;
        for (ExceptionOccurrence o : snapshot.exceptions()) {
            if (!o.hasCauseChain()) {
                continue;
            }
            chained++;
            Optional<Frame> business = o.rootBusinessFrame();
            if (business.isPresent()) {
                bySite.computeIfAbsent(new Site(o.rootCause().className(), business.get().id()),
                        k -> new ArrayList<>()).add(o);
            }
        }
        if (bySite.isEmpty()) {
            return List.of();
        }
        List<Map.Entry<Site, List<ExceptionOccurrence>>> ordered = new ArrayList<>(bySite.entrySet());
        ordered.sort(Comparator.comparingInt(e -> e.getValue().size()));

        List<Finding> out = new ArrayList<>();
        int reported = 0;
        for (int i = ordered.size() - 1; i >= 0 && reported < 3; i--, reported++) {
            Map.Entry<Site, List<ExceptionOccurrence>> e = ordered.get(i);
            Site site = e.getKey();
            List<ExceptionOccurrence> hits = e.getValue();
            if (hits.size() < Math.max(2, config.exceptionClusterThreshold() / 2)) {
                continue;
            }
            ExceptionOccurrence rep = hits.get(0);
            String outer = rep.outermost().className();
            out.add(Finding.builder(id())
                    .title(title())
                    .artifact(artifact())
                    .severity(hits.size() >= config.exceptionClusterThreshold() ? Severity.HIGH : Severity.MEDIUM)
                    .confidence(0.82)
                    .summary(hits.size() + " wrapped failure(s) all bottom out at " + site.exceptionClass()
                            + " thrown from " + display(site, hits) + ". The log shows " + outer
                            + " at the top; that class is a translation layer, and the real fault is the "
                            + "line below it.")
                    .evidence(hits.stream().limit(config.maxEvidencePerFinding())
                            .map(o -> Evidence.range(o.source(), o.startLine(), o.endLine(),
                                    chainSummary(o)))
                            .toList())
                    .recommend("Handle or prevent the cause at the quoted line; catching " + outer
                            + " further up only re-wraps it.")
                    .recommend("If the message is uninformative, this is the place to add context — "
                            + "identifiers, not payloads.")
                    .metric("count", hits.size())
                    .metric("rootClass", site.exceptionClass())
                    .metric("frame", site.frameId())
                    .metric("outerClass", outer)
                    .metric("file", rep.source().name())
                    .build());
        }
        if (out.isEmpty()) {
            return List.of();
        }
        return out;
    }

    private static String display(Site site, List<ExceptionOccurrence> hits) {
        return hits.stream().flatMap(o -> o.rootCause().frames().stream())
                .filter(f -> f.id().equals(site.frameId()))
                .findFirst()
                .map(Frame::display)
                .orElse(site.frameId());
    }

    private static String chainSummary(ExceptionOccurrence o) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < o.chain().size(); i++) {
            if (i > 0) {
                sb.append(" ← ");
            }
            sb.append(o.chain().get(i).className());
        }
        return sb.toString();
    }

    @Override
    public String doc() {
        return """
                # EXC002 — Causal-chain attribution

                **What it looks for.** For every stack with a `Caused by:` chain, the last link is taken and
                scanned top-down for the first frame that is neither a JDK nor a known framework package
                (`java.*`, `jdk.*`, `sun.*`, Spring, Tomcat, Netty, Hibernate, …). Occurrences that bottom
                out at the same exception class *and* the same business line are counted together.

                **Why it is trustworthy.** Frameworks re-throw: a controller logs
                `DataIntegrityViolationException`, five frames and three wrappers down is
                `SQLException` at your DAO line. Attributing the fault to the wrapper sends people to the
                wrong file. The exclusion list is explicit, and the finding quotes the whole chain
                (`A ← B ← C`) so the attribution can be re-checked in the log.

                **Evidence.** The start and end line of each occurrence, annotated with its chain.

                **False positives.** If your own code lives under a package that looks like a framework
                (say `org.apache.yourapp`), the attribution falls back to the JDK boundary and may name a
                framework frame instead. Two dumps of the same log make that obvious.
                """;
    }

    /** Exposed for tests: how many chained occurrences the rule saw. */
    public static int chainedCount(Snapshot snapshot) {
        int n = 0;
        for (ExceptionOccurrence o : snapshot.exceptions()) {
            if (o.hasCauseChain()) {
                n++;
            }
        }
        return n;
    }
}
