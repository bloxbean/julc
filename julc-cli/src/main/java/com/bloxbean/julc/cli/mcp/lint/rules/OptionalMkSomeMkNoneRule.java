package com.bloxbean.julc.cli.mcp.lint.rules;

import com.bloxbean.julc.cli.mcp.lint.LintFinding;
import com.bloxbean.julc.cli.mcp.lint.LintRule;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.MethodCallExpr;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Catches the canonical AI hallucination: {@code Optional.mkSome(...)} or
 * {@code Optional.mkNone()}. The real JuLC API is {@code Optional.of(x)} /
 * {@code Optional.empty()}; {@code mkSome}/{@code mkNone} are internal
 * {@code PirHelpers} methods, not user-facing.
 *
 * <p>Catching this at lint time is essential — at compile time it manifests
 * as a generic "unknown method" error that doesn't tell the agent which
 * substitution to make.
 */
public final class OptionalMkSomeMkNoneRule implements LintRule {

    private static final Set<String> BAD_NAMES = Set.of("mkSome", "mkNone");

    @Override
    public String id() {
        return "JULC-LINT-OPTIONAL-API";
    }

    @Override
    public String description() {
        return "Optional.mkSome / Optional.mkNone do not exist — use Optional.of(x) / Optional.empty()";
    }

    @Override
    public List<LintFinding> check(CompilationUnit cu) {
        var findings = new ArrayList<LintFinding>();
        cu.findAll(MethodCallExpr.class).forEach(call -> {
            String name = call.getNameAsString();
            if (!BAD_NAMES.contains(name)) return;
            // Only fire when the receiver is named "Optional".
            var scope = call.getScope().orElse(null);
            if (scope == null) return;
            String scopeStr = scope.toString();
            if (!"Optional".equals(scopeStr)) return;
            int line = call.getBegin().map(p -> p.line).orElse(0);
            int col = call.getBegin().map(p -> p.column).orElse(0);
            String fix = name.equals("mkSome")
                    ? "Replace `Optional.mkSome(x)` with `Optional.of(x)`."
                    : "Replace `Optional.mkNone()` with `Optional.empty()`.";
            findings.add(LintFinding.error(
                    id(),
                    null, // No JULC code yet; this is a lint-only check.
                    "Optional." + name + "(...) is not a JuLC API. The real factories are " +
                            "Optional.of(x) and Optional.empty().",
                    line, col, fix
            ));
        });
        return findings;
    }
}
