package dev.jingyu.jia.analyze;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tarjan's strongly-connected-components algorithm, written iteratively.
 *
 * <p>A recursive version is shorter, but a thread dump can hold tens of thousands of
 * threads and the recursion depth tracks the chain length — a stack overflow inside a
 * diagnostic tool is the last thing anyone wants at 3 a.m.
 *
 * <p>Any component with more than one node is a deadlock: every thread in it waits on a
 * monitor held by another member, so none of them can ever make progress.
 */
public final class TarjanSCC {

    private TarjanSCC() {
    }

    /**
     * @param edges adjacency map; every node that can be waited on must be a key too
     * @return strongly connected components, largest first
     */
    public static List<List<String>> strongComponents(Map<String, List<String>> edges) {
        int nextIndex = 0;
        Map<String, Integer> index = new HashMap<>();
        Map<String, Integer> low = new HashMap<>();
        Map<String, Boolean> onStack = new HashMap<>();
        Deque<String> stack = new ArrayDeque<>();
        List<List<String>> result = new ArrayList<>();

        for (String root : edges.keySet()) {
            if (index.containsKey(root)) {
                continue;
            }
            index.put(root, nextIndex);
            low.put(root, nextIndex);
            nextIndex++;
            stack.push(root);
            onStack.put(root, true);

            Deque<Frame> work = new ArrayDeque<>();
            work.push(new Frame(root, edges.getOrDefault(root, List.of())));

            while (!work.isEmpty()) {
                Frame f = work.peek();
                if (f.pos < f.neighbors.size()) {
                    String w = f.neighbors.get(f.pos++);
                    if (!edges.containsKey(w)) {
                        continue;
                    }
                    if (!index.containsKey(w)) {
                        index.put(w, nextIndex);
                        low.put(w, nextIndex);
                        nextIndex++;
                        stack.push(w);
                        onStack.put(w, true);
                        work.push(new Frame(w, edges.getOrDefault(w, List.of())));
                    } else if (Boolean.TRUE.equals(onStack.get(w))) {
                        low.put(f.node, Math.min(low.get(f.node), index.get(w)));
                    }
                    continue;
                }

                work.pop();
                if (low.get(f.node).equals(index.get(f.node))) {
                    List<String> comp = new ArrayList<>();
                    String w;
                    do {
                        w = stack.pop();
                        onStack.put(w, false);
                        comp.add(w);
                    } while (!w.equals(f.node));
                    result.add(comp);
                }
                if (!work.isEmpty()) {
                    String parent = work.peek().node;
                    low.put(parent, Math.min(low.get(parent), low.get(f.node)));
                }
            }
        }
        result.sort((a, b) -> Integer.compare(b.size(), a.size()));
        return result;
    }

    /**
     * Components that are actual cycles: more than one thread waiting on each other, or a
     * single thread waiting on a monitor it already owns.
     */
    public static List<List<String>> cycles(Map<String, List<String>> edges) {
        List<List<String>> out = new ArrayList<>();
        for (List<String> comp : strongComponents(edges)) {
            if (comp.size() > 1) {
                out.add(comp);
            } else if (comp.size() == 1 && edges.getOrDefault(comp.get(0), List.of()).contains(comp.get(0))) {
                out.add(comp);
            }
        }
        return out;
    }

    private static final class Frame {
        private final String node;
        private final List<String> neighbors;
        private int pos;

        Frame(String node, List<String> neighbors) {
            this.node = node;
            this.neighbors = neighbors;
        }
    }
}
