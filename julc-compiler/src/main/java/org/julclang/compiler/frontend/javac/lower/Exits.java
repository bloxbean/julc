package org.julclang.compiler.frontend.javac.lower;

import com.sun.source.tree.BlockTree;
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
 * A statement no slice has ported holds no exit here, and its lowering stops at {@code JULC0084}.
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
            default -> statement.getKind() == Tree.Kind.ENHANCED_FOR_LOOP || statement.getKind() == Tree.Kind.WHILE_LOOP;
        };
    }

    /**
     * The reference's check of a non-void method, which is stricter than javac's flow analysis in one place: a bare block
     * is not looked into. A label is not this check's to report: the reference throws at it while generating, which
     * goes before what it collected here. The case of a loop as the last statement comes with loops.
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
            if (statement instanceof IfTree branch && branch.getElseStatement() != null
                    && allPaths(blockStatements(branch.getThenStatement()), exit)
                    && allPaths(blockStatements(branch.getElseStatement()), exit)) {
                return true;
            }
        }
        return false;
    }

    /** A branch's statements: a block's, or the one statement it is. */
    private static List<? extends StatementTree> blockStatements(StatementTree branch) {
        return branch instanceof BlockTree block ? block.getStatements() : List.of(branch);
    }
}
