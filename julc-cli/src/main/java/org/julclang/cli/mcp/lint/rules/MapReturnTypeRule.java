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
    public List<LintFinding> check(JavacSyntax syntax) {
        var findings = new ArrayList<LintFinding>();
        for (TreePath path : LintTrees.findAll(syntax, MethodInvocationTree.class)) {
            var outer = (MethodInvocationTree) path.getLeaf();
            String name = LintTrees.methodName(outer);
            if (!ACCESSORS.contains(name)) continue;
            if (!(LintTrees.receiver(outer) instanceof MethodInvocationTree inner)) continue;
            if (!"map".equals(LintTrees.methodName(inner))) continue;
            findings.add(new LintFinding(
                    id(),
                    null,
                    "info",
                    "`.map(...)." + name + "(...)` — `map` always returns " +
                            "`ListType(DataType)`, so this accessor produces raw Data, not the " +
                            "lambda's apparent result type.",
                    syntax.line(outer), syntax.column(outer),
                    "If you need an integer: `Builtins.unIData(<expr>)`. " +
                            "If you need bytes: `Builtins.unBData(<expr>)`. " +
                            "If you need Data, leave as-is."
            ));
        }
        return findings;
    }
}
