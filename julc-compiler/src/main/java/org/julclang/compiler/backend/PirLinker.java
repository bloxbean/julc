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
                if (group.size() > 2)
                    throw new IllegalArgumentException(
                            "Recursive groups larger than two functions require a backend"
                                + " extension: "
                                    + group);
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

    private static void visit(
            String n,
            Map<String, Set<String>> es,
            Map<String, Integer> ix,
            Map<String, Integer> low,
            Deque<String> stack,
            Set<String> active,
            List<List<String>> groups) {
        ix.put(n, ix.size());
        low.put(n, ix.get(n));
        stack.push(n);
        active.add(n);
        for (String v : es.get(n)) {
            if (!ix.containsKey(v)) {
                visit(v, es, ix, low, stack, active, groups);
                low.put(n, Math.min(low.get(n), low.get(v)));
            } else if (active.contains(v)) low.put(n, Math.min(low.get(n), ix.get(v)));
        }
        if (low.get(n).equals(ix.get(n))) {
            var members = new HashSet<String>();
            String v;
            do {
                v = stack.pop();
                active.remove(v);
                members.add(v);
            } while (!v.equals(n));
            groups.add(es.keySet().stream().filter(members::contains).toList());
        }
    }
}
