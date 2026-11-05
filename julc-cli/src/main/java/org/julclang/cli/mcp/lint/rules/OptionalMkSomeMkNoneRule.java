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
    public List<LintFinding> check(JavacSyntax syntax) {
        var findings = new ArrayList<LintFinding>();
        for (TreePath path : LintTrees.findAll(syntax, MethodInvocationTree.class)) {
            var call = (MethodInvocationTree) path.getLeaf();
            String name = LintTrees.methodName(call);
            if (!BAD_NAMES.contains(name)) continue;
            if (!"Optional".equals(LintTrees.dottedName(LintTrees.receiver(call)))) continue;
            String fix = name.equals("mkSome")
                    ? "Replace `Optional.mkSome(x)` with `Optional.of(x)`."
                    : "Replace `Optional.mkNone()` with `Optional.empty()`.";
            findings.add(LintFinding.error(
                    id(),
                    null,
                    "Optional." + name + "(...) is not a JuLC API. The real factories are " +
                            "Optional.of(x) and Optional.empty().",
                    syntax.line(call), syntax.column(call), fix
            ));
        }
        return findings;
    }
}
