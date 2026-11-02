package org.julclang.compiler.frontend.javac.lower;

import com.sun.source.tree.BlockTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.WhileLoopTree;
import org.julclang.compiler.frontend.javac.JavacFacts;
import org.julclang.compiler.frontend.javac.subset.LoopRules;

import javax.lang.model.element.Element;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What a loop carries from one iteration to the next: the variables visible before it that its statements assign, in the
 * order they are first assigned. An element is visible before the loop or it is not, so a local of the body, the loop's
 * variable and a field with no binder are none of these, and no name decides it. Only statements are walked: an assignment
 * in an expression, a {@code switch} arm or a lambda is no update of the loop's. This builds no term, so it has no
 * evaluation order to state.
 */
final class Accumulators {

    private final JavacFacts facts;

    Accumulators(JavacFacts facts) {
        this.facts = facts;
    }

    /** The accumulators of {@code loop}, a for-each or a {@code while}, lowered in {@code frame}. */
    List<Element> of(StatementTree loop, Frame frame) {
        var found = new LinkedHashSet<Element>();
        collect(body(loop), frame, found);
        return List.copyOf(found);
    }

    /** What the loops in an {@code if}'s branches update of what is visible around it, which the branches hand on (ADR-050). */
    List<Element> ofBranches(IfTree tree, Frame frame) {
        var found = new LinkedHashSet<Element>();
        inBranch(tree, false, frame, found);
        return new ArrayList<>(found);
    }

    private static StatementTree body(StatementTree loop) {
        return switch (loop) {
            case WhileLoopTree tree -> tree.getStatement();
            case EnhancedForLoopTree tree -> tree.getStatement();
            default -> throw new IllegalArgumentException("Not a loop: " + loop.getKind());
        };
    }

    private void collect(StatementTree statement, Frame frame, Set<Element> found) {
        switch (statement) {
            case ExpressionStatementTree expression -> add(expression, frame, found);
            case IfTree branch -> {
                collect(branch.getThenStatement(), frame, found);
                if (branch.getElseStatement() != null) collect(branch.getElseStatement(), frame, found);
            }
            case BlockTree block -> block.getStatements().forEach(inner -> collect(inner, frame, found));
            case WhileLoopTree loop -> collect(loop.getStatement(), frame, found);
            case EnhancedForLoopTree loop -> collect(loop.getStatement(), frame, found);
            default -> {
            }
        }
    }

    private void inBranch(StatementTree statement, boolean inLoop, Frame frame, Set<Element> found) {
        switch (statement) {
            case BlockTree block -> block.getStatements().forEach(inner -> inBranch(inner, inLoop, frame, found));
            case IfTree branch -> {
                inBranch(branch.getThenStatement(), inLoop, frame, found);
                if (branch.getElseStatement() != null) inBranch(branch.getElseStatement(), inLoop, frame, found);
            }
            case EnhancedForLoopTree loop -> inBranch(loop.getStatement(), true, frame, found);
            case WhileLoopTree loop -> inBranch(loop.getStatement(), true, frame, found);
            case ExpressionStatementTree expression when inLoop -> add(expression, frame, found);
            default -> {
            }
        }
    }

    private void add(ExpressionStatementTree statement, Frame frame, Set<Element> found) {
        var target = LoopRules.updated(statement.getExpression());
        if (target == null) return;
        var element = facts.element(target);
        if (frame.scope().lookup(element) != null) found.add(element);
    }
}
