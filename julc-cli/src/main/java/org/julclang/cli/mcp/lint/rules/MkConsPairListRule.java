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
    public List<LintFinding> check(JavacSyntax syntax) {
        var findings = new ArrayList<LintFinding>();
        for (TreePath method : LintTrees.methods(syntax)) {
            MethodInvocationTree nilCall = null;
            boolean usesPairOps = false;
            for (TreePath path : LintTrees.findAll(method, MethodInvocationTree.class)) {
                var call = (MethodInvocationTree) path.getLeaf();
                String name = LintTrees.methodName(call);
                if (!"Builtins".equals(LintTrees.dottedName(LintTrees.receiver(call)))) continue;
                if ("mkNilData".equals(name) && nilCall == null) {
                    nilCall = call;
                } else if (PAIR_OPS.contains(name)) {
                    usesPairOps = true;
                }
            }
            if (nilCall == null || !usesPairOps) continue;
            findings.add(LintFinding.warning(
                    id(),
                    null,
                    "`Builtins.mkNilData()` seed in a method that also uses pair operations. " +
                            "Pair lists must use `Builtins.mkNilPairData()` — the Scalus VM rejects " +
                            "pair elements consed onto a non-pair nil.",
                    syntax.line(nilCall), syntax.column(nilCall),
                    "Replace `Builtins.mkNilData()` with `Builtins.mkNilPairData()`."
            ));
        }
        return findings;
    }
}
