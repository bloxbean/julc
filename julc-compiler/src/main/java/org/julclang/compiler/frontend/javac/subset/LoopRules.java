package org.julclang.compiler.frontend.javac.subset;

import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.BreakTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.ContinueTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.LabeledStatementTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.SwitchExpressionTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.WhileLoopTree;
import com.sun.source.tree.YieldTree;
import com.sun.source.util.TreeScanner;
import org.julclang.compiler.CompilerTypeDiagnostics;
import org.julclang.compiler.error.DiagnosticCodes;
import org.julclang.compiler.frontend.javac.JavacFacts;

import javax.lang.model.element.Element;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What a loop must be before it lowers, ported from the checks at the head of {@code PirGenerator.generateForEachStmt} and
 * {@code generateWhileStmt}, in their order, so a source with two rejections reports the same one first (I2a). A variable is
 * its element wherever the reference's question is what a name binds to. {@link #breakWithoutAccumulator} asks what a name
 * spells, as the reference does, so a declaration named like the update it hides keeps its answer.
 * <p>
 * javac decides what no rule here needs to: {@code break} and {@code continue} outside a loop, a statement after {@code
 * break}, and an update of a {@code final} field.
 */
public final class LoopRules {

    private final JavacFacts facts;

    public LoopRules(JavacFacts facts) {
        this.facts = facts;
    }

    /** The rules of a for-each, before anything of it is lowered. */
    public void check(EnhancedForLoopTree loop) {
        requireNoLabelOrContinue(loop);
        requireNoConditionalLocalUpdate(loop.getStatement(), Set.of(facts.element(loop.getVariable())));
        if (firstExit(loop.getStatement(), ReturnTree.class) != null) {
            throw facts.rejection(DiagnosticCodes.RETURN_INSIDE_FOR_EACH, loop);
        }
        if (firstExit(loop.getStatement(), YieldTree.class) != null) {
            throw facts.rejection(DiagnosticCodes.YIELD_INSIDE_LOOP, loop, "for-each");
        }
    }

    /** The rules of a {@code while}, before anything of it is lowered. */
    public void check(WhileLoopTree loop) {
        requireNoLabelOrContinue(loop);
        requireNoConditionalLocalUpdate(loop.getStatement(), Set.of());
        var exit = firstExit(loop.getStatement(), ReturnTree.class);
        if (exit != null) throw CompilerTypeDiagnostics.returnInsideWhile(facts.location(exit));
        if (firstExit(loop.getStatement(), YieldTree.class) != null) {
            throw facts.rejection(DiagnosticCodes.YIELD_INSIDE_LOOP, loop, "while");
        }
    }

    /** {@code JULC0066}, wherever a label stands in the loop, then {@code JULC0060}. A labelled {@code break} is never a plain one. */
    private void requireNoLabelOrContinue(StatementTree loop) {
        for (var tree : SubsetWalk.all(loop, Tree.class)) {
            var construct = labelled(tree);
            if (construct != null) throw CompilerTypeDiagnostics.labelUnsupported(construct, facts.location(tree));
        }
        var found = SubsetWalk.all(loop, ContinueTree.class);
        if (!found.isEmpty()) throw CompilerTypeDiagnostics.continueUnsupported(facts.location(found.getFirst()));
    }

    /** The construct as written, if {@code tree} holds a label; null if it does not. */
    private static String labelled(Tree tree) {
        return switch (tree) {
            case LabeledStatementTree statement -> statement.getLabel() + ":";
            case BreakTree statement when statement.getLabel() != null -> "break " + statement.getLabel();
            case ContinueTree statement when statement.getLabel() != null -> "continue " + statement.getLabel();
            default -> null;
        };
    }

    /** The first statement of {@code exit}'s kind that a body holds directly, in its blocks and branches and not in a nested loop. */
    public static StatementTree firstExit(StatementTree statement, Class<? extends StatementTree> exit) {
        if (exit.isInstance(statement)) return statement;
        return switch (statement) {
            case BlockTree block -> block.getStatements().stream().map(inner -> firstExit(inner, exit))
                    .filter(found -> found != null).findFirst().orElse(null);
            case IfTree branch -> {
                var found = firstExit(branch.getThenStatement(), exit);
                yield found != null || branch.getElseStatement() == null ? found : firstExit(branch.getElseStatement(), exit);
            }
            default -> null;
        };
    }

    // --- An update across a join (#155, ADR-048) ---

    /**
     * An {@code if} joins only the loop's accumulators, not its body's locals, so an update that would cross such a join is
     * rejected, even a dead one: a branch of an {@code if} does not hand a local on. A bare block is no join. A nested
     * loop owns its own locals, and what is local around it is its accumulator, which still cannot leave an enclosing branch.
     */
    private void requireNoConditionalLocalUpdate(StatementTree body, Set<Element> locals) {
        crossing(body, new HashSet<>(locals), new HashSet<>());
    }

    private void crossing(Tree tree, Set<Element> locals, Set<Element> conditional) {
        switch (tree) {
            case BlockTree block -> {
                var blockLocals = new HashSet<>(locals);
                block.getStatements().forEach(statement -> crossing(statement, blockLocals, conditional));
            }
            case ExpressionStatementTree statement -> crossing(statement.getExpression(), locals, conditional);
            case VariableTree variable -> {
                if (variable.getInitializer() != null) crossing(variable.getInitializer(), locals, conditional);
                locals.add(facts.element(variable));
            }
            case IfTree branch -> {
                crossing(branch.getCondition(), locals, conditional);
                var acrossTheJoin = new HashSet<>(conditional);
                acrossTheJoin.addAll(locals);
                crossing(branch.getThenStatement(), new HashSet<>(locals), new HashSet<>(acrossTheJoin));
                if (branch.getElseStatement() != null) {
                    crossing(branch.getElseStatement(), new HashSet<>(locals), new HashSet<>(acrossTheJoin));
                }
            }
            case EnhancedForLoopTree loop -> {
                crossing(loop.getExpression(), locals, conditional);
                crossing(loop.getStatement(), new HashSet<>(Set.of(facts.element(loop.getVariable()))), new HashSet<>(conditional));
            }
            case WhileLoopTree loop -> {
                crossing(loop.getCondition(), locals, conditional);
                crossing(loop.getStatement(), new HashSet<>(), new HashSet<>(conditional));
            }
            // An arm exports only its value, and its own loops are checked where they lower.
            case SwitchExpressionTree expression -> crossing(expression.getExpression(), locals, conditional);
            case LambdaExpressionTree ignored -> {
            }
            default -> {
                var target = updated(tree);
                if (target != null && conditional.contains(facts.element(target))) {
                    throw facts.rejection(DiagnosticCodes.LOOP_LOCAL_CONDITIONAL_UPDATE, tree, target.getName());
                }
                SubsetWalk.children(tree).forEach(child -> crossing(child, new HashSet<>(locals), new HashSet<>(conditional)));
            }
        }
    }

    /** The variable an assignment or a compound assignment updates, if the target is a name. */
    public static IdentifierTree updated(Tree tree) {
        var target = switch (tree) {
            case AssignmentTree assignment -> assignment.getVariable();
            case CompoundAssignmentTree assignment -> assignment.getVariable();
            default -> null;
        };
        return target instanceof IdentifierTree name ? name : null;
    }

    // --- A loop with no accumulator ---

    /**
     * {@code JULC0058}: a for-each with no accumulator lowers its body as plain statements, which cannot reassign a
     * variable, so assigning its variable or a local of its body is rejected. A nested loop lowers its own assignments.
     */
    public void requireNoReassignment(EnhancedForLoopTree loop) {
        var variable = facts.element(loop.getVariable());
        var found = firstReassignment(statementsOf(loop.getStatement()), new HashSet<>(Set.of(variable)));
        if (found == null) return;
        var name = updated(found);
        throw CompilerTypeDiagnostics.loopReassignmentWithoutAccumulator(
                facts.element(name).equals(variable) ? "loop variable" : "local variable", name.getName().toString(), facts.location(found));
    }

    private ExpressionTree firstReassignment(List<? extends StatementTree> statements, Set<Element> outer) {
        var locals = new HashSet<>(outer);
        for (var statement : statements) {
            ExpressionTree found = switch (statement) {
                case VariableTree variable -> {
                    locals.add(facts.element(variable));
                    yield null;
                }
                case ExpressionStatementTree expression -> {
                    var target = updated(expression.getExpression());
                    yield target != null && locals.contains(facts.element(target)) ? expression.getExpression() : null;
                }
                case IfTree branch -> {
                    var inThen = firstReassignment(statementsOf(branch.getThenStatement()), locals);
                    yield inThen != null || branch.getElseStatement() == null ? inThen
                            : firstReassignment(statementsOf(branch.getElseStatement()), locals);
                }
                case BlockTree block -> firstReassignment(block.getStatements(), locals);
                default -> null;
            };
            if (found != null) return found;
        }
        return null;
    }

    /**
     * {@code JULC0065}: a for-each with no accumulator holds a {@code break}, and its iterable updates a variable. The reference
     * goes on past the update's own rejection to this one, so it comes before the iterable lowers, and a reassignment in the
     * body, which it goes on to, comes before it.
     */
    public void requireNoUpdateOfTheIterable(EnhancedForLoopTree loop) {
        var found = updates(loop.getExpression());
        if (!found.isEmpty()) {
            requireNoReassignment(loop);
            var update = found.getFirst();
            throw CompilerTypeDiagnostics.loopUpdatePositionUnsupported(updated(update).getName().toString(), "in the loop iterable", facts.location(update));
        }
    }

    private static List<? extends StatementTree> statementsOf(StatementTree statement) {
        return statement instanceof BlockTree block ? block.getStatements() : List.of(statement);
    }

    /**
     * A loop with no accumulator lowers its body as plain statements, which cannot stop the loop, so its {@code break} is
     * rejected before the body lowers (T26, #273). {@code JULC0065} where the body updates what it did not declare, at the
     * first such update, which is one the accumulators do not see; otherwise {@code JULC0063} at the {@code break}. A
     * {@code while}'s condition runs before its body, so an update there is reported first. A for-each's iterable has met
     * {@link #requireNoUpdateOfTheIterable}.
     */
    public void requireNoBreak(StatementTree loop, StatementTree body) {
        var found = firstExit(body, BreakTree.class);
        if (found == null) return;
        var inHeader = loop instanceof WhileLoopTree whileLoop ? updates(whileLoop.getCondition()) : List.<Tree>of();
        if (!inHeader.isEmpty()) {
            var update = inHeader.getFirst();
            throw CompilerTypeDiagnostics.loopUpdatePositionUnsupported(updated(update).getName().toString(), "in the loop condition", facts.location(update));
        }
        var declared = new HashSet<String>();
        if (loop instanceof EnhancedForLoopTree forEach) declared.add(forEach.getVariable().getName().toString());
        SubsetWalk.all(body, VariableTree.class).forEach(variable -> declared.add(variable.getName().toString()));
        for (var update : updates(body)) {
            String name = updated(update).getName().toString();
            if (!declared.contains(name)) {
                throw CompilerTypeDiagnostics.loopUpdatePositionUnsupported(name, updatePosition(update, body), facts.location(update));
            }
        }
        throw CompilerTypeDiagnostics.breakInLoopWithoutAccumulator(facts.location(found));
    }

    /** The assignments to a name under {@code root}, in source order. */
    public static List<Tree> updates(Tree root) {
        var found = new ArrayList<Tree>();
        for (var tree : SubsetWalk.all(root, Tree.class)) {
            if (updated(tree) != null) found.add(tree);
        }
        return found;
    }

    /** The {@code return}s of {@code method}, without a lambda's. */
    public static List<ReturnTree> returns(MethodTree method) {
        var found = new ArrayList<ReturnTree>();
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitReturn(ReturnTree tree, Void unused) {
                found.add(tree);
                return null;
            }

            @Override
            public Void visitLambdaExpression(LambdaExpressionTree tree, Void unused) {
                return null;
            }
        }.scan(method.getBody(), null);
        return found;
    }

    /** Every call under {@code root}, in source order, at any depth. */
    public static List<MethodInvocationTree> calls(Tree root) {
        return SubsetWalk.all(root, MethodInvocationTree.class);
    }

    /** Where an update the accumulators do not see sits in the body. */
    private String updatePosition(Tree update, StatementTree body) {
        for (var at = facts.parent(update); at != null && at != body; at = facts.parent(at)) {
            switch (at.getKind()) {
                case LAMBDA_EXPRESSION -> {
                    return "inside a lambda";
                }
                case SWITCH -> {
                    return "inside a switch statement";
                }
                case SWITCH_EXPRESSION -> {
                    return "inside a switch expression";
                }
                case ENHANCED_FOR_LOOP, WHILE_LOOP -> {
                    return "inside a nested loop";
                }
                default -> {
                }
            }
        }
        return facts.parent(update) instanceof ExpressionStatementTree ? "in this position" : "inside an expression";
    }
}
