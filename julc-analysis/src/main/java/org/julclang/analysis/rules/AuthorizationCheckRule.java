package org.julclang.analysis.rules;

import org.julclang.analysis.Category;
import org.julclang.analysis.Finding;
import org.julclang.analysis.Severity;
import org.julclang.core.DefaultFun;
import org.julclang.decompiler.DecompileResult;
import org.julclang.decompiler.hir.HirTerm;

import java.util.List;

/**
 * Checks for the presence of signatory authorization checks.
 * <p>
 * A validator that manipulates value without checking signatories
 * may allow unauthorized transactions.
 */
public final class AuthorizationCheckRule implements SecurityRule {

    @Override
    public String name() {
        return "AuthorizationCheck";
    }

    @Override
    public List<Finding> analyze(DecompileResult result) {
        if (result.hir() == null) return List.of();

        // Look for signatory-related field access
        boolean hasSignatoryAccess = HirTreeWalker.anyMatch(result.hir(),
                node -> node instanceof HirTerm.FieldAccess fa
                        && "signatories".equals(fa.fieldName()));

        // Look for EqualsByteString comparisons (signatory checks)
        boolean hasByteStringEquality = HirTreeWalker.anyMatch(result.hir(),
                node -> node instanceof HirTerm.BuiltinCall bc
                        && bc.fun() == DefaultFun.EqualsByteString);

        // Look for EqualsData comparisons (alternative signatory check)
        boolean hasDataEquality = HirTreeWalker.anyMatch(result.hir(),
                node -> node instanceof HirTerm.BuiltinCall bc
                        && bc.fun() == DefaultFun.EqualsData);

        boolean hasAnyEqualityCheck = hasByteStringEquality || hasDataEquality;

        // If the script accesses value-related operations but has no authorization
        boolean manipulatesValue = result.stats().builtinsUsed().contains(DefaultFun.AddInteger)
                || result.stats().builtinsUsed().contains(DefaultFun.SubtractInteger)
                || HirTreeWalker.anyMatch(result.hir(),
                node -> node instanceof HirTerm.FieldAccess fa
                        && ("value".equals(fa.fieldName()) || "mint".equals(fa.fieldName())));

        if (!hasSignatoryAccess && !hasAnyEqualityCheck && manipulatesValue) {
            return List.of(new Finding(
                    Severity.HIGH,
                    Category.MISSING_AUTHORIZATION,
                    "No signatory authorization check detected",
                    "The script performs value manipulation but does not appear to check "
                            + "signatories. This may allow unauthorized parties to submit "
                            + "transactions that interact with this script.",
                    "Global",
                    "Add a signatory check to verify the transaction is authorized "
                            + "by the expected party."
            ));
        }

        // Even if there's a signatory access, if there's no equality check,
        // the signatories list may be accessed but never validated
        if (hasSignatoryAccess && !hasAnyEqualityCheck) {
            return List.of(new Finding(
                    Severity.MEDIUM,
                    Category.MISSING_AUTHORIZATION,
                    "Signatories accessed but not validated",
                    "The script accesses the signatories list but does not appear to "
                            + "compare any signatory against an expected value. The check "
                            + "may be incomplete.",
                    "Global",
                    "Ensure signatory hashes are compared against expected values."
            ));
        }

        return List.of();
    }
}
