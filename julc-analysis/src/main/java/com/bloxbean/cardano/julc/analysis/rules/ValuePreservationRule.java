package com.bloxbean.cardano.julc.analysis.rules;

import com.bloxbean.cardano.julc.analysis.Category;
import com.bloxbean.cardano.julc.analysis.Finding;
import com.bloxbean.cardano.julc.analysis.Severity;
import com.bloxbean.cardano.julc.core.DefaultFun;
import com.bloxbean.cardano.julc.decompiler.DecompileResult;
import com.bloxbean.cardano.julc.decompiler.hir.HirTerm;

import java.util.List;

/**
 * Checks for value preservation — scripts that manipulate value
 * should also verify value equality or ordering.
 * <p>
 * Without value preservation checks, a script may allow value to leak
 * (e.g., a swap contract that doesn't verify the output value).
 */
public final class ValuePreservationRule implements SecurityRule {

    @Override
    public String name() {
        return "ValuePreservation";
    }

    @Override
    public List<Finding> analyze(DecompileResult result) {
        if (result.hir() == null) return List.of();

        var builtins = result.stats().builtinsUsed();

        // Check if the script performs arithmetic on values
        boolean hasArithmetic = builtins.contains(DefaultFun.AddInteger)
                || builtins.contains(DefaultFun.SubtractInteger)
                || builtins.contains(DefaultFun.MultiplyInteger);

        // Check if value fields are accessed
        boolean accessesValue = HirTreeWalker.anyMatch(result.hir(),
                node -> node instanceof HirTerm.FieldAccess fa
                        && "value".equals(fa.fieldName()));

        // Check if there are comparison/equality checks on values
        boolean hasValueComparison = builtins.contains(DefaultFun.EqualsInteger)
                || builtins.contains(DefaultFun.LessThanEqualsInteger)
                || builtins.contains(DefaultFun.LessThanInteger)
                || builtins.contains(DefaultFun.EqualsData);

        if (hasArithmetic && accessesValue && !hasValueComparison) {
            return List.of(new Finding(
                    Severity.HIGH,
                    Category.VALUE_LEAK,
                    "Value manipulation without comparison check",
                    "The script performs arithmetic and accesses value fields but "
                            + "does not appear to compare values. This may allow value "
                            + "to leak from the script (e.g., outputs with less value "
                            + "than expected).",
                    "Global",
                    "Add value comparison checks to ensure outputs preserve the "
                            + "expected value."
            ));
        }

        return List.of();
    }
}
