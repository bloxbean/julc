package org.julclang.compiler.frontend.javac.lower;

import org.julclang.compiler.pir.PirHelpers;
import org.julclang.compiler.pir.PirTerm;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ADR-068 I3 and I13's check, enabled in tests: every {@code Var} of a program was built for a source element and
 * resolves, by PIR's name scoping, to a binder built for that element. Provenance comes from {@link Terms}, recorded as
 * each term was built, so nothing here reads a name to decide what it should mean. The exceptions are names no source
 * variable can have: a reserved {@code #} name, and a qualified method binder or {@code .} member. Walking the term as a
 * tree visits every copy of a duplicated term, in each copy's own scope.
 */
public final class BindingCheck {

    private final Provenance provenance;
    /** Each name's binders in scope, innermost first: the node that binds it. */
    private final Map<String, Deque<Object>> scope = new HashMap<>();

    private BindingCheck(Provenance provenance) {
        this.provenance = provenance;
    }

    /** @throws IllegalStateException for the first {@code Var} with no provenance, or one that resolves wrongly */
    public static void check(PirTerm program, Provenance provenance) {
        new BindingCheck(provenance).walk(program);
    }

    private void walk(PirTerm term) {
        switch (term) {
            case PirTerm.Var v -> resolve(v);
            case PirTerm.Let let -> {
                walk(let.value());
                within(List.of(let.name()), let, () -> walk(let.body()));
            }
            case PirTerm.LetRec rec -> within(rec.bindings().stream().map(PirTerm.Binding::name).toList(), rec, () -> {
                rec.bindings().forEach(binding -> walk(binding.value()));
                walk(rec.body());
            });
            case PirTerm.Lam lam -> within(List.of(lam.param()), lam, () -> walk(lam.body()));
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
                within(List.of(pair.firstName(), pair.secondName()), pair, () -> walk(pair.body()));
            }
            case PirTerm.ListMatch list -> {
                walk(list.scrutinee());
                walk(list.nilBranch());
                within(List.of(list.headName(), list.tailName()), list, () -> walk(list.consBranch()));
            }
            case PirTerm.DataConstr constr -> constr.fields().forEach(this::walk);
            case PirTerm.DataMatch match -> {
                walk(match.scrutinee());
                for (var branch : match.branches()) {
                    within(branch.patternVar() == null ? List.of() : List.of(branch.patternVar()), branch,
                            () -> within(branch.bindings(), branch, () -> walk(branch.body())));
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
        var element = provenance.readOf(v);
        if (element == null) {
            if (PirHelpers.isGeneratedName(v.name()) || v.name().indexOf('.') >= 0) return;
            throw new IllegalStateException("I13: '" + v.name() + "' was not built for a source element");
        }
        var binders = scope.get(v.name());
        var binder = binders == null ? null : binders.peek();
        if (binder == null) {
            throw new IllegalStateException("I13: '" + v.name() + "', built for " + element + ", has no binder in scope");
        }
        var bound = provenance.binderOf(binder, v.name());
        if (!element.equals(bound)) {
            throw new IllegalStateException("I13: '" + v.name() + "', built for " + element + ", resolves to a binder of "
                    + (bound == null ? "a generated name" : bound));
        }
    }

    private void within(List<String> names, Object binder, Runnable body) {
        for (String name : names) scope.computeIfAbsent(name, key -> new ArrayDeque<>()).push(binder);
        try {
            body.run();
        } finally {
            for (String name : names) scope.get(name).pop();
        }
    }
}
