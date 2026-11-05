package org.julclang.cli.mcp.lint.rules;

import org.julclang.cli.mcp.lint.LintFinding;
import org.julclang.cli.mcp.lint.LintRule;
import org.julclang.cli.mcp.lint.LintTrees;
import org.julclang.compiler.frontend.javac.JavacSyntax;
import com.sun.source.util.TreePath;
import com.sun.source.tree.ArrayTypeTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;

import java.util.ArrayList;
import java.util.List;

/**
 * Detects {@code @Entrypoint} methods that declare a {@code BigInteger}
 * parameter directly in the signature. Per CLAUDE.md, entrypoint params
 * with {@code BigInteger} type are typed as {@code IntegerType} in the
 * symbol table but hold raw {@code Data} at runtime — a mismatch that
 * surfaces as confusing errors when arithmetic is attempted.
 *
 * <p>Only fires when the {@code @Entrypoint} annotation is present and
 * a parameter's type is exactly {@code BigInteger} (qualified or not).
 * Datum/redeemer record types and {@code ScriptContext} are ignored —
 * they are the canonical entrypoint signature.
 */
public final class EntrypointBigIntegerParamRule implements LintRule {

    @Override
    public String id() {
        return "JULC-LINT-ENTRYPOINT-BIGINT-PARAM";
    }

    @Override
    public String description() {
        return "@Entrypoint parameter typed BigInteger holds raw Data at runtime — wrap in a record instead";
    }

    @Override
    public List<LintFinding> check(JavacSyntax syntax) {
        var findings = new ArrayList<LintFinding>();
        for (TreePath path : LintTrees.methods(syntax)) {
            var method = (MethodTree) path.getLeaf();
            boolean hasEntrypoint = method.getModifiers().getAnnotations().stream()
                    .anyMatch(a -> "Entrypoint".equals(LintTrees.annotationName(a)));
            if (!hasEntrypoint) continue;
            for (VariableTree param : method.getParameters()) {
                String t = declaredType(syntax, param).toString();
                if (!"BigInteger".equals(t) && !"java.math.BigInteger".equals(t)) continue;
                findings.add(LintFinding.warning(
                        id(),
                        null,
                        "@Entrypoint parameter `" + param.getName() + "` is BigInteger. " +
                                "It is typed as IntegerType in the symbol table but holds raw Data " +
                                "at runtime — arithmetic operations will fail.",
                        syntax.line(param), syntax.column(param),
                        "Wrap the integer in a datum/redeemer record: " +
                                "`record D(BigInteger " + param.getName() + ") {}` and accept " +
                                "the record as the entrypoint parameter."
                ));
            }
        }
        return findings;
    }

    /** The parameter's type as JavaParser gives it: a variable-arity parameter's element type. */
    private static Tree declaredType(JavacSyntax syntax, VariableTree param) {
        Tree type = param.getType();
        if (type instanceof ArrayTypeTree array && syntax.isVarargs(param)) return array.getType();
        return type;
    }
}
