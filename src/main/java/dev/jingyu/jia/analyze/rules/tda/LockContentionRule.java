package dev.jingyu.jia.analyze.rules.tda;

import dev.jingyu.jia.analyze.Rule;
import dev.jingyu.jia.model.ArtifactKind;
import dev.jingyu.jia.model.Config;
import dev.jingyu.jia.model.Evidence;
import dev.jingyu.jia.model.Finding;
import dev.jingyu.jia.model.JThread;
import dev.jingyu.jia.model.LockRef;
import dev.jingyu.jia.model.Severity;
import dev.jingyu.jia.model.Snapshot;
import dev.jingyu.jia.model.ThreadDump;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TDA002 — lock contention hotspot: one monitor, many waiters.
 */
public final class LockContentionRule implements Rule {

    @Override
    public String id() {
        return "TDA002";
    }

    @Override
    public String title() {
        return "Contended monitor (one lock, many waiters)";
    }

    @Override
    public ArtifactKind artifact() {
        return ArtifactKind.THREAD_DUMP;
    }

    @Override
    public List<Finding> evaluate(Snapshot snapshot, Config config) {
        List<Finding> out = new ArrayList<>();
        for (ThreadDump dump : snapshot.threadDumps()) {
            out.addAll(oneDump(dump, config));
        }
        return out;
    }

    private List<Finding> oneDump(ThreadDump dump, Config config) {
        Map<String, List<JThread>> waiters = new LinkedHashMap<>();
        Map<String, String> monitorClass = new LinkedHashMap<>();
        Map<String, JThread> holders = new LinkedHashMap<>();
        for (JThread t : dump.threads()) {
            for (LockRef l : t.locksOfKind(LockRef.Kind.WAITING_TO_LOCK)) {
                waiters.computeIfAbsent(l.address(), k -> new ArrayList<>()).add(t);
                monitorClass.putIfAbsent(l.address(), l.className());
            }
        }
        for (JThread t : dump.threads()) {
            for (LockRef l : t.locksOfKind(LockRef.Kind.HELD)) {
                holders.putIfAbsent(l.address(), t);
            }
        }

        List<Map.Entry<String, List<JThread>>> ordered = new ArrayList<>(waiters.entrySet());
        ordered.sort(Comparator.comparingInt((Map.Entry<String, List<JThread>> e) -> e.getValue().size())
                .reversed());

        List<Finding> out = new ArrayList<>();
        int reported = 0;
        for (Map.Entry<String, List<JThread>> e : ordered) {
            List<JThread> queued = e.getValue();
            if (queued.size() < Math.max(2, config.lockWaiterThreshold())) {
                break;
            }
            if (reported >= 3) {
                break;
            }
            reported++;
            String addr = e.getKey();
            JThread holder = holders.get(addr);
            int maxEv = config.maxEvidencePerFinding();

            Finding.Builder b = Finding.builder(id())
                    .title(title())
                    .artifact(artifact())
                    .severity(queued.size() >= 10 ? Severity.HIGH : Severity.MEDIUM)
                    .confidence(Math.min(0.95, 0.55 + queued.size() * 0.04))
                    .metric("file", dump.source().name())
                    .metric("monitor", addr)
                    .metric("monitorClass", monitorClass.get(addr))
                    .metric("waiters", queued.size())
                    .recommend("Shorten the critical section around this monitor — move I/O, RPC and "
                            + "logging out of the synchronized block.")
                    .recommend("If every waiter wants the same map or list, shard it or switch to a "
                            + "concurrent structure; a single hot lock caps throughput at one thread.");
            String holderLabel = holder == null ? "owner not visible in this dump"
                    : "held by \"" + holder.name() + "\"";
            b.summary(queued.size() + " threads are queued on monitor " + addr
                    + (monitorClass.get(addr) == null ? "" : " (a " + monitorClass.get(addr) + ")")
                    + ", " + holderLabel + ".");
            if (holder != null) {
                b.metric("holder", holder.name());
                b.metric("holderState", holder.stateLabel());
                holder.topBusinessFrame().ifPresent(f -> b.metric("holderFrame", f.display()));
                b.evidence(Evidence.range(dump.source(), holder.startLine(),
                        Math.min(holder.endLine(), holder.startLine() + 8),
                        "the holder's own stack — this is the code everyone else is waiting for"));
            }
            int shown = 0;
            for (JThread t : queued) {
                if (shown++ >= maxEv) {
                    b.metric("moreWaiters", queued.size() - shown);
                    break;
                }
                t.locksOfKind(LockRef.Kind.WAITING_TO_LOCK).stream()
                        .filter(l -> l.address().equals(addr))
                        .findFirst()
                        .ifPresent(l -> b.evidence(Evidence.of(dump.source(), l.line(),
                                "\"" + t.name() + "\" is " + t.stateLabel())));
            }
            out.add(b.build());
        }
        return out;
    }

    @Override
    public String doc() {
        return """
                # TDA002 — Contended monitor
                
                Three or more threads queued on the same monitor address (`--lock-waiters` to move the line) is a
                queue, and a queue is a bottleneck. The rule ranks monitors by waiter count and names the holder —
                resolved from the matching `- locked <0x…>` line — because the useful answer is not "there is
                contention" but "here is the code everyone is standing behind".
                
                Quotes the holder's stanza plus one line per waiter. The rule does not fire on a single thread
                waiting on a single lock, and never counts the holder as a waiter on its own monitor.
                """;
    }
}
