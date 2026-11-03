package org.julclang.cli.mcp.lint.rules;

import org.julclang.cli.mcp.lint.LintFinding;
import org.julclang.cli.mcp.lint.LintRule;
import org.julclang.cli.mcp.lint.LintTrees;
import org.julclang.compiler.frontend.javac.JavacSyntax;
import com.sun.source.util.TreePath;
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.WhileLoopTree;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.WhileStmt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Detects assignment to a local variable after its declaration — outside
 * of a {@code while}-loop accumulator pattern (which JuLC explicitly
 * supports). On-chain JuLC is single-assignment; the compiler rejects
 * mutation but with a confusing error. This lint surfaces the issue
 * pre-compile with a clear fix.
 *
 * <p>Heuristic per method: collect each local variable's declaration site;
 * scan for {@code AssignExpr} statements where the target is a
 * {@code NameExpr} matching one of those locals. If the assignment is
 * inside a {@code while} loop AND the variable is also referenced as the
 * accumulator in the loop condition or body, allow it (that's the
 * supported pattern). Otherwise flag.
 *
 * <p>This is an approximation — the real compiler check is more nuanced —
 * but it catches the common AI mistake of writing
 * <pre>{@code
 *   var x = BigInteger.ZERO;
 *   x = x.add(BigInteger.ONE);  // ← rejected
 * }</pre>
 * with an actionable suggestion instead of letting the compiler fail late.
 */
public final class MutableVarRule implements LintRule {

    @Override
    public String id() {
        return "JULC-LINT-MUTABLE-VAR";
    }

    @Override
    public String description() {
        return "Mutable variable assignment outside a while-loop accumulator — JuLC is single-assignment";
    }

    @Override
    public List<LintFinding> check(CompilationUnit cu) {
        var findings = new ArrayList<LintFinding>();
        cu.findAll(MethodDeclaration.class).forEach(method -> {
            // Collect locals declared in this method's body.
            Map<String, VariableDeclarator> locals = new HashMap<>();
            method.findAll(VariableDeclarator.class).forEach(v ->
                    locals.put(v.getNameAsString(), v));
            if (locals.isEmpty()) return;

            // Build set of variable names that are accumulators (declared
            // before a while loop and assigned inside it). For accumulator-
            // assignments we allow the assignment.
            Set<String> whileAccumulators = collectWhileAccumulators(method, locals.keySet());

            method.findAll(AssignExpr.class).forEach(assign -> {
                if (!(assign.getTarget() instanceof NameExpr ne)) return;
                String name = ne.getNameAsString();
                if (!locals.containsKey(name)) return;
                if (whileAccumulators.contains(name)) return;
                int line = assign.getBegin().map(p -> p.line).orElse(0);
                int col = assign.getBegin().map(p -> p.column).orElse(0);
                findings.add(LintFinding.warning(
                        id(),
                        null,
                        "Re-assignment to local variable `" + name + "` after declaration. " +
                                "JuLC on-chain code is single-assignment; the compiler will reject this. " +
                                "(Inside a while-loop accumulator pattern, re-assignment IS allowed; " +
                                "this lint runs a heuristic and may need a manual override.)",
                        line, col,
                        "Introduce a new variable: `var " + name + "2 = " + name + ".add(...)`. " +
                                "Or, if you need an accumulator, wrap the loop body in a `while`."
                ));
            });
        });
        return findings;
    }

    /**
     * Names of locals that look like while-loop accumulators: declared in
     * the method, assigned inside the body of at least one {@code while}.
     * Conservatively whitelisted so the rule doesn't false-positive on the
     * canonical {@code while (cond) { acc = acc.add(...); }} pattern.
     */
    private static Set<String> collectWhileAccumulators(MethodDeclaration method, Set<String> locals) {
        Set<String> acc = new HashSet<>();
        method.findAll(WhileStmt.class).forEach(loop ->
                loop.findAll(AssignExpr.class).forEach(assign -> {
                    if (assign.getTarget() instanceof NameExpr ne
                            && locals.contains(ne.getNameAsString())) {
                        acc.add(ne.getNameAsString());
                    }
                }));
        return acc;
    }

    @Override
    public List<LintFinding> check(JavacSyntax syntax) {
        var findings = new ArrayList<LintFinding>();
        for (TreePath method : LintTrees.methods(syntax)) {
            Set<String> locals = new HashSet<>();
            for (TreePath path : LintTrees.findAll(method, VariableTree.class)) {
                if (LintTrees.isDeclarator(path)) locals.add(((VariableTree) path.getLeaf()).getName().toString());
            }
            if (locals.isEmpty()) continue;

            Set<String> whileAccumulators = new HashSet<>();
            for (TreePath loop : LintTrees.findAll(method, WhileLoopTree.class)) {
                for (TreePath assign : assignments(loop)) {
                    String name = targetName(assign.getLeaf());
                    if (name != null && locals.contains(name)) whileAccumulators.add(name);
                }
            }

            for (TreePath path : assignments(method)) {
                String name = targetName(path.getLeaf());
                if (name == null || !locals.contains(name)) continue;
                if (whileAccumulators.contains(name)) continue;
                findings.add(LintFinding.warning(
                        id(),
                        null,
                        "Re-assignment to local variable `" + name + "` after declaration. " +
                                "JuLC on-chain code is single-assignment; the compiler will reject this. " +
                                "(Inside a while-loop accumulator pattern, re-assignment IS allowed; " +
                                "this lint runs a heuristic and may need a manual override.)",
                        syntax.line(path.getLeaf()), syntax.column(path.getLeaf()),
                        "Introduce a new variable: `var " + name + "2 = " + name + ".add(...)`. " +
                                "Or, if you need an accumulator, wrap the loop body in a `while`."
                ));
            }
        }
        return findings;
    }

    /**
     * JavaParser's assignment expressions: {@code =} and the compound ones, in pre-order. An annotation's
     * {@code name = value} is an assignment in javac's tree and a member-value pair in JavaParser's, so it is left out.
     */
    private static List<TreePath> assignments(TreePath root) {
        return LintTrees.findAll(root, ExpressionTree.class).stream()
                .filter(path -> (path.getLeaf() instanceof AssignmentTree
                        && !(path.getParentPath().getLeaf() instanceof AnnotationTree))
                        || path.getLeaf() instanceof CompoundAssignmentTree)
                .toList();
    }

    /** The assigned simple name, or null when the target is anything else. */
    private static String targetName(Tree assignment) {
        ExpressionTree target = assignment instanceof AssignmentTree a ? a.getVariable()
                : ((CompoundAssignmentTree) assignment).getVariable();
        return target instanceof IdentifierTree id ? id.getName().toString() : null;
    }
}
