package org.julclang.cli.mcp.lint.rules;

import org.julclang.cli.mcp.lint.LintFinding;
import org.julclang.cli.mcp.lint.LintRule;
import org.julclang.cli.mcp.lint.LintTrees;
import org.julclang.compiler.frontend.javac.JavacSyntax;
import com.sun.source.util.TreePath;
import com.sun.source.tree.VariableTree;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Detects {@code @Param} fields whose type is on the compiler's
 * {@code BANNED_PARAM_TYPES} list. The compiler rejects these with a
 * specific error; the lint catches it pre-compile so an agent can fix
 * without a round trip.
 *
 * <p>Mirrors {@code JulcCompiler.BANNED_PARAM_TYPES} — currently the
 * four typed PlutusData subtypes that cannot be reconstructed from raw
 * runtime Data without ambiguity.
 *
 * <p>Recognises both qualified ({@code PlutusData.BytesData}) and
 * direct-imported ({@code BytesData}) usages.
 */
public final class BannedParamTypesRule implements LintRule {

    private static final Set<String> BANNED_SIMPLE = Set.of(
            "BytesData", "MapData", "ListData", "IntData");

    @Override
    public String id() {
        return "JULC-LINT-BANNED-PARAM-TYPE";
    }

    @Override
    public String description() {
        return "@Param uses a banned PlutusData subtype (BytesData / MapData / ListData / IntData)";
    }

    private static String lastSegment(String typeStr) {
        // Strip generics `<...>` then take portion after final `.`
        int lt = typeStr.indexOf('<');
        String head = lt < 0 ? typeStr : typeStr.substring(0, lt);
        int dot = head.lastIndexOf('.');
        return dot < 0 ? head.trim() : head.substring(dot + 1).trim();
    }

    @Override
    public List<LintFinding> check(JavacSyntax syntax) {
        var findings = new ArrayList<LintFinding>();
        for (TreePath path : LintTrees.findAll(syntax, VariableTree.class)) {
            if (!LintTrees.isField(path)) continue;
            var field = (VariableTree) path.getLeaf();
            boolean hasParam = field.getModifiers().getAnnotations().stream()
                    .anyMatch(a -> "Param".equals(LintTrees.annotationName(a)));
            if (!hasParam) continue;
            String typeStr = field.getType().toString();
            String simple = lastSegment(typeStr);
            if (!BANNED_SIMPLE.contains(simple)) continue;
            findings.add(LintFinding.error(
                    id(),
                    null,
                    "@Param `" + field.getName() + "` uses banned type `" + typeStr + "`. " +
                            "BytesData, MapData, ListData and IntData are not allowed for @Param.",
                    syntax.line(field), syntax.column(field),
                    "Use a typed alternative: `byte[]` for bytes, `BigInteger` for integers, " +
                            "`JulcList<T>` for lists, or a typed record for structured data."
            ));
        }
        return findings;
    }
}
