package org.julclang.compiler.frontend.javac.lower;

import com.sun.source.tree.BlockTree;
import com.sun.source.tree.BreakTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.LabeledStatementTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.YieldTree;

import java.util.List;

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
        return switch (statement) {
            case ReturnTree ignored -> true;
            case YieldTree ignored -> true;
            case BlockTree block -> block.getStatements().stream().anyMatch(Exits::holdsExit);
            case IfTree branch -> holdsExit(branch.getThenStatement())
                    || branch.getElseStatement() != null && holdsExit(branch.getElseStatement());
            default -> false;
        };
    }

    /** Whether {@code statement} holds a loop statement. A loop in an expression has its own value boundary. */
    static boolean holdsLoop(StatementTree statement) {
        return switch (statement) {
            case BlockTree block -> block.getStatements().stream().anyMatch(Exits::holdsLoop);
            case IfTree branch -> holdsLoop(branch.getThenStatement())
                    || branch.getElseStatement() != null && holdsLoop(branch.getElseStatement());
            default -> isLoop(statement);
        };
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
        for (var statement : statements) {
            if (exit.isInstance(statement) || statement instanceof LabeledStatementTree) return true;
            if (exit == ReturnTree.class && isLoop(statement) && statement == statements.getLast()) return true;
            if (statement instanceof IfTree branch && branch.getElseStatement() != null
                    && allPaths(blockStatements(branch.getThenStatement()), exit)
                    && allPaths(blockStatements(branch.getElseStatement()), exit)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLoop(StatementTree statement) {
        return statement.getKind() == Tree.Kind.ENHANCED_FOR_LOOP || statement.getKind() == Tree.Kind.WHILE_LOOP;
    }

    /**
     * False only when control cannot reach the end of {@code statement} because it ends in a {@code break}. Anything else
     * counts as completing, which costs a join and no more ({@link LoopBody}).
     */
    static boolean canCompleteNormally(StatementTree statement) {
        return switch (statement) {
            case BreakTree ignored -> false;
            case BlockTree block -> block.getStatements().stream().allMatch(Exits::canCompleteNormally);
            case IfTree branch -> branch.getElseStatement() == null || canCompleteNormally(branch.getThenStatement())
                    || canCompleteNormally(branch.getElseStatement());
            default -> true;
        };
    }

    /** A branch's statements: a block's, or the one statement it is. */
    private static List<? extends StatementTree> blockStatements(StatementTree branch) {
        return branch instanceof BlockTree block ? block.getStatements() : List.of(branch);
    }
}
