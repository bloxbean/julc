package org.julclang.compiler.frontend.javac.lower;

import com.sun.source.tree.BlockTree;
import com.sun.source.tree.BreakTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.LabeledStatementTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.YieldTree;
import org.julclang.core.recursion.Trampoline;

import java.util.ArrayDeque;
import java.util.List;
import java.util.function.Predicate;

/**
 * What a statement's shape says about leaving it, read from the tree alone. {@link Ifs} chooses its shape from these. Only
 * statement structure is walked, so a {@code return} in a lambda is not one of these, and evaluation has no order to state.
 * A statement the subset does not hold has no exit here: the scan rejected it.
 */
final class Exits {

    private Exits() {
    }

    /**
     * Whether {@code statement} leaves the method or the enclosing {@code switch} arm, at any depth of blocks and branches: a
     * {@code return}, or a {@code yield}. A {@code switch} in an expression holds its own.
     */
    static boolean holdsExit(StatementTree statement) {
        return holds(statement, leaf -> leaf instanceof ReturnTree || leaf instanceof YieldTree);
    }

    /** Whether {@code statement} holds a loop statement. A loop in an expression has its own value boundary. */
    static boolean holdsLoop(StatementTree statement) {
        return holds(statement, Exits::isLoop);
    }

    /**
     * Whether {@code test} holds of a statement in {@code statement} that is neither a block nor an {@code if}, looking
     * into blocks and both branches of each {@code if}. A work list, not recursion: an {@code else if} chain is as deep
     * as it is long (issue #327). The answer is a pure function of the tree, so the order the statements are met in does
     * not change it.
     */
    private static boolean holds(StatementTree statement, Predicate<StatementTree> test) {
        var pending = new ArrayDeque<StatementTree>();
        pending.push(statement);
        while (!pending.isEmpty()) {
            switch (pending.pop()) {
                case BlockTree block -> block.getStatements().forEach(pending::push);
                case IfTree branch -> {
                    pending.push(branch.getThenStatement());
                    if (branch.getElseStatement() != null) pending.push(branch.getElseStatement());
                }
                case StatementTree leaf -> {
                    if (test.test(leaf)) return true;
                }
            }
        }
        return false;
    }

    /**
     * The reference's check of a non-void method, which is stricter than javac's flow analysis in one place: a bare block
     * is not looked into. A label is not this check's to report: the reference throws at it while generating, which
     * goes before what it collected here. A loop as the last statement counts, by its value: javac's own flow analysis lets
     * only a {@code while (true)} stand there.
     */
    static boolean allPathsReturn(List<? extends StatementTree> statements) {
        return allPaths(statements, ReturnTree.class);
    }

    /** The same check of a {@code switch} arm's block, for a {@code yield}. */
    static boolean allPathsYield(List<? extends StatementTree> statements) {
        return allPaths(statements, YieldTree.class);
    }

    private static boolean allPaths(List<? extends StatementTree> statements, Class<? extends StatementTree> exit) {
        return allPathsFrom(statements, 0, exit).run();
    }

    /** {@link #allPaths} from {@code index}, stack-safe: an {@code else if} chain is as deep as it is long (issue #327). */
    private static Trampoline<Boolean> allPathsFrom(List<? extends StatementTree> statements, int index,
                                                    Class<? extends StatementTree> exit) {
        return Trampoline.defer(() -> {
            if (index >= statements.size()) return Trampoline.done(false);
            var statement = statements.get(index);
            if (exit.isInstance(statement) || statement instanceof LabeledStatementTree) return Trampoline.done(true);
            if (exit == ReturnTree.class && isLoop(statement) && statement == statements.getLast()) return Trampoline.done(true);
            if (!(statement instanceof IfTree branch) || branch.getElseStatement() == null) {
                return allPathsFrom(statements, index + 1, exit);
            }
            return allPathsFrom(blockStatements(branch.getThenStatement()), 0, exit)
                    .then(then -> then ? allPathsFrom(blockStatements(branch.getElseStatement()), 0, exit) : Trampoline.done(false))
                    .then(both -> both ? Trampoline.done(true) : allPathsFrom(statements, index + 1, exit));
        });
    }

    private static boolean isLoop(StatementTree statement) {
        return statement.getKind() == Tree.Kind.ENHANCED_FOR_LOOP || statement.getKind() == Tree.Kind.WHILE_LOOP;
    }

    /**
     * False only when control cannot reach the end of {@code statement} because it ends in a {@code break}. Anything else
     * counts as completing, which costs a join and no more ({@link LoopBody}).
     */
    static boolean canCompleteNormally(StatementTree statement) {
        return completes(statement).run();
    }

    /** {@link #canCompleteNormally}, stack-safe: an {@code else if} chain is as deep as it is long (issue #327). */
    private static Trampoline<Boolean> completes(StatementTree statement) {
        return Trampoline.defer(() -> switch (statement) {
            case BreakTree ignored -> Trampoline.done(false);
            case BlockTree block -> Trampoline.all(block.getStatements(), Exits::completes);
            case IfTree branch -> branch.getElseStatement() == null ? Trampoline.done(true)
                    : completes(branch.getThenStatement()).then(then -> then ? Trampoline.done(true) : completes(branch.getElseStatement()));
            default -> Trampoline.done(true);
        });
    }

    /** A branch's statements: a block's, or the one statement it is. */
    private static List<? extends StatementTree> blockStatements(StatementTree branch) {
        return branch instanceof BlockTree block ? block.getStatements() : List.of(branch);
    }
}
