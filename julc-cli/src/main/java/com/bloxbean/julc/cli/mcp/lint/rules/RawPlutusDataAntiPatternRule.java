package com.bloxbean.julc.cli.mcp.lint.rules;

import com.bloxbean.julc.cli.mcp.lint.LintFinding;
import com.bloxbean.julc.cli.mcp.lint.LintRule;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;

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
    public List<LintFinding> check(CompilationUnit cu) {
        var findings = new ArrayList<LintFinding>();

        // Detect whether the source imports any of the raw types directly,
        // e.g. `import com.bloxbean.cardano.julc.core.PlutusData.ConstrData`.
        // If so, `new ConstrData(...)` (without a `PlutusData.` prefix) is
        // also the anti-pattern. Codex P2 / impl-validator finding.
        boolean hasDirectRawImport = cu.getImports().stream().anyMatch(imp -> {
            String name = imp.getNameAsString();
            for (var raw : RAW_TYPES) {
                if (name.endsWith(".PlutusData." + raw)) return true;
            }
            return false;
        });

        cu.findAll(ObjectCreationExpr.class).forEach(expr -> {
            String typeName = expr.getType().getNameAsString();
            String full = expr.getType().toString();
            boolean qualifiedHit = RAW_TYPES.contains(typeName) && full.contains("PlutusData");
            boolean directImportHit = hasDirectRawImport && RAW_TYPES.contains(typeName) && !full.contains(".");
            if (!qualifiedHit && !directImportHit) return;
            int line = expr.getBegin().map(p -> p.line).orElse(0);
            int col = expr.getBegin().map(p -> p.column).orElse(0);
            findings.add(LintFinding.warning(
                    id(),
                    null,
                    "Constructing `" + full + "` directly is the project's #1 anti-pattern.",
                    line, col,
                    "Define a `record` or sealed interface for your data and let the compiler " +
                            "auto-encode/decode. See https://julc.dev/ai/starter-pack/#5-critical-use-type-classes-not-raw-plutusdata"
            ));
        });

        // `PlutusData.cast(...)` (legacy/forwarding pattern).
        cu.findAll(MethodCallExpr.class).forEach(call -> {
            if (!"cast".equals(call.getNameAsString())) return;
            var scope = call.getScope().orElse(null);
            if (scope == null) return;
            if (!"PlutusData".equals(scope.toString())) return;
            int line = call.getBegin().map(p -> p.line).orElse(0);
            int col = call.getBegin().map(p -> p.column).orElse(0);
            findings.add(LintFinding.warning(
                    id(),
                    null,
                    "`PlutusData.cast(...)` indicates raw-data manipulation — usually a sign " +
                            "that a typed record / sealed interface should be used instead.",
                    line, col,
                    "Replace with field access on a typed record. If interop with an opaque " +
                            "payload is genuinely needed, leave it but document why."
            ));
        });

        return findings;
    }
}
