package org.julclang.cli.mcp.lint.rules;

import org.julclang.cli.mcp.lint.LintFinding;
import org.julclang.cli.mcp.lint.LintRule;
import org.julclang.cli.mcp.lint.LintTrees;
import org.julclang.compiler.frontend.javac.JavacSyntax;
import com.sun.source.util.TreePath;
import com.sun.source.tree.MethodInvocationTree;

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
    public List<LintFinding> check(JavacSyntax syntax) {
        var findings = new ArrayList<LintFinding>();
        for (TreePath path : LintTrees.findAll(syntax, MethodInvocationTree.class)) {
            var call = (MethodInvocationTree) path.getLeaf();
            String name = LintTrees.methodName(call);
            if (!OFFCHAIN_BROKEN.contains(name)) continue;
            if (!"ByteStringLib".equals(LintTrees.dottedName(LintTrees.receiver(call)))) continue;
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
                    syntax.line(call), syntax.column(call),
                    "On-chain code: leave it. JVM/test code: call " + builtinSuggestion + " directly."
            ));
        }
        return findings;
    }
}
