package com.bloxbean.julc.cli.mcp.lint.rules;

import com.bloxbean.julc.cli.mcp.lint.LintFinding;
import com.bloxbean.julc.cli.mcp.lint.LintRule;
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
}
