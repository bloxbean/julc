package org.julclang.compiler.pir;

import org.julclang.core.recursion.Trampoline;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

/**
 * Capture-avoiding substitution for PIR terms.
 *
 * <p>Used by the multi-binding LetRec transformation (Bekic's theorem) to replace free occurrences
 * of a variable with a replacement term.
 */
public final class PirSubstitution {

    private PirSubstitution() {}

    /**
     * Replace all free occurrences of {@code varName} in {@code term} with {@code replacement}.
     * Respects variable shadowing: if a binding introduces a variable with the same name, the
     * replacement does NOT occur inside that binding's body.
     * <p>
     * Every walk here is stack-safe (issue #327, ADR-074): a term nests as deeply as a program is long.
     */
    public static PirTerm substitute(PirTerm term, String varName, PirTerm replacement) {
        if (!collectFreeVarNames(term).contains(varName)) return term;
        var hygiene = new Hygiene(collectFreeVarNames(replacement));
        hygiene.used.add(varName);
        hygiene.rewrite(term);
        hygiene.rewrite(replacement);
        hygiene.collecting = false;
        return substituteUnchecked(hygiene.rewrite(term), varName, replacement).run();
    }

    private static Trampoline<PirTerm> substituteUnchecked(PirTerm term, String varName, PirTerm replacement) {
        return Trampoline.defer(() -> switch (term) {
            case PirTerm.Var(var name, var type) -> Trampoline.done(name.equals(varName) ? replacement : term);

            case PirTerm.Const _, PirTerm.Builtin _, PirTerm.Error _ -> Trampoline.done(term);

            // If Let binds the same name, it shadows — don't substitute in body
            case PirTerm.Let(var name, var value, var body) -> substituteUnchecked(value, varName, replacement)
                    .then(newValue -> (name.equals(varName)
                            ? Trampoline.done(body)
                            : substituteUnchecked(body, varName, replacement))
                            .map(newBody -> new PirTerm.Let(name, newValue, newBody)));

            // If lambda parameter shadows varName, don't substitute in body
            case PirTerm.Lam(var param, var paramType, var body) -> param.equals(varName)
                    ? Trampoline.done(term)
                    : substituteUnchecked(body, varName, replacement).map(newBody -> new PirTerm.Lam(param, paramType, newBody));

            // If any binding name matches varName, all bindings are in mutual scope —
            // varName is shadowed throughout the entire LetRec
            case PirTerm.LetRec(var bindings, var body) -> bindings.stream().anyMatch(b -> b.name().equals(varName))
                    ? Trampoline.done(term)
                    : PirHelpers.traverseChildren(term, child -> substituteUnchecked(child, varName, replacement));

            case PirTerm.PairMatch(var pair, var type, var first, var second, var body) ->
                    substituteUnchecked(pair, varName, replacement).then(newPair ->
                            (first.equals(varName) || second.equals(varName)
                                    ? Trampoline.done(body)
                                    : substituteUnchecked(body, varName, replacement))
                                    .map(newBody -> new PirTerm.PairMatch(newPair, type, first, second, newBody)));

            case PirTerm.ListMatch(var xs, var head, var tail, var nil, var cons) ->
                    substituteUnchecked(xs, varName, replacement).then(newXs ->
                            substituteUnchecked(nil, varName, replacement).then(newNil ->
                                    (head.equals(varName) || tail.equals(varName)
                                            ? Trampoline.done(cons)
                                            : substituteUnchecked(cons, varName, replacement))
                                            .map(newCons -> new PirTerm.ListMatch(newXs, head, tail, newNil, newCons))));

            // If varName is bound in a branch's bindings, don't substitute in its body
            case PirTerm.DataMatch(var scrutinee, var branches) -> substituteUnchecked(scrutinee, varName, replacement)
                    .then(newScrutinee -> Trampoline.each(branches, branch -> {
                        boolean boundInBranch = branch.bindings().contains(varName) || varName.equals(branch.patternVar());
                        return (boundInBranch
                                ? Trampoline.done(branch.body())
                                : substituteUnchecked(branch.body(), varName, replacement))
                                .map(newBody -> new PirTerm.MatchBranch(branch.constructorName(), branch.bindings(),
                                        branch.bindingTypes(), newBody, branch.patternVar()));
                    }).map(newBranches -> new PirTerm.DataMatch(newScrutinee, newBranches)));

            // The remaining forms bind nothing.
            case PirTerm.App _, PirTerm.IfThenElse _, PirTerm.DataConstr _, PirTerm.IntegerCase _, PirTerm.Trace _ ->
                    PirHelpers.traverseChildren(term, child -> substituteUnchecked(child, varName, replacement));
        });
    }

    /**
     * Rename conflicting binders, retaining each variable's original PIR type.
     * <p>
     * The names in scope are one map, extended on entering a binder and restored on leaving it, rather than a copy per
     * binder: a deep term would otherwise copy a map per level. Each binder's name is chosen ({@link #bind}) where the
     * recursive form chose it, so names are drawn in the same order.
     */
    private static final class Hygiene {
        private final Set<String> forbidden;
        private final Set<String> used = new LinkedHashSet<>();
        private final Map<String, String> scope = new HashMap<>();
        private boolean collecting = true;
        private int next;

        private Hygiene(Set<String> forbidden) {
            this.forbidden = forbidden;
        }

        /** The name {@code name}'s binder gets, drawn now; it is visible once {@link #enter}ed. */
        private String bind(String name) {
            used.add(name);
            String renamed = name;
            if (!collecting && forbidden.contains(name)) {
                do {
                    renamed = "#$pir$subst$" + next++;
                } while (!used.add(renamed));
            }
            return renamed;
        }

        /** Brings {@code name}, renamed, into scope; the returned action restores what it hid. */
        private Runnable enter(String name, String renamed) {
            boolean had = scope.containsKey(name);
            String previous = scope.put(name, renamed);
            return () -> {
                if (had) scope.put(name, previous);
                else scope.remove(name);
            };
        }

        /** Runs {@code undo} in reverse, as leaving nested scopes does. */
        private static void leave(List<Runnable> undo) {
            for (int i = undo.size() - 1; i >= 0; i--) undo.get(i).run();
        }

        private PirTerm rewrite(PirTerm term) {
            scope.clear();
            return walk(term).run();
        }

        private Trampoline<PirTerm> walk(PirTerm term) {
            return Trampoline.defer(() -> switch (term) {
                case PirTerm.Var(var name, var type) -> {
                    used.add(name);
                    // Keep unrenamed occurrences identical: source maps and Java debug
                    // provenance are keyed by PIR node identity.
                    var renamed = scope.get(name);
                    yield Trampoline.done(renamed == null || renamed.equals(name)
                            ? term
                            : new PirTerm.Var(renamed, type));
                }
                case PirTerm.Const _, PirTerm.Builtin _, PirTerm.Error _ -> Trampoline.done(term);
                case PirTerm.Lam(var name, var type, var body) -> {
                    var renamed = bind(name);
                    var undo = enter(name, renamed);
                    yield walk(body).map(newBody -> {
                        undo.run();
                        return new PirTerm.Lam(renamed, type, newBody);
                    });
                }
                case PirTerm.Let(var name, var value, var body) -> {
                    var renamed = bind(name);
                    yield walk(value).then(newValue -> {
                        var undo = enter(name, renamed);
                        return walk(body).map(newBody -> {
                            undo.run();
                            return new PirTerm.Let(renamed, newValue, newBody);
                        });
                    });
                }
                case PirTerm.LetRec(var bindings, var body) -> {
                    var undo = new ArrayList<Runnable>();
                    for (var binding : bindings) undo.add(enter(binding.name(), bind(binding.name())));
                    var names = bindings.stream().map(b -> scope.get(b.name())).toList();
                    var indexes = IntStream.range(0, bindings.size()).boxed().toList();
                    yield Trampoline.each(indexes, i -> walk(bindings.get(i).value())
                                    .map(value -> new PirTerm.Binding(names.get(i), value)))
                            .then(newBindings -> walk(body).map(newBody -> {
                                leave(undo);
                                return new PirTerm.LetRec(newBindings, newBody);
                            }));
                }
                case PirTerm.PairMatch(var value, var type, var first, var second, var body) -> {
                    var a = bind(first);
                    var b = bind(second);
                    yield walk(value).then(newValue -> {
                        var undo = List.of(enter(first, a), enter(second, b));
                        return walk(body).map(newBody -> {
                            leave(undo);
                            return new PirTerm.PairMatch(newValue, type, a, b, newBody);
                        });
                    });
                }
                case PirTerm.ListMatch(var value, var head, var tail, var nil, var cons) -> {
                    var h = bind(head);
                    var t = bind(tail);
                    yield walk(value).then(newValue -> walk(nil).then(newNil -> {
                        var undo = List.of(enter(head, h), enter(tail, t));
                        return walk(cons).map(newCons -> {
                            leave(undo);
                            return new PirTerm.ListMatch(newValue, h, t, newNil, newCons);
                        });
                    }));
                }
                case PirTerm.DataMatch(var value, var branches) -> Trampoline.each(branches, branch -> {
                            var undo = new ArrayList<Runnable>();
                            var names = new ArrayList<String>();
                            for (var name : branch.bindings()) {
                                var renamed = bind(name);
                                undo.add(enter(name, renamed));
                                names.add(renamed);
                            }
                            String pattern = null;
                            if (branch.patternVar() != null) {
                                pattern = bind(branch.patternVar());
                                undo.add(enter(branch.patternVar(), pattern));
                            }
                            String patternName = pattern;
                            return walk(branch.body()).map(newBody -> {
                                leave(undo);
                                return new PirTerm.MatchBranch(branch.constructorName(), List.copyOf(names),
                                        branch.bindingTypes(), newBody, patternName);
                            });
                        })
                        // The scrutinee is renamed after the branches, as the recursive form did.
                        .then(renamed -> walk(value).map(newValue -> new PirTerm.DataMatch(newValue, renamed)));
                // The remaining forms bind nothing.
                case PirTerm.App _, PirTerm.IfThenElse _, PirTerm.DataConstr _, PirTerm.IntegerCase _, PirTerm.Trace _ ->
                        PirHelpers.traverseChildren(term, this::walk);
            });
        }
    }

    /**
     * Collect the names of all free variables in a PIR term, in the order a pre-order walk first
     * meets them. Used for dependency analysis in multi-binding LetRec.
     * <p>
     * A work list, not recursion (issue #327): each step either visits a term or enters or leaves a
     * binder, and the steps run in the recursive walk's order. The bound names are counted rather
     * than copied per binder.
     */
    public static Set<String> collectFreeVarNames(PirTerm term) {
        var free = new LinkedHashSet<String>();
        var bound = new HashMap<String, Integer>();
        var steps = new ArrayDeque<Object>();
        steps.push(term);
        while (!steps.isEmpty()) {
            switch (steps.pop()) {
                case Enter enter -> enter.names().forEach(name -> bound.merge(name, 1, Integer::sum));
                case Leave leave -> leave.names().forEach(name -> bound.computeIfPresent(name, (k, n) -> n == 1 ? null : n - 1));
                case PirTerm visit -> {
                    var order = new ArrayList<Object>();
                    switch (visit) {
                        case PirTerm.Var(var name, _) -> {
                            if (!bound.containsKey(name)) free.add(name);
                        }
                        case PirTerm.Const _, PirTerm.Builtin _, PirTerm.Error _ -> { }
                        case PirTerm.Let(var name, var value, var body) ->
                                order.addAll(List.of(value, new Enter(List.of(name)), body, new Leave(List.of(name))));
                        case PirTerm.Lam(var param, _, var body) ->
                                order.addAll(List.of(new Enter(List.of(param)), body, new Leave(List.of(param))));
                        case PirTerm.LetRec(var bindings, var body) -> {
                            var names = bindings.stream().map(PirTerm.Binding::name).toList();
                            order.add(new Enter(names));
                            for (var binding : bindings) order.add(binding.value());
                            order.add(body);
                            order.add(new Leave(names));
                        }
                        case PirTerm.PairMatch(var pair, _, var first, var second, var body) ->
                                order.addAll(List.of(pair, new Enter(List.of(first, second)), body,
                                        new Leave(List.of(first, second))));
                        case PirTerm.ListMatch(var xs, var head, var tail, var nil, var cons) ->
                                order.addAll(List.of(xs, nil, new Enter(List.of(head, tail)), cons,
                                        new Leave(List.of(head, tail))));
                        case PirTerm.DataMatch(var scrutinee, var branches) -> {
                            order.add(scrutinee);
                            for (var branch : branches) {
                                var names = new ArrayList<>(branch.bindings());
                                if (branch.patternVar() != null) names.add(branch.patternVar());
                                order.add(new Enter(names));
                                order.add(branch.body());
                                order.add(new Leave(names));
                            }
                        }
                        // The remaining forms bind nothing.
                        case PirTerm.App _, PirTerm.IfThenElse _, PirTerm.DataConstr _, PirTerm.IntegerCase _,
                             PirTerm.Trace _ -> order.addAll(PirHelpers.children(visit));
                    }
                    for (int i = order.size() - 1; i >= 0; i--) steps.push(order.get(i));
                }
                default -> throw new IllegalStateException("unknown step");
            }
        }
        return free;
    }

    /** A step of {@link #collectFreeVarNames}: names that a binder brings into scope. */
    private record Enter(List<String> names) {}

    /** A step of {@link #collectFreeVarNames}: names whose binder's scope ends. */
    private record Leave(List<String> names) {}
}
