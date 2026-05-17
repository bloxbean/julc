package com.bloxbean.julc.cli.mcp.lint.rules;

import com.bloxbean.julc.cli.mcp.lint.LintFinding;
import com.bloxbean.julc.cli.mcp.lint.LintRule;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Detects pair-list construction that mixes {@code Builtins.mkNilData()}
 * with pair operations ({@code mkPairData}, {@code fstPair}, {@code sndPair}).
 * Per CLAUDE.md, pair lists must be seeded with {@code mkNilPairData()}
 * (not {@code mkNilData()}), otherwise the Scalus VM rejects the list at
 * runtime because of strict element-type checking.
 *
 * <p>Heuristic: a method that contains BOTH {@code mkNilData()} and any
 * of {@code mkPairData}/{@code fstPair}/{@code sndPair} is overwhelmingly
 * a pair-list mistake. The rule fires once per offending method (on the
 * {@code mkNilData} call site) to avoid noise.
 */
public final class MkConsPairListRule implements LintRule {

    private static final Set<String> PAIR_OPS = Set.of("mkPairData", "fstPair", "sndPair");

    @Override
    public String id() {
        return "JULC-LINT-MKCONS-PAIR-LIST";
    }

    @Override
    public String description() {
        return "Pair-list seeded with mkNilData() — Scalus VM requires mkNilPairData() for pair-typed lists";
    }

    @Override
    public List<LintFinding> check(CompilationUnit cu) {
        var findings = new ArrayList<LintFinding>();
        cu.findAll(MethodDeclaration.class).forEach(method -> {
            MethodCallExpr nilCall = null;
            boolean usesPairOps = false;
            for (var call : method.findAll(MethodCallExpr.class)) {
                String name = call.getNameAsString();
                var scope = call.getScope().orElse(null);
                if (scope == null) continue;
                if (!"Builtins".equals(scope.toString())) continue;
                if ("mkNilData".equals(name) && nilCall == null) {
                    nilCall = call;
                } else if (PAIR_OPS.contains(name)) {
                    usesPairOps = true;
                }
            }
            if (nilCall == null || !usesPairOps) return;
            int line = nilCall.getBegin().map(p -> p.line).orElse(0);
            int col = nilCall.getBegin().map(p -> p.column).orElse(0);
            findings.add(LintFinding.warning(
                    id(),
                    null,
                    "`Builtins.mkNilData()` seed in a method that also uses pair operations. " +
                            "Pair lists must use `Builtins.mkNilPairData()` — the Scalus VM rejects " +
                            "pair elements consed onto a non-pair nil.",
                    line, col,
                    "Replace `Builtins.mkNilData()` with `Builtins.mkNilPairData()`."
            ));
        });
        return findings;
    }
}
