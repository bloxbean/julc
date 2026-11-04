package org.julclang.cli.mcp.lint.rules;

import org.julclang.cli.mcp.lint.LintFinding;
import org.julclang.cli.mcp.lint.LintRule;
import org.julclang.compiler.frontend.javac.SourceReader;
import org.julclang.cli.mcp.lint.LintTrees;
import org.julclang.compiler.frontend.javac.JavacSyntax;
import com.sun.source.util.TreePath;
import com.sun.source.tree.BindingPatternTree;
import com.sun.source.tree.CaseLabelTree;
import com.sun.source.tree.CaseTree;
import com.sun.source.tree.ConstantCaseLabelTree;
import com.sun.source.tree.DeconstructionPatternTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.PatternCaseLabelTree;
import com.sun.source.tree.SwitchExpressionTree;
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

    @Override
    public boolean supports(SourceReader reader) {
        return true;
    }

    @Override
    public List<LintFinding> check(JavacSyntax syntax) {
        var findings = new ArrayList<LintFinding>();
        for (TreePath sw : LintTrees.findAll(syntax, SwitchExpressionTree.class)) {
            for (TreePath entry : LintTrees.findAll(sw, CaseTree.class)) {
                for (CaseLabelTree label : ((CaseTree) entry.getLeaf()).getLabels()) {
                    String name = tupleName(label);
                    if (name == null) continue;
                    boolean isTuple2 = name.equals("Tuple2");
                    findings.add(LintFinding.error(
                            id(),
                            null,
                            "Switch case on `" + name + "` is not supported — " +
                                    "tuples are records (RecordType), not a sealed sum type.",
                            syntax.line(label), syntax.column(label),
                            "Use field accessors: `" + (isTuple2 ? "tup.first(), tup.second()" :
                                    "tup.first(), tup.second(), tup.third()") + "`."
                    ));
                }
            }
        }
        return findings;
    }

    /**
     * {@code Tuple2} or {@code Tuple3} when the label, as JavaParser prints it, starts with that name and a space or a
     * parenthesis: a type pattern of the plain name, without modifiers, or a record pattern of it; else null.
     */
    private static String tupleName(CaseLabelTree label) {
        String text = switch (label) {
            case PatternCaseLabelTree pattern when pattern.getPattern() instanceof BindingPatternTree binding ->
                    binding.getVariable().getModifiers().getFlags().isEmpty()
                            && binding.getVariable().getModifiers().getAnnotations().isEmpty()
                            && binding.getVariable().getType() instanceof IdentifierTree type
                            ? type.getName() + " " : null;
            case PatternCaseLabelTree pattern when pattern.getPattern() instanceof DeconstructionPatternTree record ->
                    record.getDeconstructor() instanceof IdentifierTree type ? type.getName() + "(" : null;
            case ConstantCaseLabelTree constant -> constant.getConstantExpression().toString().trim();
            default -> null;
        };
        if (text == null) return null;
        if (text.startsWith("Tuple2(") || text.startsWith("Tuple2 ")) return "Tuple2";
        if (text.startsWith("Tuple3(") || text.startsWith("Tuple3 ")) return "Tuple3";
        return null;
    }
}
