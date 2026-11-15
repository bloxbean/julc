package org.julclang.compiler.resolve;

import org.julclang.compiler.pir.PirType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * The order in which a batch of named types registers, shared by both Java frontends: strongly connected groups of
 * field-type dependencies, dependencies first, and the productivity rule for a recursive group. It reads names and
 * registered PIR types only.
 */
public final class TypeGroups {

    private TypeGroups() {
    }

    /**
     * The strongly connected groups of {@code deps}, dependencies first, ties by declaration order (the map's order).
     * Both frontends order registration by this, so a recursive group's references come out alike.
     */
    public static List<List<String>> dependencyGroups(Map<String, Set<String>> deps) {
        var index = new int[] {0};
        var indexes = new HashMap<String, Integer>();
        var lowLinks = new HashMap<String, Integer>();
        var stack = new ArrayDeque<String>();
        var onStack = new HashSet<String>();
        var components = new ArrayList<List<String>>();

        for (String node : deps.keySet()) {
            if (!indexes.containsKey(node)) {
                strongConnect(node, deps, index, indexes, lowLinks,
                        stack, onStack, components);
            }
        }

        var declarationOrder = new HashMap<String, Integer>();
        int position = 0;
        for (String node : deps.keySet()) declarationOrder.put(node, position++);
        components.forEach(component -> component.sort(
                Comparator.comparingInt(declarationOrder::get)));

        var componentOf = new HashMap<String, Integer>();
        for (int i = 0; i < components.size(); i++) {
            for (String node : components.get(i)) componentOf.put(node, i);
        }

        var componentDeps = new ArrayList<Set<Integer>>();
        var inDegree = new int[components.size()];
        for (int i = 0; i < components.size(); i++) componentDeps.add(new LinkedHashSet<>());
        for (var entry : deps.entrySet()) {
            int owner = componentOf.get(entry.getKey());
            for (String dependency : entry.getValue()) {
                int target = componentOf.get(dependency);
                if (owner != target && componentDeps.get(owner).add(target)) {
                    inDegree[owner]++;
                }
            }
        }

        Comparator<Integer> byDeclarationOrder = Comparator.comparingInt(component ->
                declarationOrder.get(components.get(component).getFirst()));
        var ready = new PriorityQueue<>(byDeclarationOrder);
        for (int i = 0; i < inDegree.length; i++) {
            if (inDegree[i] == 0) ready.add(i);
        }

        var result = new ArrayList<List<String>>();
        while (!ready.isEmpty()) {
            int completed = ready.remove();
            result.add(components.get(completed));
            for (int owner = 0; owner < componentDeps.size(); owner++) {
                if (componentDeps.get(owner).contains(completed)
                        && --inDegree[owner] == 0) {
                    ready.add(owner);
                }
            }
        }
        if (result.size() != components.size()) {
            throw new IllegalStateException("SCC condensation graph must be acyclic");
        }
        return result;
    }

    /**
     * Tarjan's visit from {@code root}, with the depth-first path on a heap stack instead of the Java stack, so that
     * a long chain of types each naming the next cannot exhaust it (issue #327). It visits dependencies in the order
     * the recursive form did, so the components and their members come out in the same order.
     */
    private static void strongConnect(
            String root,
            Map<String, Set<String>> deps,
            int[] nextIndex,
            Map<String, Integer> indexes,
            Map<String, Integer> lowLinks,
            ArrayDeque<String> stack,
            Set<String> onStack,
            List<List<String>> components) {
        record Frame(String node, Iterator<String> dependencies) {}
        var path = new ArrayDeque<Frame>();
        enter(root, nextIndex, indexes, lowLinks, stack, onStack);
        path.push(new Frame(root, deps.getOrDefault(root, Set.of()).iterator()));
        while (!path.isEmpty()) {
            var frame = path.peek();
            String node = frame.node();
            if (frame.dependencies().hasNext()) {
                String dependency = frame.dependencies().next();
                if (!indexes.containsKey(dependency)) {
                    enter(dependency, nextIndex, indexes, lowLinks, stack, onStack);
                    path.push(new Frame(dependency, deps.getOrDefault(dependency, Set.of()).iterator()));
                } else if (onStack.contains(dependency)) {
                    lowLinks.put(node, Math.min(lowLinks.get(node), indexes.get(dependency)));
                }
                continue;
            }
            if (lowLinks.get(node).equals(indexes.get(node))) {
                var component = new ArrayList<String>();
                String member;
                do {
                    member = stack.pop();
                    onStack.remove(member);
                    component.add(member);
                } while (!member.equals(node));
                components.add(component);
            }
            path.pop();
            if (!path.isEmpty()) {
                String caller = path.peek().node();
                lowLinks.put(caller, Math.min(lowLinks.get(caller), lowLinks.get(node)));
            }
        }
    }

    private static void enter(String node, int[] nextIndex, Map<String, Integer> indexes, Map<String, Integer> lowLinks,
                              ArrayDeque<String> stack, Set<String> onStack) {
        int nodeIndex = nextIndex[0]++;
        indexes.put(node, nodeIndex);
        lowLinks.put(node, nodeIndex);
        stack.push(node);
        onStack.add(node);
    }

    /** The members of a registered recursive {@code group} that have no finite base constructor. */
    public static List<String> unproductive(List<String> group, TypeRegistry registry) {
        var definitions = registry.namedDefinitions();
        var productive = new LinkedHashSet<String>();
        boolean changed;
        do {
            changed = false;
            for (String fqcn : group) {
                if (!productive.contains(fqcn)
                        && isProductive(definitions.get(fqcn), productive)) {
                    productive.add(fqcn);
                    changed = true;
                }
            }
        } while (changed);
        return group.stream()
                .filter(name -> !productive.contains(name))
                .toList();
    }

    private static boolean isProductive(PirType type, Set<String> productive) {
        if (type == null) return false;
        return switch (type) {
            case PirType.NamedTypeRef ref -> productive.contains(ref.stableId());
            case PirType.RecordType record -> record.fields().stream()
                    .allMatch(field -> isProductiveField(field.type(), productive));
            case PirType.SumType sum -> sum.constructors().stream()
                    .anyMatch(constructor -> constructor.fields().stream()
                            .allMatch(field -> isProductiveField(field.type(), productive)));
            default -> true;
        };
    }

    private static boolean isProductiveField(PirType type, Set<String> productive) {
        // Empty/None container values are finite regardless of their element type.
        if (type instanceof PirType.ListType
                || type instanceof PirType.MapType
                || type instanceof PirType.OptionalType) {
            return true;
        }
        return isProductive(type, productive);
    }
}
