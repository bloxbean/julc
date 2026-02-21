package com.bloxbean.cardano.julc.analysis.rules;

import com.bloxbean.cardano.julc.analysis.Category;
import com.bloxbean.cardano.julc.analysis.Finding;
import com.bloxbean.cardano.julc.analysis.Severity;
import com.bloxbean.cardano.julc.decompiler.DecompileResult;
import com.bloxbean.cardano.julc.decompiler.hir.HirTerm;

import java.util.ArrayList;
import java.util.List;

/**
 * Checks for time-sensitive operations that lack time interval validation.
 * <p>
 * Contracts that deal with deadlines, expiry, or time-dependent logic
 * should validate {@code validRange} from the ScriptContext.
 */
public final class TimeValidationRule implements SecurityRule {

    @Override
    public String name() {
        return "TimeValidation";
    }

    @Override
    public List<Finding> analyze(DecompileResult result) {
        if (result.hir() == null) return List.of();

        var findings = new ArrayList<Finding>();

        // Check if the script uses time-related field names
        boolean hasTimeField = HirTreeWalker.anyMatch(result.hir(),
                node -> node instanceof HirTerm.FieldAccess fa
                        && ("validRange".equals(fa.fieldName())
                        || "from".equals(fa.fieldName())
                        || "to".equals(fa.fieldName())));

        // Check for string literals mentioning deadline/expiry
        boolean hasTimeLiteral = HirTreeWalker.anyMatch(result.hir(),
                node -> node instanceof HirTerm.StringLiteral sl
                        && (sl.value().toLowerCase().contains("deadline")
                        || sl.value().toLowerCase().contains("expir")
                        || sl.value().toLowerCase().contains("timeout")));

        // Check for time-related trace messages suggesting time logic
        boolean hasTimeTrace = HirTreeWalker.anyMatch(result.hir(),
                node -> node instanceof HirTerm.Trace tr
                        && tr.message() instanceof HirTerm.StringLiteral sl
                        && (sl.value().toLowerCase().contains("deadline")
                        || sl.value().toLowerCase().contains("expir")
                        || sl.value().toLowerCase().contains("time")));

        if ((hasTimeLiteral || hasTimeTrace) && !hasTimeField) {
            findings.add(new Finding(
                    Severity.MEDIUM,
                    Category.TIME_VALIDATION,
                    "Time-related logic without validRange check",
                    "The script contains time-related references (deadline/expiry) "
                            + "but does not appear to access validRange from ScriptContext. "
                            + "Time-dependent logic must validate the transaction's time "
                            + "bounds to be effective.",
                    "Global",
                    "Access and validate the validRange field from ScriptContext "
                            + "to enforce time constraints."
            ));
        }

        return findings;
    }
}
