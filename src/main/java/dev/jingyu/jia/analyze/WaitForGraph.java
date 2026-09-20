package dev.jingyu.jia.analyze;

import dev.jingyu.jia.model.JThread;
import dev.jingyu.jia.model.LockRef;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The wait-for graph of one dump: an edge {@code A -> B} means "A is queued on a
 * monitor or synchronizer that B owns".
 *
 * <p>Both the plain monitor form ({@code - waiting to lock} / {@code - locked}) and
 * the {@code java.util.concurrent} form ({@code parking to wait for} /
 * "Locked ownable synchronizers") are wired in, because a stuck
 * {@code ReentrantLock} is the more common production deadlock and the classic
 * textbook one is a monitor.
 */
public final class WaitForGraph {

    /** Graph node label, stable within one dump. */
    public static String key(JThread t) {
        return t.name() + "@" + (t.nid() == null ? t.startLine() : t.nid());
    }

    private final Map<String, Node> nodes = new LinkedHashMap<>();

    public record Node(JThread thread, String waitsOn, String waitsOnClass, String ownerKey) {
    }

    public static WaitForGraph of(List<JThread> threads) {
        WaitForGraph g = new WaitForGraph();
        Map<String, String> ownerOf = new HashMap<>();
        for (JThread t : threads) {
            for (LockRef l : t.locks()) {
                if (l.kind() == LockRef.Kind.HELD || l.kind() == LockRef.Kind.OWNABLE_HELD) {
                    ownerOf.put(l.address(), key(t));
                }
            }
        }
        for (JThread t : threads) {
            Optional<LockRef> waiting = t.waitingMonitor();
            if (waiting.isEmpty()) {
                continue;
            }
            LockRef w = waiting.get();
            if (w.kind() == LockRef.Kind.WAITING_ON) {
                // Object.wait() releases the monitor: waiting there is normal, not a block.
                continue;
            }
            String owner = ownerOf.get(w.address());
            if (owner == null || owner.equals(key(t))) {
                continue;
            }
            g.nodes.put(key(t), new Node(t, w.address(), w.className(), owner));
        }
        return g;
    }

    public List<Node> nodes() {
        return List.copyOf(nodes.values());
    }

    public boolean isEmpty() {
        return nodes.isEmpty();
    }

    public Optional<Node> node(String key) {
        return Optional.ofNullable(nodes.get(key));
    }

    public List<String> successors(String key) {
        Node n = nodes.get(key);
        return n == null || n.ownerKey() == null ? List.of() : List.of(n.ownerKey());
    }

    /** Monitors with more than one waiter, most-contended first. */
    public List<Contended> contendedMonitors() {
        Map<String, List<Node>> byMonitor = new LinkedHashMap<>();
        for (Node n : nodes.values()) {
            if (n.waitsOn() != null) {
                byMonitor.computeIfAbsent(n.waitsOn(), k -> new ArrayList<>()).add(n);
            }
        }
        List<Contended> out = new ArrayList<>();
        for (Map.Entry<String, List<Node>> e : byMonitor.entrySet()) {
            if (e.getValue().size() >= 2) {
                String monitorClass = e.getValue().get(0).waitsOnClass();
                String owner = e.getValue().get(0).ownerKey();
                out.add(new Contended(e.getKey(), monitorClass, owner, List.copyOf(e.getValue().stream()
                        .map(n -> n.thread().name()).toList())));
            }
        }
        out.sort((a, b) -> Integer.compare(b.waiters().size(), a.waiters().size()));
        return out;
    }

    public record Contended(String monitor, String monitorClass, String ownerKey, List<String> waiters) {
    }
}
