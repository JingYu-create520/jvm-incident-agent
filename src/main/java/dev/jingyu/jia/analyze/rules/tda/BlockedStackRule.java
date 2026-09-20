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

                **What it looks for.** BLOCKED / WAITING / TIMED_WAITING threads are grouped by a
                fingerprint of their state plus the top five frames (line numbers excluded, so a
                rebuild does not split a cluster). Any group of five or more is a hotspot: that is
                where the traffic jams.

                A second pass catches the opposite disguise — threads that report RUNNABLE while
                sitting in `socketRead0`, `EPoll.wait` or `epollWait`. They are waiting on a peer,
                and a JVM without a timeout will happily keep them there forever.

                **Why it is trustworthy.** Idle worker threads are excluded. Two hundred Tomcat
                workers parked in `ThreadPoolExecutor.getTask` is a healthy server at night, and a
                naive fingerprint count would call that a hotspot every single time. The exclusion
                list is explicit in `ThreadNoise`, not inferred from "looks fine".

                **Evidence.** One thread stanza per member of the cluster, with its state.

                **False positives.** A deliberately large pool waiting on a shared queue that the
                exclusion list does not recognise (a bespoke executor with a custom take method).
                Raise `--stack-cluster` or add the frame to the idle list.
                """;
    }
}
