package org.julclang.compiler.frontend.javac.lower;

import org.julclang.compiler.pir.PirTerm;
import org.julclang.core.Constant;
import org.julclang.core.DefaultFun;

import javax.lang.model.element.Element;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;

/**
 * ADR-069 I1's check, enabled in tests beside {@link BindingCheck}: an {@code instanceof} pattern variable is bound only
 * under its test. Each {@code let} that {@link Terms#introduced} recorded must be the then-branch of an {@code IfThenElse}
 * whose condition is {@code EqualsInteger(FstPair(UnConstrData(r)), k)}, where {@code r} is the operand the {@code let}
 * binds (the test's own operand, in the same copy of the term) and {@code k} the tag the pattern's type has. Every other binder of that variable (a loop's {@code let q = q}, an
 * accumulator's update, a join's parameter) is a rebinding, and must lie inside one of its introductions. Walking the term as a tree visits
 * every copy of a duplicated term in its own place.
 */
public final class PatternBindingCheck {

    private final Provenance provenance;
    /** How many introductions of each variable enclose the point being walked. */
    private final Map<Element, Integer> open = new HashMap<>();

    private PatternBindingCheck(Provenance provenance) {
        this.provenance = provenance;
    }

    /** @throws IllegalStateException for the first introduction out of place, or a rebinding outside every introduction */
    public static void check(PirTerm program, Provenance provenance) {
        new PatternBindingCheck(provenance).walk(program, null);
    }

    /** @param test the {@code IfThenElse} whose then-branch {@code term} is, or null */
    private void walk(PirTerm term, PirTerm.IfThenElse test) {
        switch (term) {
            case PirTerm.Let let -> let(let, test);
            case PirTerm.IfThenElse branch -> {
                walk(branch.cond(), null);
                walk(branch.thenBranch(), branch);
                walk(branch.elseBranch(), null);
            }
            case PirTerm.LetRec rec -> {
                rec.bindings().forEach(binding -> requireInside(rec, binding.name()));
                rec.bindings().forEach(binding -> walk(binding.value(), null));
                walk(rec.body(), null);
            }
            case PirTerm.Lam lam -> {
                requireInside(lam, lam.param());
                walk(lam.body(), null);
            }
            case PirTerm.App app -> {
                walk(app.function(), null);
                walk(app.argument(), null);
            }
            case PirTerm.IntegerCase select -> {
                walk(select.scrutinee(), null);
                select.branches().forEach(branch -> walk(branch, null));
            }
            case PirTerm.PairMatch pair -> {
                walk(pair.scrutinee(), null);
                requireInside(pair, pair.firstName());
                requireInside(pair, pair.secondName());
                walk(pair.body(), null);
            }
            case PirTerm.ListMatch list -> {
                walk(list.scrutinee(), null);
                walk(list.nilBranch(), null);
                requireInside(list, list.headName());
                requireInside(list, list.tailName());
                walk(list.consBranch(), null);
            }
            case PirTerm.DataConstr constr -> constr.fields().forEach(field -> walk(field, null));
            case PirTerm.DataMatch match -> {
                walk(match.scrutinee(), null);
                for (var branch : match.branches()) {
                    branch.bindings().forEach(name -> requireInside(branch, name));
                    if (branch.patternVar() != null) requireInside(branch, branch.patternVar());
                    walk(branch.body(), null);
                }
            }
            case PirTerm.Trace trace -> {
                walk(trace.message(), null);
                walk(trace.body(), null);
            }
            case PirTerm.Var ignored -> { }
            case PirTerm.Const ignored -> { }
            case PirTerm.Builtin ignored -> { }
            case PirTerm.Error ignored -> { }
        }
    }

    private void let(PirTerm.Let let, PirTerm.IfThenElse test) {
        walk(let.value(), null);
        var introduction = provenance.introductionOf(let);
        if (introduction != null) {
            var operand = test == null ? null : testedOperand(test.cond(), introduction.tag());
            if (operand == null) {
                throw new IllegalStateException("I1: pattern variable '" + let.name() + "' is bound outside the test of its operand's tag "
                        + introduction.tag());
            }
            if (!let.value().equals(operand)) {
                throw new IllegalStateException("I1: pattern variable '" + let.name() + "' binds another term than its tested operand");
            }
            if (!operand.equals(introduction.operand())) {
                throw new IllegalStateException("I1: pattern variable '" + let.name() + "' is tested and bound on another operand than its own");
            }
            open.merge(introduction.variable(), 1, Integer::sum);
            try {
                walk(let.body(), null);
            } finally {
                open.merge(introduction.variable(), -1, Integer::sum);
            }
            return;
        }
        requireInside(let, let.name());
        walk(let.body(), null);
    }

    /** A binder of an introduced pattern variable that is no introduction lies inside one of its introductions. */
    private void requireInside(Object binder, String name) {
        var element = provenance.binderOf(binder, name);
        if (element != null && provenance.isIntroduced(element) && open.getOrDefault(element, 0) == 0) {
            throw new IllegalStateException("I1: pattern variable '" + name + "' is rebound outside every test that introduces it");
        }
    }

    /** The operand of {@code EqualsInteger(FstPair(UnConstrData(operand)), tag)}, or null if {@code condition} is no such test. */
    private static PirTerm testedOperand(PirTerm condition, BigInteger tag) {
        if (condition instanceof PirTerm.App(PirTerm.App(PirTerm.Builtin(var equals), var read), PirTerm.Const(Constant.IntegerConst(var tested)))
                && equals == DefaultFun.EqualsInteger && tested.equals(tag)
                && read instanceof PirTerm.App(PirTerm.Builtin(var first), PirTerm.App(PirTerm.Builtin(var unConstr), var operand))
                && first == DefaultFun.FstPair && unConstr == DefaultFun.UnConstrData) {
            return operand;
        }
        return null;
    }
}
