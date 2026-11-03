package org.julclang.cli.mcp.lint.rules;

import org.julclang.cli.mcp.lint.LintFinding;
import org.julclang.cli.mcp.lint.LintRule;
import org.julclang.compiler.frontend.javac.SourceReader;
import org.julclang.cli.mcp.lint.LintTrees;
import org.julclang.compiler.frontend.javac.JavacSyntax;
import com.sun.source.util.TreePath;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.Tree;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.WhileStmt;

import java.util.ArrayList;
import java.util.List;

/**
 * Detects {@code return} inside a {@code while}, {@code for}, {@code for-each}
 * or {@code do-while} loop. JuLC rejects this at compile time with a
 * specific error (see CLAUDE.md "return inside while loop" → FIXED). This
 * lint surfaces the issue earlier with an actionable rewrite hint.
 *
 * <p>Walks each {@link ReturnStmt}'s ancestor chain and stops at the
 * nearest enclosing method/lambda boundary. If a loop is encountered
 * before that boundary, the return is flagged. Lambdas and inner methods
 * shield their own returns from the rule.
 */
public final class ReturnInLoopRule implements LintRule {

    @Override
    public String id() {
        return "JULC-LINT-RETURN-IN-LOOP";
    }

    @Override
    public String description() {
        return "Return inside a loop — JuLC rejects this; use an accumulator + post-loop return instead";
    }

    @Override
    public List<LintFinding> check(CompilationUnit cu) {
        var findings = new ArrayList<LintFinding>();
        cu.findAll(ReturnStmt.class).forEach(ret -> {
            Node n = ret.getParentNode().orElse(null);
            while (n != null) {
                if (n instanceof MethodDeclaration || n instanceof LambdaExpr) {
                    return; // method or lambda boundary; this return is fine
                }
                if (n instanceof WhileStmt
                        || n instanceof ForStmt
                        || n instanceof ForEachStmt
                        || n instanceof DoStmt) {
                    int line = ret.getBegin().map(p -> p.line).orElse(0);
                    int col = ret.getBegin().map(p -> p.column).orElse(0);
                    findings.add(LintFinding.error(
                            id(),
                            null,
                            "`return` inside a loop is not supported in JuLC. The compiler will reject it.",
                            line, col,
                            "Introduce a result accumulator (e.g. `var found = false;`), set it " +
                                    "inside the loop, exit when done, and `return found;` after the loop."
                    ));
                    return;
                }
                n = n.getParentNode().orElse(null);
            }
        });
        return findings;
    }

    @Override
    public boolean supports(SourceReader reader) {
        return true;
    }

    @Override
    public List<LintFinding> check(JavacSyntax syntax) {
        var findings = new ArrayList<LintFinding>();
        for (TreePath ret : LintTrees.findAll(syntax, ReturnTree.class)) {
            for (TreePath n = ret.getParentPath(); n != null; n = n.getParentPath()) {
                Tree leaf = n.getLeaf();
                if ((leaf instanceof MethodTree method && !method.getName().contentEquals("<init>"))
                        || leaf instanceof LambdaExpressionTree) {
                    break; // method or lambda boundary; this return is fine
                }
                if (leaf.getKind() == Tree.Kind.WHILE_LOOP
                        || leaf.getKind() == Tree.Kind.FOR_LOOP
                        || leaf.getKind() == Tree.Kind.ENHANCED_FOR_LOOP
                        || leaf.getKind() == Tree.Kind.DO_WHILE_LOOP) {
                    findings.add(LintFinding.error(
                            id(),
                            null,
                            "`return` inside a loop is not supported in JuLC. The compiler will reject it.",
                            syntax.line(ret.getLeaf()), syntax.column(ret.getLeaf()),
                            "Introduce a result accumulator (e.g. `var found = false;`), set it " +
                                    "inside the loop, exit when done, and `return found;` after the loop."
                    ));
                    break;
                }
            }
        }
        return findings;
    }
}
