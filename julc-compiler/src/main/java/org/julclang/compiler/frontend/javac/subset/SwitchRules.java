package org.julclang.compiler.frontend.javac.subset;

import com.sun.source.tree.BindingPatternTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.CaseLabelTree;
import com.sun.source.tree.CaseTree;
import com.sun.source.tree.DeconstructionPatternTree;
import com.sun.source.tree.DefaultCaseLabelTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.PatternCaseLabelTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.SwitchExpressionTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.YieldTree;
import org.julclang.compiler.error.DiagnosticCodes;
import org.julclang.compiler.frontend.javac.JavacFacts;
import org.julclang.compiler.frontend.javac.subset.GuardTypes.JType;
import org.julclang.compiler.pir.PirType;

import javax.lang.model.element.TypeElement;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * The rules on a {@code switch} expression that javac does not make (ADR-068 D11), and the labels' model the lowering
 * uses. javac has rejected a duplicate {@code default}, a dominated label, a mix of case kinds, a switch that is not
 * exhaustive, a pattern variable that conflicts, and a pattern that is not applicable to the selector, so
 * {@code JULC0083}, but for one shape of a guard's, is javac's diagnostic here, and {@code JULC0005}, {@code JULC0071},
 * {@code JULC0073} and {@code JULC0082} are retired.
 * <p>
 * The lowering calls these where the reference calls {@code SwitchPatternChecker}: {@link #requireLocalUpdates} before the
 * selector is lowered, and {@link #check} after, on a sealed interface. So a rejection keeps its place in the reference's
 * order (I2a). A variant is its {@link TypeElement}: two variants of one simple name are two variants. This reads trees and
 * builds no term, so it has no evaluation order to state.
 */
public final class SwitchRules {

    /** A type-pattern arm. {@code guard} is null when the label is unguarded, a constant {@code true} included. */
    public record Arm(CaseTree entry, BindingPatternTree pattern, TypeElement variant, ExpressionTree guard) {
    }

    /** The checked switch: its arms in source order, its {@code default} (or null), and whether any label has a guard. */
    public record Model(List<Arm> arms, CaseTree defaultEntry, boolean guarded) {
    }

    private final JavacFacts facts;
    private final GuardRules guards;
    private final ArmUpdates updates;

    public SwitchRules(JavacFacts facts) {
        this.facts = facts;
        this.guards = new GuardRules(facts);
        this.updates = new ArmUpdates(facts);
    }

    public void requireLocalUpdates(SwitchExpressionTree tree) {
        updates.check(tree);
    }

    public Model check(SwitchExpressionTree tree, TypeElement sealed, PirType.SumType sum) {
        var arms = new ArrayList<Arm>();
        CaseTree defaultEntry = null;
        boolean guarded = false;
        for (var entry : tree.getCases()) {
            checkGroup(entry);
            requireOneLabel(entry);
            if (isDefault(entry)) defaultEntry = entry;
            if (!(entry.getLabels().getFirst() instanceof PatternCaseLabelTree label)) continue;
            if (label.getPattern() instanceof DeconstructionPatternTree) {
                throw facts.rejection(DiagnosticCodes.CASE_PATTERN_UNSUPPORTED, entry, facts.text(label),
                        "not yet supported: record patterns are implemented by ADR-067 M2a; match the type pattern "
                                + "and read the components with their accessors");
            }
            var pattern = (BindingPatternTree) label.getPattern();
            var guard = entry.getGuard();
            if (guard != null) guarded = true;
            var variant = applicable(entry, pattern, sealed, sum);
            boolean unguarded = guard == null || guards.check(entry, guard);
            arms.add(new Arm(entry, pattern, variant, unguarded ? null : guard));
        }
        return new Model(arms, defaultEntry, guarded);
    }

    /** Whether {@code entry} has a {@code default} label. */
    private static boolean isDefault(CaseTree entry) {
        return entry.getLabels().stream().anyMatch(DefaultCaseLabelTree.class::isInstance);
    }

    /** An arm is one type pattern or {@code default} ({@code JULC0075}, {@code JULC0076}). Nothing lowers another label. */
    private void requireOneLabel(CaseTree entry) {
        var labels = entry.getLabels();
        if (labels.size() > 1) {
            throw facts.rejection(DiagnosticCodes.SWITCH_GROUP_UNSUPPORTED, labels.get(1), facts.location(labels.get(1)).fragment(),
                    "an arm takes one type pattern or default");
        }
        var label = labels.getFirst();
        if (!(label instanceof PatternCaseLabelTree || label instanceof DefaultCaseLabelTree)) {
            throw facts.rejection(DiagnosticCodes.CASE_PATTERN_UNSUPPORTED, label, facts.location(label).fragment(),
                    "an arm takes a type pattern of a variant or default");
        }
    }

    /** {@code JULC0075}: a colon form holds one label a group, and yields on every path: JuLC has no fall-through (#283). */
    private void checkGroup(CaseTree entry) {
        if (entry.getCaseKind() != CaseTree.CaseKind.STATEMENT) return;
        if (entry.getStatements().isEmpty()) {
            throw facts.rejection(DiagnosticCodes.SWITCH_GROUP_UNSUPPORTED, entry, labelText(facts, entry),
                    "a statement group with several labels; give each label its own group that ends in yield");
        }
        if (!cannotCompleteNormally(entry.getStatements())) {
            throw facts.rejection(DiagnosticCodes.SWITCH_GROUP_UNSUPPORTED, entry, labelText(facts, entry),
                    "the group can complete normally, so Java would fall through into the next group; "
                            + "JuLC does not implement fall-through (#283). End the group with yield");
        }
    }

    /** JLS 14.22 for the statements a group can hold: a {@code throw} stops at the scan. */
    private static boolean cannotCompleteNormally(List<? extends StatementTree> statements) {
        return statements.stream().anyMatch(SwitchRules::cannotCompleteNormally);
    }

    private static boolean cannotCompleteNormally(StatementTree statement) {
        return switch (statement) {
            case YieldTree ignored -> true;
            case BlockTree block -> cannotCompleteNormally(block.getStatements());
            case IfTree branch -> branch.getElseStatement() != null && cannotCompleteNormally(branch.getThenStatement())
                    && cannotCompleteNormally(branch.getElseStatement());
            default -> false;
        };
    }

    /** {@code JULC0076}: the pattern's type is a variant of exactly the selector's sealed interface. */
    private TypeElement applicable(CaseTree entry, BindingPatternTree pattern, TypeElement sealed, PirType.SumType sum) {
        var written = pattern.getVariable().getType();
        var type = facts.types().asElement(facts.type(written));
        String text = facts.text(written);
        String name = type == null ? text : type.getSimpleName().toString();
        boolean variant = type instanceof TypeElement element && facts.permitted(sealed).contains(element);
        if (variant) return (TypeElement) type;
        boolean named = sum.constructors().stream().anyMatch(c -> c.name().equals(name));
        String label = facts.text(pattern);
        if (isQualified(written)) {
            throw facts.rejection(DiagnosticCodes.CASE_PATTERN_UNSUPPORTED, entry, label,
                    "not applicable: " + text + " is not a permitted variant of " + sum.name());
        }
        if (named) {
            throw facts.rejection(DiagnosticCodes.CASE_PATTERN_UNSUPPORTED, entry, label,
                    "not applicable: " + text + " does not resolve to a permitted variant of " + sum.name());
        }
        if (name.equals(sum.name())) {
            throw facts.rejection(DiagnosticCodes.CASE_PATTERN_UNSUPPORTED, entry, label,
                    "unsupported: total type pattern (valid Java, not supported by JuLC; use default)");
        }
        String available = String.join(", ", sum.constructors().stream().map(PirType.Constructor::name).toList());
        throw facts.rejection(DiagnosticCodes.CASE_PATTERN_UNSUPPORTED, entry, label,
                "not applicable: " + name + " is not a permitted variant of " + sum.name() + " (variants: " + available + ")");
    }

    private static boolean isQualified(Tree written) {
        var type = written instanceof ParameterizedTypeTree parameterized ? parameterized.getType() : written;
        return type instanceof MemberSelectTree;
    }

    // --- Where a guard is lowered ---

    /** What the reference asks of a guard before it lowers it. */
    public void beforeGuard(Arm arm, Function<ExpressionTree, JType> leaf) {
        guards.requireStringEquals(arm.entry(), arm.guard(), leaf);
        guards.requireEquality(arm.guard());
    }

    /** The guard's type as far as {@code leaf} establishes it. */
    public JType guardType(Arm arm, Function<ExpressionTree, JType> leaf) {
        return guards.typeOf(arm.guard(), leaf);
    }

    /** What the reference asks of a guard after it lowers it: {@code leafIsBoolean} says the term is a boolean. */
    public void afterGuard(Arm arm, JType type, boolean leafIsBoolean) {
        boolean established = type == JType.UNKNOWN && GuardRules.isLeaf(arm.guard()) && leafIsBoolean;
        guards.requireBoolean(arm.entry(), arm.guard(), established ? JType.BOOLEAN : type);
    }

    /** The constants a guard reads, to Java's widths. */
    public GuardConstants constants() {
        return guards.constants();
    }

    /** The case as a message quotes it. */
    static String labelText(JavacFacts facts, CaseTree entry) {
        if (entry.getLabels().stream().allMatch(DefaultCaseLabelTree.class::isInstance)) return "default";
        var text = new StringBuilder("case ");
        String separator = "";
        for (CaseLabelTree label : entry.getLabels()) {
            text.append(separator).append(facts.text(label));
            separator = ", ";
        }
        if (entry.getGuard() != null) text.append(" when ").append(facts.text(entry.getGuard()));
        return text.toString();
    }
}
