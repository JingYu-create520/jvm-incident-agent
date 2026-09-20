package dev.jingyu.jia.analyze.rules.tda;

import dev.jingyu.jia.analyze.Rule;
import dev.jingyu.jia.analyze.rules.ThreadNoise;
import dev.jingyu.jia.model.ArtifactKind;
import dev.jingyu.jia.model.Config;
import dev.jingyu.jia.model.Evidence;
import dev.jingyu.jia.model.Finding;
import dev.jingyu.jia.model.Frame;
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
import java.util.Optional;

/**
 * TDA005 — thread-pool starvation: every worker of one pool is busy at the same instant,
 * and busy in the same place.
 */
public final class PoolStarvationRule implements Rule {

    /** Beyond this, a same-name family is a leak (TDA003), not a sizing decision. */
    private static final int MAX_PLAUSIBLE_POOL = 100;

    @Override
    public String id() {
        return "TDA005";
    }

    @Override
    public String title() {
        return "Thread pool starved (no idle worker left)";
    }

    @Override
    public ArtifactKind artifact() {
        return ArtifactKind.THREAD_DUMP;
    }

    @Override
    public List<Finding> evaluate(Snapshot snapshot, Config config) {
        List<Finding> out = new ArrayList<>();
        for (ThreadDump dump : snapshot.threadDumps()) {
            for (Map.Entry<String, List<JThread>> fam : dump.byNameFamily().entrySet()) {
                List<JThread> members = fam.getValue();
                if (members.size() < config.poolStarveMinSize() || ThreadNoise.jvmInternalName(fam.getKey())) {
                    continue;
                }
                // A 140-member family of identical threads is a leak, which TDA003 reports with the
                // growth evidence. Calling that "starvation" would send the reader to pool sizing.
                if (members.size() > MAX_PLAUSIBLE_POOL) {
                    continue;
                }
                List<JThread> busy = members.stream().filter(ThreadNoise::activeOrStuck).toList();
                double idleRatio = 1.0 - (double) busy.size() / members.size();
                if (busy.size() < config.poolStarveMinSize() || idleRatio > 1.0 - config.poolStarveSameFrameRatio()) {
                    continue;
                }
                Map<String, List<JThread>> byFrame = new LinkedHashMap<>();
                for (JThread t : busy) {
                    String key = t.topBusinessFrame().map(Frame::id)
                            .orElseGet(() -> t.topFrame().map(Frame::id).orElse("<empty stack>"));
                    byFrame.computeIfAbsent(key, k -> new ArrayList<>()).add(t);
                }
                Optional<Map.Entry<String, List<JThread>>> worst = byFrame.entrySet().stream()
                        .max(Comparator.comparingInt(e -> e.getValue().size()));
                if (worst.isEmpty()) {
                    continue;
                }
                List<JThread> stuck = worst.get().getValue();
                double share = (double) stuck.size() / members.size();
                if (share < config.poolStarveSameFrameRatio()) {
                    continue;
                }
                String frame = worst.get().getKey();
                out.add(Finding.builder(id())
                        .title(title())
                        .artifact(artifact())
                        .severity(Severity.HIGH)
                        .confidence(Math.min(0.93, 0.7 + share * 0.2))
                        .summary("All " + members.size() + " workers of \"" + fam.getKey()
                                + "\" are occupied and " + stuck.size() + " of them sit in the same frame ("
                                + frame + "). The pool has no spare thread, so every further request queues.")
                        .evidence(stuck.stream().limit(config.maxEvidencePerFinding())
                                .map(t -> Evidence.of(dump.source(), t.startLine(),
                                        "\"" + t.name() + "\" " + t.stateLabel()))
                                .toList())
                        .recommend("Fix what the workers are stuck on first — a bigger pool only moves the "
                                + "queue if the shared dependency is the bottleneck.")
                        .recommend("If the work is genuinely I/O bound, separate it onto its own pool so one "
                                + "slow dependency cannot starve unrelated requests.")
                        .metric("file", dump.source().name())
                        .metric("pool", fam.getKey())
                        .metric("size", members.size())
                        .metric("busy", busy.size())
                        .metric("sameFrame", stuck.size())
                        .metric("sameFrameShare", String.format(Locale.ROOT, "%.2f", share))
                        .build());
            }
        }
        return out;
    }

    @Override
    public String doc() {
        return """
                # TDA005 — Thread pool starvation

                **What it looks for.** Threads are grouped into pools by name family. A pool fires
                when at least 80% of its workers are doing something (i.e. not parked in the pool's
                own work queue) **and** those busy workers share one business frame.

                **Why it is trustworthy.** The idle-worker exclusion is the whole trick: a pool where
                4 of 10 threads wait in `getTask` is normal, and a pool where 10 of 10 are inside
                `JdbcTemplate.query` is about to reject traffic. The same-frame requirement stops
                ordinary concurrency from looking like starvation.

                **Evidence.** Each stuck worker's stanza, with its state.

                **False positives.** A batch job that intentionally saturates its pool at full tilt
                looks identical to starvation from one dump. Take two dumps seconds apart, or raise
                `--pool-starve-ratio`.
                """;
    }
}
