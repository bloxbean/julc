package org.julclang.compiler.backend;

import org.julclang.compiler.pir.PirTerm;

import java.util.*;

/** Checks lexical closure, including recursive and pattern scopes. Not a full PIR type verifier. */
public final class PirClosure {
    private PirClosure() {}

    public static void check(PirTerm term) {
        visit(term, Set.of());
    }

    public static Set<String> freeVariables(PirTerm term) {
        var found = new LinkedHashSet<String>();
        collect(term, Set.of(), found);
        return found;
    }

    private static void visit(PirTerm term, Set<String> scope) {
        var free = new LinkedHashSet<String>();
        collect(term, scope, free);
        if (!free.isEmpty()) throw new IllegalArgumentException("Unbound PIR symbols: " + free);
    }

    /** A step of the walk: a term to visit, or names entering or leaving scope. */
    private sealed interface Step {
        record Visit(PirTerm term) implements Step {}

        record Enter(List<String> names) implements Step {}

        record Exit(List<String> names) implements Step {}
    }

    /**
     * Adds the names {@code t} reads that are not in scope, in the order a left-to-right walk meets them. The walk keeps
     * its pending steps on a heap stack, as a term can nest as deeply as a program is long (issue #327, ADR-074), and
     * counts how many binders hold each name instead of copying the scope at every binder.
     */
    private static void collect(PirTerm t, Set<String> s, Set<String> free) {
        var bound = new HashMap<String, Integer>();
        for (String name : s) bound.put(name, 1);
        var pending = new ArrayDeque<Step>();
        pending.push(new Step.Visit(t));
        while (!pending.isEmpty()) {
            switch (pending.pop()) {
                case Step.Enter enter -> enter.names().forEach(name -> bound.merge(name, 1, Integer::sum));
                case Step.Exit exit -> exit.names().forEach(name -> bound.computeIfPresent(name,
                        (key, count) -> count == 1 ? null : count - 1));
                case Step.Visit visit -> {
                    // Steps are pushed last-first, so that they run in order.
                    var steps = new ArrayList<Step>();
                    visit(visit.term(), bound, free, steps);
                    for (int i = steps.size() - 1; i >= 0; i--) pending.push(steps.get(i));
                }
            }
        }
    }

    /** Visits one term: records a free read, or lists the steps of its children, in order. */
    private static void visit(PirTerm t, Map<String, Integer> bound, Set<String> free, List<Step> steps) {
        switch (t) {
            case PirTerm.Var v -> {
                if (!bound.containsKey(v.name())) free.add(v.name());
            }
            case PirTerm.Lam l -> scoped(steps, List.of(l.param()), l.body());
            case PirTerm.Let l -> {
                steps.add(new Step.Visit(l.value()));
                scoped(steps, List.of(l.name()), l.body());
            }
            case PirTerm.LetRec l -> {
                var names = l.bindings().stream().map(PirTerm.Binding::name).toList();
                steps.add(new Step.Enter(names));
                l.bindings().forEach(b -> steps.add(new Step.Visit(b.value())));
                steps.add(new Step.Visit(l.body()));
                steps.add(new Step.Exit(names));
            }
            case PirTerm.App a -> {
                steps.add(new Step.Visit(a.function()));
                steps.add(new Step.Visit(a.argument()));
            }
            case PirTerm.IfThenElse i -> {
                steps.add(new Step.Visit(i.cond()));
                steps.add(new Step.Visit(i.thenBranch()));
                steps.add(new Step.Visit(i.elseBranch()));
            }
            case PirTerm.DataConstr c -> c.fields().forEach(f -> steps.add(new Step.Visit(f)));
            case PirTerm.DataMatch m -> {
                steps.add(new Step.Visit(m.scrutinee()));
                for (var b : m.branches()) {
                    var names = new ArrayList<>(b.bindings());
                    if (b.patternVar() != null) names.add(b.patternVar());
                    scoped(steps, names, b.body());
                }
            }
            case PirTerm.ListMatch m -> {
                steps.add(new Step.Visit(m.scrutinee()));
                steps.add(new Step.Visit(m.nilBranch()));
                scoped(steps, List.of(m.headName(), m.tailName()), m.consBranch());
            }
            case PirTerm.PairMatch m -> {
                steps.add(new Step.Visit(m.scrutinee()));
                scoped(steps, List.of(m.firstName(), m.secondName()), m.body());
            }
            case PirTerm.IntegerCase c -> {
                steps.add(new Step.Visit(c.scrutinee()));
                c.branches().forEach(b -> steps.add(new Step.Visit(b)));
            }
            case PirTerm.Trace t1 -> {
                steps.add(new Step.Visit(t1.message()));
                steps.add(new Step.Visit(t1.body()));
            }
            case PirTerm.Const _, PirTerm.Builtin _, PirTerm.Error _ -> {}
        }
    }

    /** {@code body} visited with {@code names} in scope. */
    private static void scoped(List<Step> steps, List<String> names, PirTerm body) {
        steps.add(new Step.Enter(names));
        steps.add(new Step.Visit(body));
        steps.add(new Step.Exit(names));
    }
}
