package org.julclang.compiler.frontend.javac.subset;

import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BindingPatternTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.CaseTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.InstanceOfTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.SwitchExpressionTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import org.julclang.compiler.CompilerException;
import org.julclang.compiler.error.DiagnosticCodes;
import org.julclang.compiler.frontend.javac.JavacFacts;

import javax.lang.model.element.Element;
import java.util.HashSet;
import java.util.Set;

/**
 * A {@code switch} arm exports only its value, never a rebinding of an enclosing variable (ADR-048, ADR-067 I11), ported
 * from {@code LoopBodyGenerator.validateSwitchExpressionUpdates}. It runs before the selector is lowered, as there, so a
 * rejection here comes first. A variable is its element. An arm-local that is assigned is the lowering's to reject.
 */
final class ArmUpdates {

    private final JavacFacts facts;

    ArmUpdates(JavacFacts facts) {
        this.facts = facts;
    }

    void check(SwitchExpressionTree tree) {
        for (var entry : tree.getCases()) {
            var declared = new HashSet<Element>();
            for (var label : entry.getLabels()) {
                for (var binding : SubsetWalk.all(label, BindingPatternTree.class)) declared.add(facts.element(binding.getVariable()));
            }
            var bindings = Set.copyOf(declared);
            if (entry.getGuard() != null) walk(entry.getGuard(), new HashSet<>(bindings), bindings);
            if (entry.getCaseKind() == CaseTree.CaseKind.RULE) {
                walk(entry.getBody(), declared, bindings);
            } else {
                entry.getStatements().forEach(statement -> walk(statement, declared, bindings));
            }
        }
    }

    private void walk(Tree node, Set<Element> declared, Set<Element> bindings) {
        switch (node) {
            case LambdaExpressionTree ignored -> {
            }
            case BlockTree block -> {
                var locals = new HashSet<>(declared);
                block.getStatements().forEach(statement -> walk(statement, locals, bindings));
            }
            case ExpressionStatementTree statement -> walk(statement.getExpression(), declared, bindings);
            case VariableTree variable -> {
                if (variable.getInitializer() != null) walk(variable.getInitializer(), declared, bindings);
                declared.add(facts.element(variable));
            }
            case AssignmentTree assignment when isUpdate(assignment.getVariable(), declared, bindings) ->
                    throw update(assignment.getVariable(), assignment, bindings);
            case CompoundAssignmentTree assignment when isUpdate(assignment.getVariable(), declared, bindings) ->
                    throw update(assignment.getVariable(), assignment, bindings);
            case IfTree branch -> {
                walk(branch.getCondition(), declared, bindings);
                var thenLocals = new HashSet<>(declared);
                var condition = ((ParenthesizedTree) branch.getCondition()).getExpression();
                if (condition instanceof InstanceOfTree test && test.getPattern() instanceof BindingPatternTree pattern) {
                    thenLocals.add(facts.element(pattern.getVariable()));
                }
                walk(branch.getThenStatement(), thenLocals, bindings);
                if (branch.getElseStatement() != null) walk(branch.getElseStatement(), new HashSet<>(declared), bindings);
            }
            case EnhancedForLoopTree loop -> {
                walk(loop.getExpression(), declared, bindings);
                var loopLocals = new HashSet<>(declared);
                loopLocals.add(facts.element(loop.getVariable()));
                walk(loop.getStatement(), loopLocals, bindings);
            }
            case SwitchExpressionTree nested -> {
                walk(nested.getExpression(), declared, bindings);
                check(nested); // A second value boundary, including for outer-arm locals.
            }
            default -> SubsetWalk.children(node).forEach(child -> walk(child, new HashSet<>(declared), bindings));
        }
    }

    private boolean isUpdate(ExpressionTree target, Set<Element> declared, Set<Element> bindings) {
        if (!(target instanceof IdentifierTree)) return false;
        var element = facts.element(target);
        return bindings.contains(element) || !declared.contains(element);
    }

    private CompilerException update(ExpressionTree target, ExpressionTree assignment, Set<Element> bindings) {
        String name = ((IdentifierTree) target).getName().toString();
        if (bindings.contains(facts.element(target))) {
            // #162: cached case-field projections still refer to the original record.
            return facts.rejection(DiagnosticCodes.SWITCH_CASE_VARIABLE_REASSIGNED, assignment, name);
        }
        // ADR-069 D8 rule 3: a pattern variable other than an if's whole condition's is never an arm's local.
        if (facts.declaringTest(facts.element(target)) != null) {
            return facts.patternVariableReassigned(assignment, facts.element(target));
        }
        return facts.rejection(DiagnosticCodes.SWITCH_ARM_UPDATE_UNSUPPORTED, assignment, name);
    }
}
