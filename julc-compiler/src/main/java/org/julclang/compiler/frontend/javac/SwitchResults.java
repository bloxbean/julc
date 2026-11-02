package org.julclang.compiler.frontend.javac;

import com.sun.source.tree.CaseTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.SwitchExpressionTree;
import com.sun.source.tree.YieldTree;
import com.sun.source.util.TreeScanner;

import java.util.ArrayList;
import java.util.List;

/**
 * What a {@code switch} expression can give, in source order: the expression of each arrow arm, and the value of each
 * {@code yield} that targets this switch. A {@code yield} in a nested {@code switch} expression is that switch's. javac
 * types a {@code switch} in an argument by the parameter, so what it can be is read here.
 */
public final class SwitchResults {

    private SwitchResults() {
    }

    public static List<ExpressionTree> of(SwitchExpressionTree tree) {
        var results = new ArrayList<ExpressionTree>();
        for (var entry : tree.getCases()) {
            if (entry.getCaseKind() == CaseTree.CaseKind.RULE && entry.getBody() instanceof ExpressionTree value) {
                results.add(value);
                continue;
            }
            var yields = new TreeScanner<Void, Void>() {
                @Override
                public Void visitYield(YieldTree yield, Void unused) {
                    results.add(yield.getValue());
                    return null;
                }

                @Override
                public Void visitSwitchExpression(SwitchExpressionTree nested, Void unused) {
                    return null;
                }

                @Override
                public Void visitLambdaExpression(LambdaExpressionTree lambda, Void unused) {
                    return null;
                }

                @Override
                public Void visitClass(ClassTree type, Void unused) {
                    return null;
                }
            };
            if (entry.getCaseKind() == CaseTree.CaseKind.RULE) {
                yields.scan(entry.getBody(), null);
            } else {
                yields.scan(entry.getStatements(), null);
            }
        }
        return results;
    }
}
