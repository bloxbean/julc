package org.julclang.compiler.frontend.javac.lower;

import com.sun.source.tree.BindingPatternTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.InstanceOfTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.StatementTree;
import org.julclang.compiler.frontend.javac.JavacFacts;
import org.julclang.compiler.frontend.javac.NotPorted;
import org.julclang.compiler.pir.PirTerm;

import java.util.List;
import java.util.function.Function;

/**
 * {@code if} statements, in the reference's three shapes, each reviewed against its source in {@code PirGenerator}:
 * {@link #ifWithExit}, {@link #ifWithLoop} and {@link #ifForEffect}.
 * <p>
 * <b>Evaluation order.</b> The condition, then one branch, then what follows unless that branch left the method. In
 * {@code ifWithExit} what follows sits inside each branch that falls through, so it runs at most once. In
 * {@code ifForEffect} the branches run first, under a strict {@code let}, and what follows runs after them.
 * <p>
 * <b>Generation order</b>, which decides which of two errors comes first, is the reference's, and differs by shape:
 * what follows is built first in {@code ifWithExit} and last in {@code ifForEffect}, and with a pattern the {@code else}
 * branch is built before the condition.
 * <p>
 * What follows is built once, in the frame around the {@code if}, and reused by reference. So its free variables are all
 * visible in that frame, and a binder of a branch takes another name by {@link Scope} rather than capture one.
 * <p>
 * A pattern binds only when it is the whole condition, as in the reference, which reads it from the {@code if}: javac's
 * condition is {@code PARENTHESIZED}, and {@link #condition} takes off that one level. Under another pair of parentheses,
 * or inside {@code &&} or {@code !}, the test lowers and its variable is {@code Undefined variable}.
 */
final class Ifs {

    private final JavacFacts facts;
    private final Terms terms;
    private final Statements statements;
    private final TypeTests typeTests;
    private final Lowering lowering;

    Ifs(JavacFacts facts, Terms terms, Statements statements, TypeTests typeTests, Lowering lowering) {
        this.facts = facts;
        this.terms = terms;
        this.statements = statements;
        this.typeTests = typeTests;
        this.lowering = lowering;
    }

    /** The {@code if} at {@code index}, which continues with what follows it, then {@code cont}. */
    PirTerm inSequence(IfTree tree, List<? extends StatementTree> following, int index, Frame frame, PirTerm cont) {
        if (Exits.holdsLoop(tree.getThenStatement()) || holdsLoopInElse(tree)) return ifWithLoop(tree);
        boolean exits = Exits.holdsReturn(tree.getThenStatement())
                || tree.getElseStatement() != null && Exits.holdsReturn(tree.getElseStatement());
        return exits ? ifWithExit(tree, following, index, frame, cont) : ifForEffect(tree, following, index, frame, cont);
    }

    private static boolean holdsLoopInElse(IfTree tree) {
        return tree.getElseStatement() != null && Exits.holdsLoop(tree.getElseStatement());
    }

    /**
     * A branch leaves the method. What follows becomes each branch's continuation, so a path that returns skips it and a
     * path that falls through runs it. It is placed in both branches, and {@code let}s nothing, as the reference does.
     */
    private PirTerm ifWithExit(IfTree tree, List<? extends StatementTree> following, int index, Frame frame, PirTerm cont) {
        var after = statements.sequence(following, index + 1, frame, cont);
        var condition = condition(tree);
        var pattern = pattern(condition);
        PirTerm result;
        if (pattern != null) {
            var elseTerm = elseWithExit(tree, frame, after);
            result = patternIf((InstanceOfTree) condition, pattern, elseTerm, frame,
                    inner -> statements.branch(tree.getThenStatement(), inner, after));
        } else {
            var test = lowering.expr(condition, frame);
            var thenTerm = statements.branch(tree.getThenStatement(), frame, after);
            result = new PirTerm.IfThenElse(test, thenTerm, elseWithExit(tree, frame, after));
        }
        return terms.at(tree, result);
    }

    private PirTerm elseWithExit(IfTree tree, Frame frame, PirTerm after) {
        return tree.getElseStatement() == null ? after : statements.branch(tree.getElseStatement(), frame, after);
    }

    /** ADR-050's join, with loops: nothing reaches it yet, since a loop stops first. */
    private PirTerm ifWithLoop(IfTree tree) {
        throw NotPorted.at(facts, tree, "an if around a loop");
    }

    /**
     * No branch leaves the method: the {@code if} runs for its effect, under a {@code let}, and what follows runs after it.
     * As the last thing a statement list does, it is the list's value.
     */
    private PirTerm ifForEffect(IfTree tree, List<? extends StatementTree> following, int index, Frame frame, PirTerm cont) {
        boolean hasFollowing = index + 1 < following.size();
        var effect = terms.at(tree, nested(tree, frame));
        if (!hasFollowing && cont == null) return effect;
        return terms.afterEffect(effect, statements.sequence(following, index + 1, frame, cont));
    }

    /** An {@code if} that is a value: a branch of another for its effect, or the term {@link #ifForEffect} binds. */
    PirTerm nested(IfTree tree, Frame frame) {
        var condition = condition(tree);
        var pattern = pattern(condition);
        if (pattern != null) {
            var elseTerm = elseForEffect(tree, frame);
            return patternIf((InstanceOfTree) condition, pattern, elseTerm, frame,
                    inner -> statements.single(tree.getThenStatement(), inner));
        }
        var test = lowering.expr(condition, frame);
        var thenTerm = statements.single(tree.getThenStatement(), frame);
        return new PirTerm.IfThenElse(test, thenTerm, elseForEffect(tree, frame));
    }

    private PirTerm elseForEffect(IfTree tree, Frame frame) {
        return tree.getElseStatement() == null ? Statements.unit() : statements.single(tree.getElseStatement(), frame);
    }

    /** {@code if (test) { let v = operand in then } else elseTerm}: {@code v} is in scope in the then branch alone. */
    private PirTerm patternIf(InstanceOfTree test, BindingPatternTree pattern, PirTerm elseTerm, Frame frame,
                              Function<Frame, PirTerm> then) {
        var condition = typeTests.instanceOf(test, frame);
        var scrutinee = lowering.expr(test.getExpression(), frame);
        var variable = pattern.getVariable();
        var element = facts.element(variable);
        var scope = frame.scope().bind(element, facts.pirType(element.asType(), variable.getType()));
        var thenTerm = terms.let(scope.lookup(element), scrutinee, then.apply(frame.in(scope)));
        return new PirTerm.IfThenElse(condition, thenTerm, elseTerm);
    }

    /** The expression between an {@code if}'s own parentheses, which javac keeps as a tree. A user's stay. */
    private static ExpressionTree condition(IfTree tree) {
        return ((ParenthesizedTree) tree.getCondition()).getExpression();
    }

    /** The pattern of a condition that is an {@code instanceof} with a type pattern, or null. */
    private static BindingPatternTree pattern(ExpressionTree condition) {
        return condition instanceof InstanceOfTree test && test.getPattern() instanceof BindingPatternTree pattern ? pattern : null;
    }
}
