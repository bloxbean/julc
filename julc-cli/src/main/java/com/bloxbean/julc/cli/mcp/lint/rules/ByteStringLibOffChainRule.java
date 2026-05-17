package com.bloxbean.julc.cli.mcp.lint.rules;

import com.bloxbean.julc.cli.mcp.lint.LintFinding;
import com.bloxbean.julc.cli.mcp.lint.LintRule;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.MethodCallExpr;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Detects calls to {@code ByteStringLib} static methods that are
 * implemented with on-chain-only casts (e.g.
 * {@code (byte[])(Object) Builtins.replicateByte(...)}). These work in
 * compiled validators but throw {@code ClassCastException} when invoked
 * from off-chain JVM code (tests, REPL).
 *
 * <p>The lint is informational: the methods are valid in on-chain code.
 * Agents writing JVM tests should call {@code Builtins.*} directly
 * instead.
 */
public final class ByteStringLibOffChainRule implements LintRule {

    private static final Set<String> OFFCHAIN_BROKEN = Set.of(
            "zeros", "empty", "integerToByteString", "serialiseData");

    @Override
    public String id() {
        return "JULC-LINT-BYTESTRINGLIB-OFFCHAIN";
    }

    @Override
    public String description() {
        return "ByteStringLib.zeros / empty / integerToByteString / serialiseData fail when invoked off-chain";
    }

    @Override
    public List<LintFinding> check(CompilationUnit cu) {
        var findings = new ArrayList<LintFinding>();
        cu.findAll(MethodCallExpr.class).forEach(call -> {
            String name = call.getNameAsString();
            if (!OFFCHAIN_BROKEN.contains(name)) return;
            var scope = call.getScope().orElse(null);
            if (scope == null) return;
            if (!"ByteStringLib".equals(scope.toString())) return;
            int line = call.getBegin().map(p -> p.line).orElse(0);
            int col = call.getBegin().map(p -> p.column).orElse(0);
            String builtinSuggestion = switch (name) {
                case "zeros" -> "`Builtins.replicateByte(n, (byte) 0)`";
                case "empty" -> "`new byte[0]`";
                case "integerToByteString" -> "`Builtins.integerToByteString(...)`";
                case "serialiseData" -> "`Builtins.serialiseData(...)`";
                default -> "the corresponding `Builtins.*` call";
            };
            findings.add(LintFinding.warning(
                    id(),
                    null,
                    "`ByteStringLib." + name + "(...)` uses an on-chain-only cast and throws " +
                            "`ClassCastException` when called from JVM (tests/REPL).",
                    line, col,
                    "On-chain code: leave it. JVM/test code: call " + builtinSuggestion + " directly."
            ));
        });
        return findings;
    }
}
