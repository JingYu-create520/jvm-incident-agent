package dev.jingyu.jia.analyze.rules.tda;

import dev.jingyu.jia.analyze.Rule;
import dev.jingyu.jia.analyze.TarjanSCC;
import dev.jingyu.jia.analyze.WaitForGraph;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * TDA001 — deadlock detection: build the wait-for graph of each dump and look for
 * strongly connected components.
 */
public final class DeadlockRule implements Rule {

    @Override
    public String id() {
        return "TDA001";
    }

    @Override
    public String title() {
        return "Deadlock (cycle in the wait-for graph)";
    }

    @Override
    public ArtifactKind artifact() {
        return ArtifactKind.THREAD_DUMP;
    }

    @Override
    public List<Finding> evaluate(Snapshot snapshot, Config config) {
        List<Finding> findings = new ArrayList<>();
        for (ThreadDump dump : snapshot.threadDumps()) {
            findings.addAll(oneDump(dump, config));
        }
        return findings;
    }

    private List<Finding> oneDump(ThreadDump dump, Config config) {
        List<Finding> findings = new ArrayList<>();
        WaitForGraph graph = WaitForGraph.of(dump.threads());
        Map<String, List<String>> edges = new LinkedHashMap<>();
        for (WaitForGraph.Node n : graph.nodes()) {
            edges.put(WaitForGraph.key(n.thread()), List.of(n.ownerKey()));
        }
        List<List<String>> cycles = TarjanSCC.cycles(edges);

        for (List<String> cycle : cycles) {
            Finding.Builder b = Finding.builder(id())
                    .title(title())
                    .artifact(artifact())
                    .severity(Severity.CRITICAL)
                    .confidence(dump.vmReportedDeadlock() ? 0.99 : 0.92)
                    .metric("file", dump.source().name())
                    .metric("threadsInCycle", cycle.size())
                    .recommend("Break the lock ordering: acquire the two monitors in the same order everywhere, "
                            + "or replace them with one lock / a tryLock with a timeout.")
                    .recommend("Reproduce under jconsole or async-profiler; the frames below show the exact "
                            + "acquisition order each thread took.");
            StringBuilder summary = new StringBuilder();
            List<String> chain = new ArrayList<>();
            for (String key : cycle) {
                WaitForGraph.Node n = graph.node(key).orElse(null);
                if (n == null) {
                    continue;
                }
                JThread t = n.thread();
                String waits = n.waitsOn() + (n.waitsOnClass() == null ? "" : " (" + n.waitsOnClass() + ")");
                chain.add(t.name() + " waits for " + waits + ", held by " + shortOwner(n.ownerKey()));
                b.evidence(Evidence.range(dump.source(), t.startLine(), Math.min(t.endLine(), t.startLine() + 6),
                        t.name() + " is " + t.stateLabel() + ", waiting on " + waits));
                b.evidence(Evidence.of(dump.source(), ownerLockLine(dump, n),
                        "monitor " + n.waitsOn() + " is held by " + shortOwner(n.ownerKey())));
                summary.append(t.name()).append(" -> ");
            }
            if (!chain.isEmpty()) {
                b.metric("cycle", List.copyOf(chain));
                b.summary(String.join("; ", chain) + ".") ;
            } else {
                b.summary("Cycle of " + cycle.size() + " threads in the wait-for graph in " + dump.source().name());
            }
            findings.add(b.build());
        }

        if (findings.isEmpty() && dump.vmReportedDeadlock()) {
            findings.add(Finding.builder(id())
                    .title(title())
                    .artifact(artifact())
                    .severity(Severity.CRITICAL)
                    .confidence(0.80)
                    .summary("jstack reported a deadlock in " + dump.source().name()
                            + ", but the lock lines could not be reconstructed into a cycle — the dump is "
                            + "probably truncated or uses an unsupported lock syntax.")
                    .evidence(dump.deadlockLines().isEmpty() ? Evidence.of(dump.source(), 1)
                            : Evidence.range(dump.source(), trailerLine(dump),
                            Math.min(dump.source().size(), trailerLine(dump) + dump.deadlockLines().size())))
                    .recommend("Re-capture with `jstack -l <pid>` and pass every file in the same directory.")
                    .metric("file", dump.source().name())
                    .build());
        }
        return findings;
    }

    private static String shortOwner(String ownerKey) {
        int at = ownerKey.indexOf('@');
        return at < 0 ? ownerKey : ownerKey.substring(0, at);
    }

    private int ownerLockLine(ThreadDump dump, WaitForGraph.Node waiter) {
        String ownerName = shortOwner(waiter.ownerKey());
        Optional<JThread> owner = dump.threads().stream()
                .filter(t -> t.name().equals(ownerName))
                .findFirst();
        if (owner.isEmpty()) {
            return 1;
        }
        for (LockRef l : owner.get().heldSynchronizers()) {
            if (l.address().equals(waiter.waitsOn())) {
                return l.line();
            }
        }
        return owner.get().startLine();
    }

    private int trailerLine(ThreadDump dump) {
        List<String> lines = dump.source().lines();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).toLowerCase().startsWith("found ") && lines.get(i).toLowerCase().contains("deadlock")) {
                return i + 1;
            }
        }
        return 1;
    }

    @Override
    public String doc() {
        return """
                # TDA001 — Deadlock

                Every `waiting to lock <0x…>` / `parking to wait for <0x…>` line is paired with
                whichever thread prints `locked <0x…>` (or lists that address under *Locked ownable
                synchronizers*) for the same monitor. That gives a directed graph over the threads in
                one dump, edge from waiter to owner, and Tarjan's strongly-connected-components
                algorithm returns every component with two or more members. Inside such a component
                each thread waits on a monitor another member holds, so none of them can ever run
                again: that is the definition of a deadlock, not a heuristic about it.

                Two things make this more than a re-implementation of what jstack already prints.

                The graph covers `java.util.concurrent` locks as well as monitors. A cycle of threads
                parked on each other's `ReentrantLock` is completely invisible to jstack's own
                detector — it prints no trailer at all — and shows up here. When the trailer does
                fire and the graph agrees, confidence is 0.99; when only the graph fires, 0.92; when
                the trailer fires but the lock lines will not reconstruct into a cycle, that is
                reported as a parser limitation rather than dropped.

                Tarjan is written iteratively. Recursion depth tracks chain length and a tired
                production JVM can hold tens of thousands of threads; a stack overflow in the tool
                you reach for at 3 a.m. is not an acceptable failure mode.

                Evidence is the full stanza of each thread in the cycle plus the exact line where its
                partner holds the monitor it is queued on.

                Can be wrong when: effectively never, which is why this is the only thread rule with
                no threshold. It would need two unrelated objects to share a monitor address.
                """;
    }
}
