package org.julclang.cli.mcp.lint.rules;

import org.julclang.cli.mcp.lint.LintFinding;
import org.julclang.cli.mcp.lint.LintRule;
import org.julclang.cli.mcp.lint.LintTrees;
import org.julclang.compiler.frontend.javac.JavacSyntax;
import com.sun.source.util.TreePath;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.Tree;

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
    public List<LintFinding> check(JavacSyntax syntax) {
        var findings = new ArrayList<LintFinding>();
        for (TreePath path : LintTrees.findAll(syntax, MethodInvocationTree.class)) {
            var outer = (MethodInvocationTree) path.getLeaf();
            if (!"hash".equals(LintTrees.methodName(outer))) continue;
            Tree scope = LintTrees.receiver(outer);
            while (scope instanceof ParenthesizedTree parens) {
                scope = parens.getExpression();
            }
            if (!(scope instanceof MethodInvocationTree inner)) continue;
            if (!"hash".equals(LintTrees.methodName(inner))) continue;
            findings.add(LintFinding.warning(
                    id(),
                    null,
                    "Double `.hash()` call. The first `.hash()` already returns the underlying " +
                            "byte[]; the second call is either a no-op or a type error.",
                    syntax.line(outer), syntax.column(outer),
                    "Drop one `.hash()`. E.g. replace `pkh.hash().hash()` with `pkh.hash()`."
            ));
        }
        return findings;
    }
}
