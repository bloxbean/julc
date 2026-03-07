package com.bloxbean.cardano.julc.compiler.util;

import com.bloxbean.cardano.julc.compiler.pir.PirTerm;

import java.util.*;
import java.util.function.BiFunction;

/**
 * Resolves method dependency order using Tarjan's SCC algorithm.
 * Produces binding groups in topological order suitable for Let/LetRec wrapping:
 * dependents first (innermost), dependencies last (outermost).
 */
public class MethodDependencyResolver {

    /** A named PIR binding (method name + compiled body). */
    public record NamedBinding(String name, PirTerm body) {}

    /**
     * A group of mutually dependent bindings.
     * Size 1 = normal or self-recursive method.
     * Size 2 = mutual recursion (handled by Bekic's theorem in UplcGenerator).
     * Size >2 = unsupported mutual recursion.
     */
    public record BindingGroup(List<NamedBinding> bindings) {
        public BindingGroup { bindings = List.copyOf(bindings); }

        public boolean isSingle() { return bindings.size() == 1; }
    }

    /**
     * Resolve dependency order for the given bindings.
     *
     * @param bindings the method bindings to sort
     * @param containsVarRef function to check if a PirTerm references a variable name
     * @return binding groups in topological order: dependents first (innermost), dependencies last (outermost)
     */
    public static List<BindingGroup> resolveDependencyOrder(
            List<NamedBinding> bindings,
            BiFunction<PirTerm, String, Boolean> containsVarRef) {

        if (bindings.isEmpty()) return List.of();
        if (bindings.size() == 1) {
            return List.of(new BindingGroup(List.of(bindings.get(0))));
        }

        // Build name -> index mapping
        var nameToIndex = new HashMap<String, Integer>();
        for (int i = 0; i < bindings.size(); i++) {
            nameToIndex.put(bindings.get(i).name(), i);
        }

        // Build adjacency list: method -> methods it calls
        var adj = new ArrayList<List<Integer>>();
        for (int i = 0; i < bindings.size(); i++) {
            var deps = new ArrayList<Integer>();
            for (int j = 0; j < bindings.size(); j++) {
                if (i != j && containsVarRef.apply(bindings.get(i).body(), bindings.get(j).name())) {
                    deps.add(j);
                }
            }
            adj.add(deps);
        }

        // Tarjan's SCC algorithm
        var sccs = tarjanScc(adj, bindings.size());

        // Tarjan's produces SCCs in reverse topological order: leaves (no dependencies) first,
        // roots (dependents) last. For Let wrapping, we need dependents first (innermost),
        // dependencies last (outermost). So reverse.
        Collections.reverse(sccs);

        var result = new ArrayList<BindingGroup>();
        for (var scc : sccs) {
            var groupBindings = new ArrayList<NamedBinding>();
            for (int idx : scc) {
                groupBindings.add(bindings.get(idx));
            }
            result.add(new BindingGroup(groupBindings));
        }

        return result;
    }

    /**
     * Tarjan's SCC algorithm. Returns SCCs in reverse topological order
     * (nodes that depend on others come first, leaf nodes come last).
     */
    private static List<List<Integer>> tarjanScc(List<List<Integer>> adj, int n) {
        var index = new int[n];
        var lowlink = new int[n];
        var onStack = new boolean[n];
        var visited = new boolean[n];
        Arrays.fill(index, -1);

        var stack = new ArrayDeque<Integer>();
        var sccs = new ArrayList<List<Integer>>();
        var counter = new int[]{0}; // mutable counter

        for (int i = 0; i < n; i++) {
            if (!visited[i]) {
                strongConnect(i, adj, index, lowlink, onStack, visited, stack, sccs, counter);
            }
        }

        return sccs;
    }

    private static void strongConnect(int v, List<List<Integer>> adj,
                                       int[] index, int[] lowlink, boolean[] onStack,
                                       boolean[] visited, Deque<Integer> stack,
                                       List<List<Integer>> sccs, int[] counter) {
        index[v] = counter[0];
        lowlink[v] = counter[0];
        counter[0]++;
        visited[v] = true;
        stack.push(v);
        onStack[v] = true;

        for (int w : adj.get(v)) {
            if (!visited[w]) {
                strongConnect(w, adj, index, lowlink, onStack, visited, stack, sccs, counter);
                lowlink[v] = Math.min(lowlink[v], lowlink[w]);
            } else if (onStack[w]) {
                lowlink[v] = Math.min(lowlink[v], index[w]);
            }
        }

        if (lowlink[v] == index[v]) {
            var scc = new ArrayList<Integer>();
            int w;
            do {
                w = stack.pop();
                onStack[w] = false;
                scc.add(w);
            } while (w != v);
            sccs.add(scc);
        }
    }
}
