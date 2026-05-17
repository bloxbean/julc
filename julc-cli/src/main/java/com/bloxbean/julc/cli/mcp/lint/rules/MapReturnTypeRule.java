package com.bloxbean.julc.cli.mcp.lint.rules;

import com.bloxbean.julc.cli.mcp.lint.LintFinding;
import com.bloxbean.julc.cli.mcp.lint.LintRule;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.MethodCallExpr;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Detects {@code <list>.map(<lambda>).<accessor>()} chains. Per
 * CLAUDE.md, {@code map()} returns {@code ListType(DataType)} regardless
 * of the lambda's result type — so the {@code .head()}/{@code .get(...)}
 * accessor returns raw {@code Data}, not the expected primitive. Using
 * the result directly in arithmetic or comparison silently produces wrong
 * answers.
 *
 * <p>Heuristic: detect {@code .map(...).head()} and
 * {@code .map(...).get(...)} chains. Emit an info-level finding so the
 * agent knows to wrap the accessor in {@code Builtins.unIData(...)} or
 * {@code Builtins.unBData(...)} when the element type is integer or bytes.
 */
public final class MapReturnTypeRule implements LintRule {

    private static final Set<String> ACCESSORS = Set.of("head", "get");

    @Override
    public String id() {
        return "JULC-LINT-MAP-RETURN-TYPE";
    }

    @Override
    public String description() {
        return "list.map(...).head()/get() returns Data — wrap with Builtins.unIData/unBData if you need a primitive";
    }

    @Override
    public List<LintFinding> check(CompilationUnit cu) {
        var findings = new ArrayList<LintFinding>();
        cu.findAll(MethodCallExpr.class).forEach(outer -> {
            if (!ACCESSORS.contains(outer.getNameAsString())) return;
            var scope = outer.getScope().orElse(null);
            if (!(scope instanceof MethodCallExpr inner)) return;
            if (!"map".equals(inner.getNameAsString())) return;
            int line = outer.getBegin().map(p -> p.line).orElse(0);
            int col = outer.getBegin().map(p -> p.column).orElse(0);
            findings.add(new LintFinding(
                    id(),
                    null,
                    "info",
                    "`.map(...)." + outer.getNameAsString() + "(...)` — `map` always returns " +
                            "`ListType(DataType)`, so this accessor produces raw Data, not the " +
                            "lambda's apparent result type.",
                    line, col,
                    "If you need an integer: `Builtins.unIData(<expr>)`. " +
                            "If you need bytes: `Builtins.unBData(<expr>)`. " +
                            "If you need Data, leave as-is."
            ));
        });
        return findings;
    }
}
