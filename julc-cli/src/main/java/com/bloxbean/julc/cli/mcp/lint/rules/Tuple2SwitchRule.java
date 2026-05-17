package com.bloxbean.julc.cli.mcp.lint.rules;

import com.bloxbean.julc.cli.mcp.lint.LintFinding;
import com.bloxbean.julc.cli.mcp.lint.LintRule;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.SwitchExpr;
import com.github.javaparser.ast.stmt.SwitchEntry;

import java.util.ArrayList;
import java.util.List;

/**
 * Detects {@code switch} expressions with {@code Tuple2} or {@code Tuple3}
 * case patterns. Per CLAUDE.md, tuple types are registered as
 * {@code RecordType} but {@code switch} requires a {@code SumType}
 * (sealed interface). The compiler rejects the switch with an error;
 * the lint surfaces it pre-compile with the canonical fix.
 *
 * <p>Heuristic: flag any switch case label that textually starts with
 * {@code Tuple2(}, {@code Tuple2 }, {@code Tuple3(}, or {@code Tuple3 }.
 * This catches both record-pattern syntax ({@code case Tuple2(a, b)})
 * and type-pattern syntax ({@code case Tuple2 t}).
 */
public final class Tuple2SwitchRule implements LintRule {

    @Override
    public String id() {
        return "JULC-LINT-TUPLE-SWITCH";
    }

    @Override
    public String description() {
        return "Switch case on Tuple2 / Tuple3 — these are RecordType, not SumType; use field access";
    }

    @Override
    public List<LintFinding> check(CompilationUnit cu) {
        var findings = new ArrayList<LintFinding>();
        cu.findAll(SwitchExpr.class).forEach(sw ->
                sw.findAll(SwitchEntry.class).forEach(entry -> {
                    for (var label : entry.getLabels()) {
                        String text = label.toString().trim();
                        boolean isTuple2 = text.startsWith("Tuple2(") || text.startsWith("Tuple2 ");
                        boolean isTuple3 = text.startsWith("Tuple3(") || text.startsWith("Tuple3 ");
                        if (!isTuple2 && !isTuple3) continue;
                        String name = isTuple2 ? "Tuple2" : "Tuple3";
                        int line = label.getBegin().map(p -> p.line).orElse(0);
                        int col = label.getBegin().map(p -> p.column).orElse(0);
                        findings.add(LintFinding.error(
                                id(),
                                null,
                                "Switch case on `" + name + "` is not supported — " +
                                        "tuples are records (RecordType), not a sealed sum type.",
                                line, col,
                                "Use field accessors: `" + (isTuple2 ? "tup.first(), tup.second()" :
                                        "tup.first(), tup.second(), tup.third()") + "`."
                        ));
                    }
                }));
        return findings;
    }
}
