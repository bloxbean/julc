package com.bloxbean.julc.cli.mcp.lint.rules;

import com.bloxbean.julc.cli.mcp.lint.LintFinding;
import com.bloxbean.julc.cli.mcp.lint.LintRule;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.FieldDeclaration;

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

    @Override
    public List<LintFinding> check(CompilationUnit cu) {
        var findings = new ArrayList<LintFinding>();
        cu.findAll(FieldDeclaration.class).forEach(field -> {
            boolean hasParam = field.getAnnotations().stream()
                    .anyMatch(a -> "Param".equals(a.getNameAsString()));
            if (!hasParam) return;
            String typeStr = field.getCommonType().toString();
            String simple = lastSegment(typeStr);
            if (!BANNED_SIMPLE.contains(simple)) return;
            int line = field.getBegin().map(p -> p.line).orElse(0);
            int col = field.getBegin().map(p -> p.column).orElse(0);
            for (var v : field.getVariables()) {
                findings.add(LintFinding.error(
                        id(),
                        null,
                        "@Param `" + v.getNameAsString() + "` uses banned type `" + typeStr + "`. " +
                                "BytesData, MapData, ListData and IntData are not allowed for @Param.",
                        line, col,
                        "Use a typed alternative: `byte[]` for bytes, `BigInteger` for integers, " +
                                "`JulcList<T>` for lists, or a typed record for structured data."
                ));
            }
        });
        return findings;
    }

    private static String lastSegment(String typeStr) {
        // Strip generics `<...>` then take portion after final `.`
        int lt = typeStr.indexOf('<');
        String head = lt < 0 ? typeStr : typeStr.substring(0, lt);
        int dot = head.lastIndexOf('.');
        return dot < 0 ? head.trim() : head.substring(dot + 1).trim();
    }
}
