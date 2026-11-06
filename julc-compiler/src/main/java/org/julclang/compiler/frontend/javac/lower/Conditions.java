package org.julclang.compiler.frontend.javac.lower;

import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.BindingPatternTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.InstanceOfTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.tree.WhileLoopTree;
import org.julclang.compiler.CompilerException;
import org.julclang.compiler.error.DiagnosticCodes;
import org.julclang.compiler.frontend.javac.JavacFacts;
import org.julclang.compiler.pir.PirHelpers;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;
import org.julclang.core.Constant;

import javax.lang.model.element.Element;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
 * Over JLS 6.3.1's forms: {@code !a} swaps the continuations; {@code a && b} lowers {@code b} in {@code a}'s when-true
 * frame and {@code a || b} in its when-false one; a boolean {@code a ? b : c} lowers {@code b} and {@code c} as conditions
 * in {@code a}'s frames. A continuation that two paths reach (the false one of {@code &&}, the true one of {@code ||}, both
 * of {@code ?:}) gets no variable from that sub-condition (JLS 6.3.1.1, 6.3.1.2, 6.3.1.4), so it is lowered once, in the
 * frame where the sub-condition starts, under every binder around it: a trivial term is copied to each path, any other
 * is bound once as {@code #cond-join-N}, a thunk each path applies (I6, I7).
 * <p>
 * In a value position the continuations are {@code true} and {@code false}, and a test with those continuations is the
 * test itself, so {@code x instanceof T v && v.f() > 1} is the term {@code &&} has always had, with {@code v} bound.
 * <p>
 * <b>Evaluation order.</b> The operand of a pattern test is evaluated once ({@link PirHelpers#readOnce}), then tested, then
 * one continuation runs; {@code &&}, {@code ||} and {@code ?:} run their operands left to right and only as Java does.
 * Lowering builds a shared continuation first, then the condition from left to right, then each path.
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
    private final Typing typing;
    /** Numbers the joins, so no join shadows another. */
    private int joins;
    /** The pattern variables this class bound: none of them is a loop's accumulator (D8 rule 2). */
    private final Set<Element> bound = new HashSet<>();

    /** The continuations of a value position: identities, so that a test with them can be the test itself. */
    private static final Function<Frame, PirTerm> TRUE = frame -> new PirTerm.Const(Constant.bool(true));
    private static final Function<Frame, PirTerm> FALSE = frame -> new PirTerm.Const(Constant.bool(false));

    Conditions(JavacFacts facts, Terms terms, Lowering lowering, TypeTests typeTests, Typing typing) {
        this.facts = facts;
        this.terms = terms;
        this.lowering = lowering;
        this.typeTests = typeTests;
        this.typing = typing;
    }

    /** {@code condition} as a boolean value: a local's initializer, a {@code return}, an argument, a lambda body. */
    PirTerm value(ExpressionTree condition, Frame frame) {
        return branch(condition, frame, TRUE, FALSE);
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

    /**
     * D4: the pattern variables {@code tree}'s condition declares that javac resolves a use of outside {@code tree}. javac
     * accepts such a use only where JLS 6.3.2.2 introduces the variable into the rest of the block, so one outcome of the
     * {@code if} falls through to it, and the other's branch cannot complete normally. Empty in a guard, as everything here.
     */
    List<Element> introducedBy(IfTree tree, Frame frame) {
        var introduced = new ArrayList<Element>();
        if (frame.inGuard()) return introduced;
        declared(Ifs.condition(tree), introduced);
        introduced.removeIf(variable -> !facts.isUsedOutside(variable, tree));
        return introduced;
    }

    /** The pattern variables declared through JLS 6.3.1's forms. */
    private void declared(ExpressionTree condition, List<Element> into) {
        switch (condition) {
            case ParenthesizedTree parenthesized -> declared(parenthesized.getExpression(), into);
            case UnaryTree not when not.getKind() == Tree.Kind.LOGICAL_COMPLEMENT -> declared(not.getExpression(), into);
            case BinaryTree binary when isShortCircuit(binary) -> {
                declared(binary.getLeftOperand(), into);
                declared(binary.getRightOperand(), into);
            }
            case ConditionalExpressionTree conditional when isBoolean(conditional) -> {
                declared(conditional.getCondition(), into);
                declared(conditional.getTrueExpression(), into);
                declared(conditional.getFalseExpression(), into);
            }
            case InstanceOfTree test when test.getPattern() instanceof BindingPatternTree pattern -> into.add(facts.element(pattern.getVariable()));
            default -> { }
        }
    }

    /** Whether {@code frame} binds every one of {@code variables}: the outcome of a D4 {@code if} that falls through. */
    static boolean bindsAll(Frame frame, List<Element> variables) {
        return variables.stream().allMatch(variable -> frame.scope().lookup(variable) != null);
    }

    /** Through JLS 6.3.1's forms, the only ones whose pattern variables reach past their own operand. */
    private boolean declaresUsedVariable(ExpressionTree condition) {
        return switch (condition) {
            case ParenthesizedTree parenthesized -> declaresUsedVariable(parenthesized.getExpression());
            case UnaryTree not when not.getKind() == Tree.Kind.LOGICAL_COMPLEMENT -> declaresUsedVariable(not.getExpression());
            case BinaryTree binary when isShortCircuit(binary) ->
                    declaresUsedVariable(binary.getLeftOperand()) || declaresUsedVariable(binary.getRightOperand());
            case ConditionalExpressionTree conditional -> declaresUsedVariable(conditional.getCondition())
                    || isBoolean(conditional) && (declaresUsedVariable(conditional.getTrueExpression())
                    || declaresUsedVariable(conditional.getFalseExpression()));
            case InstanceOfTree test when test.getPattern() instanceof BindingPatternTree pattern ->
                    facts.isUsed(facts.element(pattern.getVariable()));
            default -> false;
        };
    }

    private static boolean isShortCircuit(BinaryTree binary) {
        return binary.getKind() == Tree.Kind.CONDITIONAL_AND || binary.getKind() == Tree.Kind.CONDITIONAL_OR;
    }

    /** A {@code boolean} or a {@code Boolean}: a conditional that is itself a condition. */
    private boolean isBoolean(ExpressionTree tree) {
        return facts.pirType(facts.type(tree), tree) instanceof PirType.BoolType;
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

    /** {@code JULC0139} at the first use, outside {@code tree}, of the first of {@code introduced}: a D4 if no outcome bound. */
    static CompilerException unboundAfter(JavacFacts facts, IfTree tree, List<Element> introduced) {
        var variable = introduced.getFirst();
        return unbound(facts, facts.firstUseOutside(variable, tree), variable);
    }

    /** {@code condition} with its continuations. */
    PirTerm branch(ExpressionTree condition, Frame frame, Function<Frame, PirTerm> whenTrue, Function<Frame, PirTerm> whenFalse) {
        return switch (condition) {
            case ParenthesizedTree parenthesized -> branch(parenthesized.getExpression(), frame, whenTrue, whenFalse);
            case UnaryTree not when not.getKind() == Tree.Kind.LOGICAL_COMPLEMENT -> branch(not.getExpression(), frame, whenFalse, whenTrue);
            // No position here: in a value position Lowering.expr records the expression's, and in a statement the result is
            // the whole if, whose position is the statement's.
            case BinaryTree and when and.getKind() == Tree.Kind.CONDITIONAL_AND -> shared(whenFalse, frame, otherwise ->
                    branch(and.getLeftOperand(), frame, inner -> branch(and.getRightOperand(), inner, whenTrue, otherwise), otherwise));
            case BinaryTree or when or.getKind() == Tree.Kind.CONDITIONAL_OR -> shared(whenTrue, frame, then ->
                    branch(or.getLeftOperand(), frame, then, inner -> branch(or.getRightOperand(), inner, then, whenFalse)));
            case ConditionalExpressionTree choice when isBoolean(choice) -> shared(whenTrue, frame, then ->
                    shared(whenFalse, frame, otherwise -> branch(choice.getCondition(), frame,
                            inner -> branch(choice.getTrueExpression(), inner, then, otherwise),
                            inner -> branch(choice.getFalseExpression(), inner, then, otherwise))));
            case InstanceOfTree test when test.getPattern() instanceof BindingPatternTree pattern ->
                    introduce(test, pattern, frame, whenTrue, whenFalse);
            default -> leaf(lowering.expr(condition, frame), frame, whenTrue, whenFalse);
        };
    }

    /** A test that binds nothing: in a value position, the test itself, or its complement. */
    private static PirTerm leaf(PirTerm test, Frame frame, Function<Frame, PirTerm> whenTrue, Function<Frame, PirTerm> whenFalse) {
        if (whenTrue == TRUE && whenFalse == FALSE) return test;
        return new PirTerm.IfThenElse(test, whenTrue.apply(frame), whenFalse.apply(frame));
    }

    /**
     * {@code body} given {@code continuation} as a continuation two paths can call: a value position's constant as it is, a
     * trivial term copied to each call, anything else bound once around {@code body} as a {@code #cond-join-N} thunk, in
     * {@code frame}. The JLS guarantees the continuation needs no variable the paths introduce.
     */
    private PirTerm shared(Function<Frame, PirTerm> continuation, Frame frame, Function<Function<Frame, PirTerm>, PirTerm> body) {
        if (continuation == TRUE || continuation == FALSE) return body.apply(continuation);
        var term = continuation.apply(frame);
        if (isTrivial(term)) return body.apply(unused -> {
            var copy = terms.copied(term);
            typing.copied(term, copy);
            return copy;
        });
        var name = "#cond-join-" + ++joins;
        var type = new PirType.FunType(new PirType.UnitType(), typing.ofTerm(term));
        var result = body.apply(unused -> new PirTerm.App(terms.generated(name, type), Statements.unit()));
        return terms.generatedLet(name, terms.generatedLam("#unit", new PirType.UnitType(), term), result);
    }

    private static boolean isTrivial(PirTerm term) {
        return term instanceof PirTerm.Const || term instanceof PirTerm.Error || term instanceof PirTerm.Var
                || term instanceof PirTerm.App(PirTerm.Var join, PirTerm.Const ignored) && join.name().startsWith("#cond-join-");
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
