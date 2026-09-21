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
import java.util.Map;

/**
 * TDA004 — blocked-stack hotspot: many threads parked in the same place, or "RUNNABLE"
 * threads that are really waiting on a socket.
 */
public final class BlockedStackRule implements Rule {

    private static final int FINGERPRINT_DEPTH = 5;

    @Override
    public String id() {
        return "TDA004";
    }

    @Override
    public String title() {
        return "Stack hotspot (many threads stuck in the same place)";
    }

    @Override
    public ArtifactKind artifact() {
        return ArtifactKind.THREAD_DUMP;
    }

    @Override
    public List<Finding> evaluate(Snapshot snapshot, Config config) {
        List<Finding> out = new ArrayList<>();
        for (ThreadDump dump : snapshot.threadDumps()) {
            out.addAll(clusters(dump, config));
            Finding socket = socketWaiters(dump, config);
            if (socket != null) {
                out.add(socket);
            }
        }
        return out;
    }

    private List<Finding> clusters(ThreadDump dump, Config config) {
        Map<String, List<JThread>> groups = new LinkedHashMap<>();
        for (JThread t : dump.threads()) {
            if (ThreadNoise.jvmInternal(t) || !ThreadNoise.activeOrStuck(t) || t.stack().isEmpty()) {
                continue;
            }
            if (!t.blocked() && !t.waiting()) {
                continue;
            }
            // Sleeping workers are a thread-count story (TDA003), not a contention story.
            if (ThreadNoise.sleeping(t)) {
                continue;
            }
            groups.computeIfAbsent(ThreadDump.stackFingerprint(t, FINGERPRINT_DEPTH), k -> new ArrayList<>()).add(t);
        }

        List<Map.Entry<String, List<JThread>>> ordered = new ArrayList<>(groups.entrySet());
        ordered.sort(Comparator.comparingInt((Map.Entry<String, List<JThread>> e) -> e.getValue().size())
                .reversed());

        List<Finding> out = new ArrayList<>();
        int reported = 0;
        for (Map.Entry<String, List<JThread>> e : ordered) {
            if (reported >= 2) {
                break;
            }
            List<JThread> members = e.getValue();
            if (members.size() < config.stackClusterThreshold()) {
                break;
            }
            JThread rep = members.get(0);
            String where = rep.stack().isEmpty() ? "unknown" : rep.stack().get(0).display();
            long blocked = members.stream().filter(JThread::blocked).count();
            reported++;
            out.add(Finding.builder(id())
                    .title(title())
                    .artifact(artifact())
                    .severity(blocked >= members.size() / 2.0 && blocked >= config.stackClusterThreshold()
                            ? Severity.HIGH : Severity.MEDIUM)
                    .confidence(Math.min(0.9, 0.5 + members.size() * 0.03))
                    .summary(members.size() + " threads share one stack in " + dump.source().name()
                            + " (" + blocked + " of them BLOCKED on a monitor), all stopped at " + where)
                    .evidence(members.stream().limit(config.maxEvidencePerFinding())
                            .map(t -> Evidence.of(dump.source(), t.startLine(),
                                    "\"" + t.name() + "\" " + t.stateLabel()))
                            .toList())
                    .recommend("This is where the traffic jams. Read the top frame's own code for what it "
                            + "waits on, then fix that dependency rather than the threads.")
                    .metric("file", dump.source().name())
                    .metric("variant", "stack-cluster")
                    .metric("threads", members.size())
                    .metric("blocked", blocked)
                    .metric("topFrame", where)
                    .build());
        }
        return out;
    }

    private Finding socketWaiters(ThreadDump dump, Config config) {
        List<JThread> inSocket = new ArrayList<>();
        for (JThread t : dump.threads()) {
            if (!ThreadNoise.jvmInternal(t) && ThreadNoise.socketBlocked(t)) {
                inSocket.add(t);
            }
        }
        if (inSocket.size() < Math.max(3, config.stackClusterThreshold() - 2)) {
            return null;
        }
        Map<String, Long> byFrame = new LinkedHashMap<>();
        for (JThread t : inSocket) {
            t.topFrame().map(Frame::display).ifPresent(f -> byFrame.merge(f, 1L, Long::sum));
        }
        String worst = byFrame.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("socket read");
        return Finding.builder(id())
                .title("RUNNABLE threads blocked on network I/O")
                .artifact(artifact())
                .severity(Severity.MEDIUM)
                .confidence(0.72)
                .summary(inSocket.size() + " threads are reported RUNNABLE but sit in a native socket "
                        + "call (" + worst + "). The JVM cannot show these as blocked, so thread-count "
                        + "dashboards look healthy while every worker is actually waiting on a peer.")
                .evidence(inSocket.stream().limit(config.maxEvidencePerFinding())
                        .map(t -> Evidence.of(dump.source(), t.startLine(), "\"" + t.name() + "\""))
                        .toList())
                .recommend("Set an explicit read/connect timeout on the client they are waiting for; the "
                        + "default is often infinite, which turns one slow dependency into total outage.")
                .recommend("Check the downstream service's latency — these threads are a symptom, not the "
                        + "cause.")
                .metric("file", dump.source().name())
                .metric("variant", "socket-io")
                .metric("threads", inSocket.size())
                .metric("topFrame", worst)
                .build();
    }

    @Override
    public String doc() {
        return """
                # TDA004 — Stack hotspot
                
                Group BLOCKED / WAITING / TIMED_WAITING threads by their state plus the top five frames, and
                report any group of five or more. That is where the traffic jams. Line numbers are excluded from
                the fingerprint, because otherwise one rebuild splits a single bug into two clusters.
                
                The hard part is not the grouping. It is knowing what does not count.
                
                Two hundred Tomcat workers parked in `ThreadPoolExecutor.getTask` is a healthy server at night. So
                is a pool waiting on `SynchronousQueue.poll`, `LinkedBlockingQueue.take`, `LockSupport.park` under
                `getTask`, or its own `Object.wait()`. A naive fingerprint count calls all of that a hotspot, which
                is exactly why most home-grown thread-dump scripts get dismissed after one use. The exclusion list
                lives in `ThreadNoise` and is written out frame by frame rather than inferred from "looks idle".
                Threads sleeping in `Thread.sleep` are excluded too — that is a thread-count question for TDA003,
                not a contention question.
                
                A second pass catches the opposite disguise: threads reporting RUNNABLE while sitting in
                `socketRead0`, `EPoll.wait` or `kevent0`. They are waiting on a peer, the JVM will not call them
                blocked, and a dashboard counting blocked threads will look perfectly calm while every worker is
                gone. That variant is tagged `socket-io` in the metrics so the report can rank it differently.
                
                Evidence: one stanza per member of the cluster, with its state.
                
                Wrong when: a custom executor whose take method is not in the idle list. Add the frame, or raise
                `--stack-cluster`.
                """;
    }
}
