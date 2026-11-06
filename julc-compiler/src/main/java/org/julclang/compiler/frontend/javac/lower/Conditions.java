package org.julclang.compiler.frontend.javac.lower;

import com.sun.source.tree.BindingPatternTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.InstanceOfTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.WhileLoopTree;
import org.julclang.compiler.CompilerException;
import org.julclang.compiler.error.DiagnosticCodes;
import org.julclang.compiler.frontend.javac.JavacFacts;
import org.julclang.compiler.pir.PirHelpers;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;

import javax.lang.model.element.Element;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;

/**
 * A condition lowered with its two continuations (ADR-069 D2): the term that runs when it is true, in a frame that holds
 * what it introduces when true, and the one that runs when it is false. An {@code instanceof} pattern variable is bound
 * in its test's then-branch alone, so no path where the test failed can read it (I1).
 * <p>
 * It lowers only a condition that declares a pattern variable javac resolves a use of, outside a {@code switch} guard
 * (D2's case 3, {@link #binds}); the whole-condition {@code if} of case 2 never reaches it, since {@code Ifs} sends that
 * one to {@code patternIf} first. Every such program is rejected
 * before ADR-069, so every program that compiled before keeps its terms (I4). Any other condition is lowered as it was:
 * {@link #choice} gives callers one shape for both.
 * <p>
 * <b>Evaluation order.</b> The operand of a pattern test is evaluated once ({@link PirHelpers#readOnce}), then tested, then
 * one continuation runs. Lowering follows the same order: the operand, the test, the then-continuation, the else one.
 */
final class Conditions {

    /** A condition, ready to choose between two continuations: each is called once, with its frame. */
    interface Choice {
        PirTerm choose(Function<Frame, PirTerm> whenTrue, Function<Frame, PirTerm> whenFalse);
    }

    private final JavacFacts facts;
    private final Terms terms;
    private final Lowering lowering;
    private final TypeTests typeTests;
    /** The pattern variables this class bound: none of them is a loop's accumulator (D8 rule 2). */
    private final Set<Element> bound = new HashSet<>();

    Conditions(JavacFacts facts, Terms terms, Lowering lowering, TypeTests typeTests) {
        this.facts = facts;
        this.terms = terms;
        this.lowering = lowering;
        this.typeTests = typeTests;
    }

    /**
     * {@code condition} as a choice. One that this class lowers waits for its continuations; any other is lowered now, as
     * before ADR-069, so what it rejects is rejected in the same order.
     */
    Choice choice(ExpressionTree condition, Frame frame) {
        if (binds(condition, frame)) return (whenTrue, whenFalse) -> branch(condition, frame, whenTrue, whenFalse);
        var test = lowering.expr(condition, frame);
        return (whenTrue, whenFalse) -> new PirTerm.IfThenElse(test, whenTrue.apply(frame), whenFalse.apply(frame));
    }

    /** Whether this class bound {@code variable}. */
    boolean bound(Element variable) {
        return bound.contains(variable);
    }

    /** D2's case 3: outside a guard, {@code condition} declares a pattern variable that javac resolves a use of. */
    boolean binds(ExpressionTree condition, Frame frame) {
        return !frame.inGuard() && declaresUsedVariable(condition);
    }

    private boolean declaresUsedVariable(ExpressionTree condition) {
        return switch (condition) {
            case ParenthesizedTree parenthesized -> declaresUsedVariable(parenthesized.getExpression());
            case InstanceOfTree test when test.getPattern() instanceof BindingPatternTree pattern ->
                    facts.isUsed(facts.element(pattern.getVariable()));
            default -> false;
        };
    }

    /**
     * {@code JULC0139}: a use of a pattern variable that javac resolved and no binder stands for (ADR-069 D6), with the
     * test written again in the developer's own words as the fix. Only a {@code while} carries a variable where JuLC does
     * not; anything else would be a defect of this class, and is reported the same way rather than compiled.
     */
    static CompilerException unbound(JavacFacts facts, IdentifierTree use, Element variable) {
        var test = facts.declaringTest(variable);
        String where = "at this point";
        String fix = null;
        if (test != null) {
            if (facts.enclosing(test, Tree.Kind.WHILE_LOOP) instanceof WhileLoopTree loop && facts.within(test, loop.getCondition())
                    && !facts.within(use, loop.getCondition())) {
                where = facts.within(use, loop.getStatement()) ? "in the body of the while loop whose condition tests it"
                        : "after the while loop that tests it";
            }
            // Re-declaring the name where javac still has it in scope does not compile: the first test drops it.
            String type = facts.text(((BindingPatternTree) test.getPattern()).getVariable().getType());
            String operand = facts.text(test.getExpression());
            fix = test.getExpression() instanceof IdentifierTree
                    ? "Test without the variable where it is declared, " + operand + " instanceof " + type + ", and test again with it where you use it: if ("
                    + operand + " instanceof " + type + " " + use.getName() + ") { ... }"
                    : "Store " + operand + " in a local, test the local without the variable where it is declared, and test it again with the variable where you use it";
        }
        return facts.rejectionWithFix(DiagnosticCodes.PATTERN_VARIABLE_SCOPE_UNSUPPORTED, fix, use, use.getName(), where);
    }

    /** {@code condition} with its continuations. */
    PirTerm branch(ExpressionTree condition, Frame frame, Function<Frame, PirTerm> whenTrue, Function<Frame, PirTerm> whenFalse) {
        return switch (condition) {
            case ParenthesizedTree parenthesized -> branch(parenthesized.getExpression(), frame, whenTrue, whenFalse);
            case InstanceOfTree test when test.getPattern() instanceof BindingPatternTree pattern ->
                    introduce(test, pattern, frame, whenTrue, whenFalse);
            default -> new PirTerm.IfThenElse(lowering.expr(condition, frame), whenTrue.apply(frame), whenFalse.apply(frame));
        };
    }

    /**
     * {@code if tag(#operand) == k then (let v = #operand in whenTrue) else whenFalse}, with the operand bound once unless
     * it is a variable or a literal: the term {@code Ifs.patternIf} builds, in source order.
     */
    private PirTerm introduce(InstanceOfTree test, BindingPatternTree pattern, Frame frame,
                              Function<Frame, PirTerm> whenTrue, Function<Frame, PirTerm> whenFalse) {
        return PirHelpers.readOnce(lowering.expr(test.getExpression(), frame), new PirType.DataType(), operand -> {
            var tagTest = typeTests.instanceOf(test, frame, () -> operand);
            var variable = pattern.getVariable();
            var element = facts.element(variable);
            bound.add(element);
            var scope = frame.scope().bind(element, facts.pirType(element.asType(), variable.getType()));
            var thenTerm = terms.introduced(scope.lookup(element), operand, typeTests.tag(test), whenTrue.apply(frame.in(scope)));
            return new PirTerm.IfThenElse(tagTest, thenTerm, whenFalse.apply(frame));
        });
    }
}
