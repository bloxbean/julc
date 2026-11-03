package org.julclang.cli.mcp.lint.rules;

import org.julclang.cli.mcp.lint.LintFinding;
import org.julclang.cli.mcp.lint.LintRule;
import org.julclang.cli.mcp.lint.LintTrees;
import org.julclang.compiler.frontend.javac.JavacSyntax;
import com.sun.source.util.TreePath;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.CaseTree;
import com.sun.source.tree.ForLoopTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TryTree;
import com.sun.source.tree.VariableTree;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.ForEachStmt;

import java.util.ArrayList;
import java.util.List;

/**
 * Detects local variable declarations with no initializer
 * ({@code int x;}, {@code BigInteger total;}). JuLC requires
 * every local to be initialized at its declaration site (no separate
 * declare-then-assign). The compiler rejects this; the lint surfaces
 * it earlier.
 *
 * <p>Only flags locals (statements). Field declarations, method
 * parameters, and {@code for}-loop control variables are out of scope.
 */
public final class UninitializedVarRule implements LintRule {

    @Override
    public String id() {
        return "JULC-LINT-UNINITIALIZED-VAR";
    }

    @Override
    public String description() {
        return "Local variable declared without an initializer — JuLC requires immediate assignment";
    }

    @Override
    public List<LintFinding> check(CompilationUnit cu) {
        var findings = new ArrayList<LintFinding>();
        cu.findAll(VariableDeclarationExpr.class).forEach(expr -> {
            if (isEnhancedForVariable(expr)) return;
            for (var v : expr.getVariables()) {
                if (v.getInitializer().isPresent()) continue;
                int line = v.getBegin().map(p -> p.line).orElse(0);
                int col = v.getBegin().map(p -> p.column).orElse(0);
                findings.add(LintFinding.error(
                        id(),
                        null,
                        "Local variable `" + v.getNameAsString() + "` declared without an initializer. " +
                                "JuLC has no separate declare-then-assign — every local must be " +
                                "initialized at declaration.",
                        line, col,
                        "Initialize at the declaration: `var " + v.getNameAsString() + " = ...;`"
                ));
            }
        });
        return findings;
    }

    private static boolean isEnhancedForVariable(VariableDeclarationExpr expr) {
        return expr.findAncestor(ForEachStmt.class)
                .map(forEach -> forEach.getVariable() == expr)
                .orElse(false);
    }

    @Override
    public List<LintFinding> check(JavacSyntax syntax) {
        var findings = new ArrayList<LintFinding>();
        for (TreePath path : LintTrees.findAll(syntax, VariableTree.class)) {
            if (!isLocalDeclaration(path)) continue;
            var v = (VariableTree) path.getLeaf();
            if (v.getInitializer() != null) continue;
            long name = LintTrees.declaratorNameStart(syntax, path);
            findings.add(LintFinding.error(
                    id(),
                    null,
                    "Local variable `" + v.getName() + "` declared without an initializer. " +
                            "JuLC has no separate declare-then-assign — every local must be " +
                            "initialized at declaration.",
                    syntax.line(name), syntax.column(name),
                    "Initialize at the declaration: `var " + v.getName() + " = ...;`"
            ));
        }
        return findings;
    }

    /**
     * What JavaParser reads as a variable declaration expression: a local declaration statement, a {@code for} header's
     * or a resource; an enhanced {@code for}'s variable is left out, as above.
     */
    private static boolean isLocalDeclaration(TreePath path) {
        Tree parent = path.getParentPath().getLeaf();
        return parent instanceof BlockTree || parent instanceof CaseTree
                || (parent instanceof ForLoopTree loop && loop.getInitializer().contains(path.getLeaf()))
                || (parent instanceof TryTree attempt && attempt.getResources().contains(path.getLeaf()));
    }
}
