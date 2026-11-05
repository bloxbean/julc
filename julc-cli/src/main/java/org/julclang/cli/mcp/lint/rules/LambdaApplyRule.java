package org.julclang.cli.mcp.lint.rules;

import org.julclang.cli.mcp.lint.LintFinding;
import org.julclang.cli.mcp.lint.LintRule;
import org.julclang.cli.mcp.lint.LintTrees;
import org.julclang.compiler.frontend.javac.JavacSyntax;
import com.sun.source.util.TreePath;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.VariableTree;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Detects {@code lambdaVar.apply(...)} — calling a stored lambda. JuLC
 * does not support lambdas as first-class values; lambdas are only
 * accepted as inline arguments to higher-order builtins (e.g.
 * {@code list.map(x -> ...)}). Storing a lambda in a variable and
 * later invoking it via {@code .apply(...)} is rejected by the compiler.
 *
 * <p>Conservative heuristic: per method, find local variables whose
 * initializer is a {@link LambdaExpr}. Then flag any
 * {@code <name>.apply(...)} call where the receiver matches one of
 * those locals. This avoids false positives on legitimate
 * {@code something.apply(...)} usages elsewhere.
 */
public final class LambdaApplyRule implements LintRule {

    @Override
    public String id() {
        return "JULC-LINT-LAMBDA-APPLY";
    }

    @Override
    public String description() {
        return "Calling .apply() on a stored lambda — JuLC only accepts lambdas inline as HOF arguments";
    }

    @Override
    public List<LintFinding> check(JavacSyntax syntax) {
        var findings = new ArrayList<LintFinding>();
        for (TreePath method : LintTrees.methods(syntax)) {
            Set<String> lambdaLocals = new HashSet<>();
            for (TreePath path : LintTrees.findAll(method, VariableTree.class)) {
                if (LintTrees.isDeclarator(path)
                        && ((VariableTree) path.getLeaf()).getInitializer() instanceof LambdaExpressionTree) {
                    lambdaLocals.add(((VariableTree) path.getLeaf()).getName().toString());
                }
            }
            if (lambdaLocals.isEmpty()) continue;

            for (TreePath path : LintTrees.findAll(method, MethodInvocationTree.class)) {
                var call = (MethodInvocationTree) path.getLeaf();
                if (!"apply".equals(LintTrees.methodName(call))) continue;
                if (!(LintTrees.receiver(call) instanceof IdentifierTree ne)) continue;
                if (!lambdaLocals.contains(ne.getName().toString())) continue;
                findings.add(LintFinding.error(
                        id(),
                        null,
                        "`" + ne.getName() + ".apply(...)` invokes a stored lambda — " +
                                "JuLC does not support first-class lambda values.",
                        syntax.line(call), syntax.column(call),
                        "Inline the lambda at the call site of the higher-order function. " +
                                "E.g. instead of `var f = x -> x.add(ONE); list.map(f);` write " +
                                "`list.map(x -> x.add(ONE));`."
                ));
            }
        }
        return findings;
    }
}
