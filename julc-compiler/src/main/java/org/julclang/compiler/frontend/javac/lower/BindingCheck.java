package org.julclang.compiler.frontend.javac.lower;

import org.julclang.compiler.pir.PirTerm;

import javax.lang.model.element.Element;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * ADR-068 I3 and I13's check, enabled in tests: every {@code Var} built for a source element resolves, by PIR's name
 * scoping, to a binder built for that element. Provenance comes from {@link Terms}, recorded as each term was built, so
 * nothing here reads a name to decide what it should mean. Walking the term as a tree visits every copy of a duplicated
 * term, in each copy's own scope.
 */
public final class BindingCheck {

    /** A binder with no term of its own, or none built for a source element. */
    private static final Object GENERATED = new Object();

    private final IdentityHashMap<PirTerm, Element> provenance;
    private final Map<String, Deque<Object>> scope = new HashMap<>();

    private BindingCheck(IdentityHashMap<PirTerm, Element> provenance) {
        this.provenance = provenance;
    }

    /** @throws IllegalStateException for the first {@code Var} that resolves to another element's binder, or to none */
    public static void check(PirTerm program, IdentityHashMap<PirTerm, Element> provenance) {
        new BindingCheck(provenance).walk(program);
    }

    private void walk(PirTerm term) {
        switch (term) {
            case PirTerm.Var v -> resolve(v);
            case PirTerm.Let let -> {
                walk(let.value());
                within(let.name(), let, () -> walk(let.body()));
            }
            case PirTerm.LetRec rec -> withinAll(rec.bindings().stream().map(PirTerm.Binding::name).toList(), rec, () -> {
                rec.bindings().forEach(binding -> walk(binding.value()));
                walk(rec.body());
            });
            case PirTerm.Lam lam -> within(lam.param(), lam, () -> walk(lam.body()));
            case PirTerm.App app -> {
                walk(app.function());
                walk(app.argument());
            }
            case PirTerm.Const ignored -> { }
            case PirTerm.Builtin ignored -> { }
            case PirTerm.IfThenElse branch -> {
                walk(branch.cond());
                walk(branch.thenBranch());
                walk(branch.elseBranch());
            }
            case PirTerm.IntegerCase select -> {
                walk(select.scrutinee());
                select.branches().forEach(this::walk);
            }
            case PirTerm.PairMatch pair -> {
                walk(pair.scrutinee());
                withinAll(List.of(pair.firstName(), pair.secondName()), pair, () -> walk(pair.body()));
            }
            case PirTerm.ListMatch list -> {
                walk(list.scrutinee());
                walk(list.nilBranch());
                withinAll(List.of(list.headName(), list.tailName()), list, () -> walk(list.consBranch()));
            }
            case PirTerm.DataConstr constr -> constr.fields().forEach(this::walk);
            case PirTerm.DataMatch match -> {
                walk(match.scrutinee());
                for (var branch : match.branches()) {
                    within(branch.patternVar(), match, () -> withinAll(branch.bindings(), match, () -> walk(branch.body())));
                }
            }
            case PirTerm.Error ignored -> { }
            case PirTerm.Trace trace -> {
                walk(trace.message());
                walk(trace.body());
            }
        }
    }

    private void resolve(PirTerm.Var v) {
        var element = provenance.get(v);
        if (element == null) return;
        var binders = scope.get(v.name());
        var binder = binders == null ? null : binders.peek();
        if (binder == null) {
            throw new IllegalStateException("I13: '" + v.name() + "', built for " + element + ", has no binder in scope");
        }
        var bound = binder instanceof PirTerm term ? provenance.get(term) : null;
        if (!element.equals(bound)) {
            throw new IllegalStateException("I13: '" + v.name() + "', built for " + element + ", resolves to a binder of "
                    + (bound == null ? "a generated name" : bound));
        }
    }

    private void within(String name, PirTerm binder, Runnable body) {
        if (name == null) {
            body.run();
            return;
        }
        var binders = scope.computeIfAbsent(name, k -> new ArrayDeque<>());
        binders.push(provenance.containsKey(binder) ? binder : GENERATED);
        try {
            body.run();
        } finally {
            binders.pop();
        }
    }

    private void withinAll(List<String> names, PirTerm binder, Runnable body) {
        if (names.isEmpty()) {
            body.run();
            return;
        }
        within(names.getFirst(), binder, () -> withinAll(names.subList(1, names.size()), binder, body));
    }
}
