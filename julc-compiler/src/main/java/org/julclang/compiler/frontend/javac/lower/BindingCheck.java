package org.julclang.compiler.frontend.javac.lower;

import org.julclang.compiler.pir.PirHelpers;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.core.recursion.Trampoline;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

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
        new BindingCheck(provenance).walk(program).run();
    }

    /** The walk, stack-safe (issue #327): a program is as deep as it is long. Terms are visited in the recursive form's order. */
    private Trampoline<Void> walk(PirTerm term) {
        return Trampoline.defer(() -> switch (term) {
            case PirTerm.Var v -> {
                resolve(v);
                yield DONE;
            }
            case PirTerm.Let let -> walk(let.value()).then(ignored -> within(List.of(let.name()), let, () -> walk(let.body())));
            case PirTerm.LetRec rec -> within(rec.bindings().stream().map(PirTerm.Binding::name).toList(), rec, () ->
                    all(rec.bindings(), binding -> walk(binding.value())).then(ignored -> walk(rec.body())));
            case PirTerm.Lam lam -> within(List.of(lam.param()), lam, () -> walk(lam.body()));
            case PirTerm.App app -> walk(app.function()).then(ignored -> walk(app.argument()));
            case PirTerm.Const ignored -> DONE;
            case PirTerm.Builtin ignored -> DONE;
            case PirTerm.IfThenElse branch -> walk(branch.cond()).then(ignored -> walk(branch.thenBranch()))
                    .then(ignored -> walk(branch.elseBranch()));
            case PirTerm.IntegerCase select -> walk(select.scrutinee()).then(ignored -> all(select.branches(), this::walk));
            case PirTerm.PairMatch pair -> walk(pair.scrutinee())
                    .then(ignored -> within(List.of(pair.firstName(), pair.secondName()), pair, () -> walk(pair.body())));
            case PirTerm.ListMatch list -> walk(list.scrutinee()).then(ignored -> walk(list.nilBranch()))
                    .then(ignored -> within(List.of(list.headName(), list.tailName()), list, () -> walk(list.consBranch())));
            case PirTerm.DataConstr constr -> all(constr.fields(), this::walk);
            case PirTerm.DataMatch match -> walk(match.scrutinee()).then(ignored -> all(match.branches(), branch ->
                    within(branch.patternVar() == null ? List.of() : List.of(branch.patternVar()), branch,
                            () -> within(branch.bindings(), branch, () -> walk(branch.body())))));
            case PirTerm.Error ignored -> DONE;
            case PirTerm.Trace trace -> walk(trace.message()).then(ignored -> walk(trace.body()));
        });
    }

    private static final Trampoline<Void> DONE = Trampoline.done(null);

    private static <T> Trampoline<Void> all(List<T> items, Function<T, Trampoline<Void>> step) {
        return Trampoline.each(items, step).map(ignored -> null);
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

    /**
     * {@code body} with {@code names} bound by {@code binder}. A failure ends the check, so the names need no unbinding
     * after one.
     */
    private Trampoline<Void> within(List<String> names, Object binder, Supplier<Trampoline<Void>> body) {
        return Trampoline.defer(() -> {
            for (String name : names) scope.computeIfAbsent(name, key -> new ArrayDeque<>()).push(binder);
            return body.get().map(ignored -> {
                for (String name : names) scope.get(name).pop();
                return null;
            });
        });
    }
}
