package org.julclang.cli.mcp.lint.rules;

import org.julclang.cli.mcp.lint.LintFinding;
import org.julclang.cli.mcp.lint.LintRule;
import org.julclang.cli.mcp.lint.LintTrees;
import org.julclang.compiler.frontend.javac.JavacSyntax;
import com.sun.source.util.TreePath;
import com.sun.source.tree.Tree;
import com.sun.source.tree.UnaryTree;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.UnaryExpr;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Detects {@code ++} / {@code --} operators (prefix or postfix). JuLC is
 * single-assignment; mutating an existing variable is rejected by the
 * compiler. {@link MutableVarRule} catches {@code x = x + 1} because it
 * uses {@code AssignExpr}, but {@code i++} parses as
 * {@link UnaryExpr} and slips past — this rule fills that gap.
 */
public final class IncrementDecrementRule implements LintRule {

    private static final Set<UnaryExpr.Operator> MUTATING = Set.of(
            UnaryExpr.Operator.PREFIX_INCREMENT,
            UnaryExpr.Operator.POSTFIX_INCREMENT,
            UnaryExpr.Operator.PREFIX_DECREMENT,
            UnaryExpr.Operator.POSTFIX_DECREMENT
    );

    @Override
    public String id() {
        return "JULC-LINT-INCREMENT";
    }

    @Override
    public String description() {
        return "Increment / decrement operators (++ / --) — JuLC is single-assignment";
    }

    @Override
    public List<LintFinding> check(CompilationUnit cu) {
        var findings = new ArrayList<LintFinding>();
        cu.findAll(UnaryExpr.class).forEach(u -> {
            if (!MUTATING.contains(u.getOperator())) return;
            int line = u.getBegin().map(p -> p.line).orElse(0);
            int col = u.getBegin().map(p -> p.column).orElse(0);
            findings.add(LintFinding.error(
                    id(),
                    null,
                    "`" + u + "` mutates an existing variable. JuLC is single-assignment.",
                    line, col,
                    "Introduce a new binding: `var i2 = i.add(BigInteger.ONE);`. " +
                            "For loop counters use `var i = i.add(BigInteger.ONE);` inside the " +
                            "while-loop accumulator pattern."
            ));
        });
        return findings;
    }

    private static final Set<Tree.Kind> MUTATING_KINDS = Set.of(
            Tree.Kind.PREFIX_INCREMENT,
            Tree.Kind.POSTFIX_INCREMENT,
            Tree.Kind.PREFIX_DECREMENT,
            Tree.Kind.POSTFIX_DECREMENT
    );

    @Override
    public List<LintFinding> check(JavacSyntax syntax) {
        var findings = new ArrayList<LintFinding>();
        for (TreePath path : LintTrees.findAll(syntax, UnaryTree.class)) {
            var u = (UnaryTree) path.getLeaf();
            if (!MUTATING_KINDS.contains(u.getKind())) continue;
            findings.add(LintFinding.error(
                    id(),
                    null,
                    "`" + u + "` mutates an existing variable. JuLC is single-assignment.",
                    syntax.line(u), syntax.column(u),
                    "Introduce a new binding: `var i2 = i.add(BigInteger.ONE);`. " +
                            "For loop counters use `var i = i.add(BigInteger.ONE);` inside the " +
                            "while-loop accumulator pattern."
            ));
        }
        return findings;
    }
}
