package org.julclang.compiler.frontend.javac.lower;

import com.sun.source.tree.BindingPatternTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.InstanceOfTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.StatementTree;
import org.julclang.compiler.frontend.javac.JavacFacts;
import org.julclang.compiler.pir.PirHelpers;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;

import javax.lang.model.element.Element;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * {@code if} statements, in the reference's three shapes, each reviewed against its source in {@code PirGenerator}:
 * {@link #ifWithExit}, {@link #ifWithLoop} and {@link #ifForEffect}. A loop's own {@code if}s are {@link LoopBody}'s.
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
 * A pattern that is the whole condition binds as in the reference, which reads it from the {@code if}: javac's condition
 * is {@code PARENTHESIZED}, and {@link #condition} takes off that one level. Any other condition whose pattern variable is
 * used is {@link Conditions}' (ADR-069), which binds it in the then-branch alone.
 */
final class Ifs {

    private final JavacFacts facts;
    private final Terms terms;
    private final Statements statements;
    private final TypeTests typeTests;
    private final Lowering lowering;
    private final Accumulators accumulators;
    private final Typing typing;
    /** Numbers the joins of {@link #ifWithLoop}, so one in a branch of another never shadows it. */
    private int joins;

    Ifs(JavacFacts facts, Terms terms, Statements statements, TypeTests typeTests, Lowering lowering, Accumulators accumulators,
        Typing typing) {
        this.facts = facts;
        this.terms = terms;
        this.statements = statements;
        this.typeTests = typeTests;
        this.lowering = lowering;
        this.accumulators = accumulators;
        this.typing = typing;
    }

    /** The {@code if} at {@code index}, which continues with what follows it, then {@code cont}. */
    PirTerm inSequence(IfTree tree, List<? extends StatementTree> following, int index, Frame frame, PirTerm cont) {
        var introduced = lowering.conditions().introducedBy(tree, frame);
        if (!introduced.isEmpty()) return introducing(tree, introduced, following, index, frame, cont);
        if (Exits.holdsLoop(tree.getThenStatement()) || holdsLoopInElse(tree)) return ifWithLoop(tree, following, index, frame, cont);
        boolean exits = Exits.holdsExit(tree.getThenStatement())
                || tree.getElseStatement() != null && Exits.holdsExit(tree.getElseStatement());
        return exits ? ifWithExit(tree, following, index, frame, cont) : ifForEffect(tree, following, index, frame, cont);
    }

    /**
     * ADR-069 D4: an {@code if} that introduces {@code introduced} into the rest of its block. The outcome whose frame binds
     * them falls through: its branch and the rest of the block are one statement list, lowered once in that frame by
     * today's routing. The other outcome's branch cannot complete normally, which javac has proved, so its end is an
     * {@code Error} no path reaches.
     */
    private PirTerm introducing(IfTree tree, List<Element> introduced, List<? extends StatementTree> following, int index, Frame frame,
                                PirTerm cont) {
        var rest = following.subList(index + 1, following.size());
        var falling = new PirTerm[1];
        Function<Frame, PirTerm> then = inner -> Conditions.bindsAll(inner, introduced)
                ? (falling[0] = statements.sequence(spliced(tree.getThenStatement(), rest), 0, inner, cont))
                : statements.branch(tree.getThenStatement(), inner, unreachable(frame));
        Function<Frame, PirTerm> otherwise = inner -> Conditions.bindsAll(inner, introduced)
                ? (falling[0] = statements.sequence(spliced(tree.getElseStatement(), rest), 0, inner, cont))
                : tree.getElseStatement() == null ? unreachable(frame) : statements.branch(tree.getElseStatement(), inner, unreachable(frame));
        var result = lowering.conditions().branch(condition(tree), frame, then, otherwise);
        // No outcome bound them: a defect here, refused at the first use rather than compiled to a script that fails (M3-C7).
        if (falling[0] == null) throw Conditions.unboundAfter(facts, tree, introduced);
        // Typed by what falls through: in a lambda block the unreachable end's own type is only a placeholder (review M3-S4).
        typing.typedAs(result, typing.ofTerm(falling[0]));
        return terms.at(tree, result);
    }

    /** A branch's statements, none for a missing one, then {@code rest}. */
    static List<StatementTree> spliced(StatementTree branch, List<? extends StatementTree> rest) {
        var list = new ArrayList<StatementTree>();
        if (branch instanceof BlockTree block) list.addAll(block.getStatements());
        else if (branch != null) list.add(branch);
        list.addAll(rest);
        return list;
    }

    /** The end of a branch javac has proved cannot complete: of the block's value type, which no path reaches. */
    private static PirTerm unreachable(Frame frame) {
        var type = frame.exit().returnType();
        return new PirTerm.Error(type != null ? type : new PirType.UnitType());
    }

    private static boolean holdsLoopInElse(IfTree tree) {
        return tree.getElseStatement() != null && Exits.holdsLoop(tree.getElseStatement());
    }

    /**
     * A branch leaves the method, or the {@code switch} arm. What follows becomes each branch's continuation, so a path that returns skips it and a
     * path that falls through runs it. It is placed in both branches, and {@code let}s nothing, as the reference does.
     */
    private PirTerm ifWithExit(IfTree tree, List<? extends StatementTree> following, int index, Frame frame, PirTerm cont) {
        return terms.at(tree, withContinuation(tree, statements.sequence(following, index + 1, frame, cont), frame));
    }

    /** The {@code if} with {@code after} placed in each branch that falls through. */
    private PirTerm withContinuation(IfTree tree, PirTerm after, Frame frame) {
        var condition = condition(tree);
        var pattern = pattern(condition);
        PirTerm result;
        if (pattern != null) {
            var elseTerm = elseWithExit(tree, frame, after);
            result = patternIf((InstanceOfTree) condition, pattern, elseTerm, frame,
                    inner -> statements.branch(tree.getThenStatement(), inner, after));
        } else {
            result = lowering.conditions().choice(condition, frame).choose(
                    inner -> statements.branch(tree.getThenStatement(), inner, after), inner -> elseWithExit(tree, inner, after));
        }
        return result;
    }

    private PirTerm elseWithExit(IfTree tree, Frame frame, PirTerm after) {
        return tree.getElseStatement() == null ? after : statements.branch(tree.getElseStatement(), frame, after);
    }

    /**
     * ADR-050: a branch holds a loop. What follows is bound once, as a function each path that falls through calls, so N
     * guarded loops in a row do not grow as 2^N. It closes over what no loop changes, and what the branches' loops update
     * is its argument, read where the branch ends: there the loop's result has been bound to that variable.
     */
    private PirTerm ifWithLoop(IfTree tree, List<? extends StatementTree> following, int index, Frame frame, PirTerm cont) {
        var after = statements.sequence(following, index + 1, frame, cont);
        var join = "#if-join-" + ++joins;
        var updated = accumulators.ofBranches(tree, frame);
        PirTerm joinBody = after;
        PirType joinType = typing.ofTerm(after);
        for (int i = updated.size() - 1; i >= 0; i--) {
            var binder = frame.scope().lookup(updated.get(i));
            joinBody = terms.lam(binder, joinBody);
            joinType = new PirType.FunType(binder.type(), joinType);
        }
        if (updated.isEmpty()) {
            joinBody = terms.generatedLam("#unit", new PirType.UnitType(), joinBody);
            joinType = new PirType.FunType(new PirType.UnitType(), joinType);
        }
        PirTerm fallThrough = terms.generated(join, joinType);
        for (var element : updated) fallThrough = new PirTerm.App(fallThrough, terms.var(element, frame.scope().lookup(element)));
        if (updated.isEmpty()) fallThrough = new PirTerm.App(fallThrough, Statements.unit());
        return terms.at(tree, terms.generatedLet(join, joinBody, withContinuation(tree, fallThrough, frame)));
    }

    /**
     * No branch leaves: the {@code if} runs for its effect, under a {@code let}, and what follows runs after it.
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
        return lowering.conditions().choice(condition, frame).choose(
                inner -> statements.single(tree.getThenStatement(), inner), inner -> elseForEffect(tree, inner));
    }

    private PirTerm elseForEffect(IfTree tree, Frame frame) {
        return tree.getElseStatement() == null ? Statements.unit() : statements.single(tree.getElseStatement(), frame);
    }

    /**
     * {@code if (test) { let v = operand in then } else elseTerm}: {@code v} is in scope in the then branch alone. The test
     * evaluates the operand first, so the operand can be bound ahead of it ({@link PirHelpers#readOnce}).
     */
    private PirTerm patternIf(InstanceOfTree test, BindingPatternTree pattern, PirTerm elseTerm, Frame frame,
                              Function<Frame, PirTerm> then) {
        return PirHelpers.readOnce(lowering.expr(test.getExpression(), frame), new PirType.DataType(), scrutinee -> {
            var condition = typeTests.instanceOf(test, frame, () -> scrutinee);
            var variable = pattern.getVariable();
            var element = facts.element(variable);
            var scope = frame.scope().bind(element, facts.pirType(element.asType(), variable.getType()));
            // In a guard the test is a dispatch (ADR-067 D1), which I1's check does not read: its binder is a plain let.
            var thenTerm = frame.inGuard() ? terms.let(scope.lookup(element), scrutinee, then.apply(frame.in(scope)))
                    : terms.introduced(scope.lookup(element), scrutinee, typeTests.tag(test), then.apply(frame.in(scope)));
            return new PirTerm.IfThenElse(condition, thenTerm, elseTerm);
        });
    }

    /** The expression between an {@code if}'s own parentheses, which javac keeps as a tree. A user's stay. */
    static ExpressionTree condition(IfTree tree) {
        return ((ParenthesizedTree) tree.getCondition()).getExpression();
    }

    /** The pattern of a condition that is an {@code instanceof} with a type pattern, or null. */
    private static BindingPatternTree pattern(ExpressionTree condition) {
        return condition instanceof InstanceOfTree test && test.getPattern() instanceof BindingPatternTree pattern ? pattern : null;
    }
}
