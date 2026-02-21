package com.bloxbean.cardano.julc.analysis.rules;

import com.bloxbean.cardano.julc.analysis.Category;
import com.bloxbean.cardano.julc.analysis.Finding;
import com.bloxbean.cardano.julc.analysis.Severity;
import com.bloxbean.cardano.julc.decompiler.DecompileResult;
import com.bloxbean.cardano.julc.decompiler.hir.HirTerm;

import java.util.ArrayList;
import java.util.List;

/**
 * Detects potentially unbounded recursion (LetRec without clear termination).
 * <p>
 * LetRec nodes represent recursive bindings. Without a conditional guard
 * (If/Switch) in the recursive body, the recursion may run until budget exhaustion.
 */
public final class UnboundedRecursionRule implements SecurityRule {

    @Override
    public String name() {
        return "UnboundedRecursion";
    }

    @Override
    public List<Finding> analyze(DecompileResult result) {
        if (result.hir() == null) return List.of();

        var findings = new ArrayList<Finding>();

        var letRecs = HirTreeWalker.collect(result.hir(),
                node -> node instanceof HirTerm.LetRec);

        for (var node : letRecs) {
            var lr = (HirTerm.LetRec) node;
            // Check if the recursive value body contains a conditional guard
            boolean hasGuard = HirTreeWalker.anyMatch(lr.value(),
                    n -> n instanceof HirTerm.If || n instanceof HirTerm.Switch);

            // Also check for NullList (list termination check)
            boolean hasNullCheck = HirTreeWalker.anyMatch(lr.value(),
                    n -> n instanceof HirTerm.BuiltinCall bc
                            && bc.fun() == com.bloxbean.cardano.julc.core.DefaultFun.NullList);

            if (!hasGuard && !hasNullCheck) {
                findings.add(new Finding(
                        Severity.MEDIUM,
                        Category.UNBOUNDED_EXECUTION,
                        "Recursive binding without termination guard",
                        "LetRec '" + lr.name() + "' does not appear to have a conditional "
                                + "guard (if/switch/null-check) in its recursive body. This "
                                + "may lead to unbounded execution until budget exhaustion.",
                        "LetRec '" + lr.name() + "'",
                        "Ensure recursive functions have a base case that terminates recursion."
                ));
            }
        }

        return findings;
    }
}
