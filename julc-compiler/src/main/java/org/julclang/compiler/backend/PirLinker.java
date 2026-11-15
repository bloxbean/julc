package org.julclang.compiler.backend;

import org.julclang.compiler.pir.PirTerm;

import java.util.*;

/**
 * Deterministic dependency-SCC linking; unrelated definitions never enlarge a recursive group.
 * Definition encounter order is the strict evaluation order, so callers must supply a
 * {@link SequencedMap} rather than an unordered map.
 */
public final class PirLinker {
    private PirLinker() {}

    public static PirTerm link(SequencedMap<String, PirTerm> definitions, PirTerm root) {
        Objects.requireNonNull(definitions, "definitions");
        Objects.requireNonNull(root, "root");
        var orderedDefinitions = new LinkedHashMap<>(definitions);
        var edges = new LinkedHashMap<String, Set<String>>();
        orderedDefinitions.forEach(
                (name, term) -> {
                    var free = new LinkedHashSet<>(PirClosure.freeVariables(term));
                    free.retainAll(orderedDefinitions.keySet());
                    edges.put(name, free);
                });
        var groups = new ArrayList<List<String>>();
        var index = new HashMap<String, Integer>();
        var low = new HashMap<String, Integer>();
        var stack = new ArrayDeque<String>();
        var active = new HashSet<String>();
        for (String name : orderedDefinitions.keySet())
            if (!index.containsKey(name)) visit(name, edges, index, low, stack, active, groups);
        PirTerm result = root;
        for (int i = groups.size() - 1; i >= 0; i--) {
            var group = groups.get(i);
            if (group.size() == 1 && !edges.get(group.getFirst()).contains(group.getFirst()))
                result =
                        new PirTerm.Let(
                                group.getFirst(), orderedDefinitions.get(group.getFirst()), result);
            else {
                if (group.stream().anyMatch(n -> !(orderedDefinitions.get(n) instanceof PirTerm.Lam)))
                    throw new IllegalArgumentException(
                            "Recursive PIR definitions must be functions: " + group);
                result =
                        new PirTerm.LetRec(
                                group.stream()
                                        .map(n -> new PirTerm.Binding(n, orderedDefinitions.get(n)))
                                        .toList(),
                                result);
            }
        }
        PirClosure.check(result);
        return result;
    }

    /**
     * Tarjan's visit from {@code root}, with the depth-first path on a heap stack instead of the Java stack, as a chain of
     * definitions each using the next is as deep as it is long (issue #327, ADR-074). It visits edges in the order the
     * recursive form did, so the groups come out in the same order.
     */
    private static void visit(
            String root,
            Map<String, Set<String>> es,
            Map<String, Integer> ix,
            Map<String, Integer> low,
            Deque<String> stack,
            Set<String> active,
            List<List<String>> groups) {
        record Frame(String node, Iterator<String> edges) {}
        var path = new ArrayDeque<Frame>();
        enter(root, ix, low, stack, active);
        path.push(new Frame(root, es.get(root).iterator()));
        while (!path.isEmpty()) {
            var frame = path.peek();
            String n = frame.node();
            if (frame.edges().hasNext()) {
                String v = frame.edges().next();
                if (!ix.containsKey(v)) {
                    enter(v, ix, low, stack, active);
                    path.push(new Frame(v, es.get(v).iterator()));
                } else if (active.contains(v)) low.put(n, Math.min(low.get(n), ix.get(v)));
                continue;
            }
            if (low.get(n).equals(ix.get(n))) {
                var members = new HashSet<String>();
                String v;
                do {
                    v = stack.pop();
                    active.remove(v);
                    members.add(v);
                } while (!v.equals(n));
                // A group of one is that definition: only a larger group needs ordering by definition order.
                groups.add(members.size() == 1 ? List.of(n)
                        : es.keySet().stream().filter(members::contains).toList());
            }
            path.pop();
            if (!path.isEmpty()) {
                String caller = path.peek().node();
                low.put(caller, Math.min(low.get(caller), low.get(n)));
            }
        }
    }

    private static void enter(String n, Map<String, Integer> ix, Map<String, Integer> low, Deque<String> stack,
                              Set<String> active) {
        ix.put(n, ix.size());
        low.put(n, ix.get(n));
        stack.push(n);
        active.add(n);
    }
}
