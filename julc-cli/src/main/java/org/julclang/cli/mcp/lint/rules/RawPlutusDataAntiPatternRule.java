package org.julclang.cli.mcp.lint.rules;

import org.julclang.cli.mcp.lint.LintFinding;
import org.julclang.cli.mcp.lint.LintRule;
import org.julclang.cli.mcp.lint.LintTrees;
import org.julclang.compiler.frontend.javac.JavacSyntax;
import com.sun.source.util.TreePath;
import com.sun.source.tree.AnnotatedTypeTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.Tree;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Flags direct use of {@code PlutusData.ConstrData}, {@code IntData},
 * {@code BytesData}, {@code MapData}, {@code ListData} — the project's #1
 * anti-pattern (see CLAUDE.md "Type-class vs PlutusData" rule).
 *
 * <p>The rule fires on:
 * <ul>
 *   <li>{@code new PlutusData.ConstrData(...)} (and other raw subtypes)</li>
 *   <li>{@code PlutusData.cast(...)}</li>
 * </ul>
 *
 * <p>Pattern-matching on raw types via {@code switch} or {@code instanceof}
 * is allowed (you may legitimately need to interrogate an opaque payload).
 * The rule targets <em>construction</em>, which is what AI agents invent
 * when they don't realize records auto-encode.
 */
public final class RawPlutusDataAntiPatternRule implements LintRule {

    private static final Set<String> RAW_TYPES = Set.of(
            "ConstrData", "IntData", "BytesData", "MapData", "ListData");

    @Override
    public String id() {
        return "JULC-LINT-RAW-PLUTUSDATA";
    }

    @Override
    public String description() {
        return "Constructing raw PlutusData subtypes — prefer high-level type classes (records, sealed interfaces, JulcList, JulcMap)";
    }

    @Override
    public List<LintFinding> check(JavacSyntax syntax) {
        var findings = new ArrayList<LintFinding>();
        boolean hasDirectRawImport = false;
        for (ImportTree imp : syntax.unit().getImports()) {
            String name = imp.getQualifiedIdentifier().toString();
            for (var raw : RAW_TYPES) {
                if (name.endsWith(".PlutusData." + raw)) hasDirectRawImport = true;
            }
        }

        for (TreePath path : LintTrees.findAll(syntax, NewClassTree.class)) {
            var expr = (NewClassTree) path.getLeaf();
            String typeName = simpleTypeName(expr.getIdentifier());
            String full = expr.getIdentifier().toString();
            boolean qualifiedHit = RAW_TYPES.contains(typeName) && full.contains("PlutusData");
            boolean directImportHit = hasDirectRawImport && RAW_TYPES.contains(typeName) && !full.contains(".");
            if (!qualifiedHit && !directImportHit) continue;
            findings.add(LintFinding.warning(
                    id(),
                    null,
                    "Constructing `" + full + "` directly is the project's #1 anti-pattern.",
                    syntax.line(expr), syntax.column(expr),
                    "Define a `record` or sealed interface for your data and let the compiler " +
                            "auto-encode/decode. See https://julc.dev/ai/starter-pack/#5-critical-use-type-classes-not-raw-plutusdata"
            ));
        }

        for (TreePath path : LintTrees.findAll(syntax, MethodInvocationTree.class)) {
            var call = (MethodInvocationTree) path.getLeaf();
            if (!"cast".equals(LintTrees.methodName(call))) continue;
            if (!"PlutusData".equals(LintTrees.dottedName(LintTrees.receiver(call)))) continue;
            findings.add(LintFinding.warning(
                    id(),
                    null,
                    "`PlutusData.cast(...)` indicates raw-data manipulation — usually a sign " +
                            "that a typed record / sealed interface should be used instead.",
                    syntax.line(call), syntax.column(call),
                    "Replace with field access on a typed record. If interop with an opaque " +
                            "payload is genuinely needed, leave it but document why."
            ));
        }

        return findings;
    }

    /** A class type's own name, without its qualifier or type arguments. */
    private static String simpleTypeName(Tree type) {
        return switch (type) {
            case ParameterizedTypeTree parameterized -> simpleTypeName(parameterized.getType());
            case AnnotatedTypeTree annotated -> simpleTypeName(annotated.getUnderlyingType());
            case MemberSelectTree select -> select.getIdentifier().toString();
            case IdentifierTree id -> id.getName().toString();
            default -> type.toString();
        };
    }
}
