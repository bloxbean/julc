package com.bloxbean.julc.cli.mcp.lint.rules;

import com.bloxbean.julc.cli.mcp.lint.LintFinding;
import com.bloxbean.julc.cli.mcp.lint.LintRule;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;

import java.util.ArrayList;
import java.util.List;

/**
 * Detects {@code something.hash().hash()} patterns. {@code PubKeyHash},
 * {@code ScriptHash}, {@code ValidatorHash}, etc. already wrap a
 * {@code byte[]}; calling {@code .hash()} returns the bytes. Calling
 * {@code .hash()} a second time would attempt to invoke {@code hash()}
 * on a {@code byte[]} (which has no such method) — but the wrong fix
 * compiles in some on-chain contexts because of how method dispatch is
 * resolved through the {@code hofUnwrappedVars} mechanism. Catching this
 * at lint time prevents the confusion.
 */
public final class DoubleHashRule implements LintRule {

    @Override
    public String id() {
        return "JULC-LINT-DOUBLE-HASH";
    }

    @Override
    public String description() {
        return "Calling .hash() twice on a hash-wrapper type — .hash() already returns the byte[]";
    }

    @Override
    public List<LintFinding> check(CompilationUnit cu) {
        var findings = new ArrayList<LintFinding>();
        cu.findAll(MethodCallExpr.class).forEach(outer -> {
            if (!"hash".equals(outer.getNameAsString())) return;
            // Unwrap parenthesized expressions: `(x.hash()).hash()` parses as
            // outer-scope = EnclosedExpr(MethodCallExpr) — direct instanceof
            // miss without this. Codex P2.
            Expression scope = outer.getScope().orElse(null);
            while (scope instanceof EnclosedExpr enc) {
                scope = enc.getInner();
            }
            if (!(scope instanceof MethodCallExpr inner)) return;
            if (!"hash".equals(inner.getNameAsString())) return;
            int line = outer.getBegin().map(p -> p.line).orElse(0);
            int col = outer.getBegin().map(p -> p.column).orElse(0);
            findings.add(LintFinding.warning(
                    id(),
                    null,
                    "Double `.hash()` call. The first `.hash()` already returns the underlying " +
                            "byte[]; the second call is either a no-op or a type error.",
                    line, col,
                    "Drop one `.hash()`. E.g. replace `pkh.hash().hash()` with `pkh.hash()`."
            ));
        });
        return findings;
    }
}
