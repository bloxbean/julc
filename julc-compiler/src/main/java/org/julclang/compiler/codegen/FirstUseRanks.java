package org.julclang.compiler.codegen;

import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.resolve.LibraryMethodRegistry.LibraryMethod;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The first-use rank of every library method a program binds (ADR-078 D1). {@link LibraryBindingOrder} breaks a tie
 * between methods with equal content keys by this rank instead of their qualified names.
 * <p>
 * The ranks follow one walk per artifact:
 * <ul>
 *   <li><b>D1a.</b> The root: the static initialisers in textual order, then the entry points (by purpose tag under
 *       auto-dispatch; the call of the target under {@code compileMethod}). The walk is pre-order and left to right,
 *       in {@link LibraryBindingOrder#children}'s order, the order the content key walks. The first reference to a method not yet ranked gives it the next
 *       rank, and its body is walked there, before the rest of the term. A method is ranked before its body is walked,
 *       so recursion ends.</li>
 *   <li><b>D1a′.</b> The helpers the root walk did not reach, in the order the assembly receives them (declaration
 *       order), each walked once.</li>
 *   <li><b>D1b.</b> The library methods still unranked: repeatedly, among those no unranked library method calls
 *       (self-calls ignored), the one with the smallest content key is ranked and walked. Where several share that key,
 *       the qualified name decides; where every one is called by another (a cycle, which the backend rejects), the
 *       smallest by content key and name is taken.</li>
 * </ul>
 * A reference resolves lexically, to the binding its name has where it occurs: a local first, then, outside a library
 * body, a validator helper, then a library method. A helper and a library copy of the same name (a root compiled as its
 * own library) are two bindings. A static initialiser is bound outside the helpers, so it sees only library methods.
 * The walk uses a work list per walked body, never the Java stack (ADR-074).
 */
final class FirstUseRanks {

    private FirstUseRanks() {
    }

    /**
     * What the root walk visits: static initialisers, then entry points, and the validator's helpers. The {@code @Param}
     * decoders are not walked: each is {@code PirHelpers.wrapDecode} over the raw argument's variable, and holds no
     * reference to a method (ADR-078 D1a).
     */
    record Roots(List<PirTerm> statics, List<PirTerm> entries, List<ValidatorAssembly.Method> helpers) {

        /** No root: every library method is ranked by D1b. */
        static final Roots NONE = new Roots(List.of(), List.of(), List.of());

        Roots {
            statics = List.copyOf(statics);
            entries = List.copyOf(entries);
            helpers = List.copyOf(helpers);
        }
    }

    /** The rule that ranked a method (ADR-078 D5's instrumentation). */
    enum Rule {
        /** Reached from the root (D1a), or from an unread helper (D1a′), at its first use. */
        FIRST_USE,
        /** A helper the root walk did not reach (D1a′). */
        UNREAD_HELPER,
        /** D1b, by content key: no other candidate shared the key. */
        UNREAD_BY_KEY,
        /**
         * D1b, where candidates shared the smallest key: the qualified name decided. Only the methods taken while another
         * candidate with the same key was still waiting are labelled so; the last of a tied group, taken when no other
         * remained, is labelled {@link #UNREAD_BY_KEY}.
         */
        UNREAD_BY_NAME,
        /** D1b's cycle fallback: every unranked method was called by another. */
        CYCLE
    }

    /** One ranking step: which binding got which rank, and why. */
    record Selection(String name, boolean helper, int rank, Rule rule) {
    }

    /**
     * The rank of each library method, by qualified name.
     *
     * @param methods      the library methods, by qualified name
     * @param keys         their content keys
     * @param dependencies the library methods each one calls, itself excluded
     * @param observer     told each selection, in rank order, or null
     */
    static Map<String, Integer> of(Roots roots, Map<String, LibraryMethod> methods, Map<String, String> keys,
                                   Map<String, Set<String>> dependencies, Consumer<Selection> observer) {
        var walk = new Walk(roots.helpers(), methods, observer);
        // D1a: the static initialisers see only library methods; the entry points see the helpers too.
        for (var value : roots.statics()) walk.walk(value, true);
        for (var entry : roots.entries()) walk.walk(entry, false);
        // D1a′: the helpers the root did not reach, in the order the assembly received them.
        for (var helper : roots.helpers()) {
            if (!walk.helperRanks.containsKey(helper.name())) walk.rankHelper(helper.name(), Rule.UNREAD_HELPER);
        }
        unread(walk, methods, keys, dependencies);
        return walk.libraryRanks;
    }

    /** D1b: the library methods no walk reached, by content key, each walked as it is ranked. */
    private static void unread(Walk walk, Map<String, LibraryMethod> methods, Map<String, String> keys,
                               Map<String, Set<String>> dependencies) {
        if (walk.libraryRanks.size() == methods.size()) return;
        Comparator<String> canonical = Comparator.<String, String>comparing(keys::get).thenComparing(Comparator.naturalOrder());
        // Each unranked method's count of unranked library callers; a method is a candidate when it has none.
        var callers = new HashMap<String, Integer>();
        for (var entry : dependencies.entrySet()) {
            if (walk.libraryRanks.containsKey(entry.getKey())) continue;
            for (var callee : entry.getValue()) callers.merge(callee, 1, Integer::sum);
        }
        var candidates = new PriorityQueue<>(canonical);
        for (var name : methods.keySet()) {
            if (!walk.libraryRanks.containsKey(name) && callers.getOrDefault(name, 0) == 0) candidates.add(name);
        }
        // Ranking a method removes it as a caller: its callees may become candidates.
        walk.onLibraryRanked = name -> {
            for (var callee : dependencies.get(name)) {
                if (callers.merge(callee, -1, Integer::sum) == 0 && !walk.libraryRanks.containsKey(callee)) {
                    candidates.add(callee);
                }
            }
        };
        // The cycle fallback reads one presorted sequence with a cursor that only moves forward.
        var sorted = new ArrayList<>(methods.keySet());
        sorted.sort(canonical);
        int cursor = 0;
        while (walk.libraryRanks.size() < methods.size()) {
            String next = poll(candidates, walk);
            Rule rule;
            if (next != null) {
                String following = peek(candidates, walk);
                rule = following != null && keys.get(following).equals(keys.get(next)) ? Rule.UNREAD_BY_NAME : Rule.UNREAD_BY_KEY;
            } else {
                while (walk.libraryRanks.containsKey(sorted.get(cursor))) cursor++;
                next = sorted.get(cursor);
                rule = Rule.CYCLE;
            }
            walk.rankLibrary(next, rule);
        }
    }

    private static String poll(PriorityQueue<String> candidates, Walk walk) {
        while (!candidates.isEmpty()) {
            String name = candidates.poll();
            if (!walk.libraryRanks.containsKey(name)) return name;
        }
        return null;
    }

    private static String peek(PriorityQueue<String> candidates, Walk walk) {
        while (!candidates.isEmpty() && walk.libraryRanks.containsKey(candidates.peek())) candidates.poll();
        return candidates.peek();
    }

    /** Pending work of one walked body: a term, or a change of the names bound in it. */
    private sealed interface Step {
        record Visit(PirTerm term) implements Step {}

        record Bind(List<String> names) implements Step {}

        record Unbind(List<String> names) implements Step {}
    }

    /** One body being walked: its pending steps, its locals, and whether helpers are in scope. */
    private record Frame(ArrayDeque<Step> steps, Map<String, Integer> locals, boolean library) {
    }

    /** The shared state of one artifact's walk: the ranks so far and the bodies still to walk. */
    private static final class Walk {
        final Map<String, PirTerm> helperBodies = new LinkedHashMap<>();
        final Map<String, LibraryMethod> methods;
        final Consumer<Selection> observer;
        final Map<String, Integer> helperRanks = new HashMap<>();
        final Map<String, Integer> libraryRanks = new HashMap<>();
        final ArrayDeque<Frame> frames = new ArrayDeque<>();
        Consumer<String> onLibraryRanked = name -> { };
        int next;

        Walk(Collection<ValidatorAssembly.Method> helpers, Map<String, LibraryMethod> methods, Consumer<Selection> observer) {
            for (var helper : helpers) helperBodies.putIfAbsent(helper.name(), helper.body());
            this.methods = methods;
            this.observer = observer;
        }

        void rankHelper(String name, Rule rule) {
            helperRanks.put(name, next);
            if (observer != null) observer.accept(new Selection(name, true, next, rule));
            next++;
            walk(helperBodies.get(name), false);
        }

        void rankLibrary(String name, Rule rule) {
            libraryRanks.put(name, next);
            if (observer != null) observer.accept(new Selection(name, false, next, rule));
            next++;
            onLibraryRanked.accept(name);
            walk(methods.get(name).body(), true);
        }

        /** Walks {@code term} to the end, and every body it reaches first, each where it is reached. */
        void walk(PirTerm term, boolean library) {
            int depth = frames.size();
            push(term, library);
            while (frames.size() > depth) {
                var frame = frames.peek();
                if (frame.steps().isEmpty()) {
                    frames.pop();
                    continue;
                }
                switch (frame.steps().pop()) {
                    case Step.Bind bind -> bind.names().forEach(name -> frame.locals().merge(name, 1, Integer::sum));
                    case Step.Unbind unbind -> unbind.names().forEach(name -> frame.locals().merge(name, -1, Integer::sum));
                    case Step.Visit visit -> visit(frame, visit.term());
                }
            }
        }

        private void push(PirTerm term, boolean library) {
            var steps = new ArrayDeque<Step>();
            steps.push(new Step.Visit(term));
            frames.push(new Frame(steps, new HashMap<>(), library));
        }

        /** Resolves a variable, ranking the method it names at its first use; otherwise schedules the children. */
        private void visit(Frame frame, PirTerm term) {
            if (term instanceof PirTerm.Var variable) {
                String name = variable.name();
                if (frame.locals().getOrDefault(name, 0) > 0) return;
                if (!frame.library() && helperBodies.containsKey(name)) {
                    if (!helperRanks.containsKey(name)) {
                        // Ranked here: the helper's body is pushed above this frame and walked before its rest.
                        helperRanks.put(name, next);
                        if (observer != null) observer.accept(new Selection(name, true, next, Rule.FIRST_USE));
                        next++;
                        push(helperBodies.get(name), false);
                    }
                    return;
                }
                if (methods.containsKey(name) && !libraryRanks.containsKey(name)) {
                    libraryRanks.put(name, next);
                    if (observer != null) observer.accept(new Selection(name, false, next, Rule.FIRST_USE));
                    next++;
                    onLibraryRanked.accept(name);
                    push(methods.get(name).body(), true);
                }
                return;
            }
            var children = new ArrayList<Step>();
            LibraryBindingOrder.children(term, child -> children.add(new Step.Visit(child)), (names, body) -> {
                children.add(new Step.Bind(names));
                children.add(new Step.Visit(body));
                children.add(new Step.Unbind(names));
            });
            for (int i = children.size() - 1; i >= 0; i--) frame.steps().push(children.get(i));
        }
    }

    /** The visitor {@link LibraryBindingOrder#children} reports a child under new binders to. */
    interface Bound {
        void child(List<String> names, PirTerm body);
    }
}
