package com.bloxbean.cardano.julc.analysis.rules;

import com.bloxbean.cardano.julc.analysis.Category;
import com.bloxbean.cardano.julc.analysis.Finding;
import com.bloxbean.cardano.julc.analysis.Severity;
import com.bloxbean.cardano.julc.core.DefaultFun;
import com.bloxbean.cardano.julc.decompiler.DecompileResult;
import com.bloxbean.cardano.julc.decompiler.hir.HirTerm;

import java.util.List;

/**
 * Checks for double satisfaction vulnerability.
 * <p>
 * In the UTxO model, a single transaction can satisfy multiple script inputs.
 * If a validator doesn't identify its own input (e.g., by checking the spending
 * TxOutRef), an attacker may craft a transaction that satisfies the validator
 * multiple times with a single output.
 */
public final class DoubleSatisfactionRule implements SecurityRule {

    @Override
    public String name() {
        return "DoubleSatisfaction";
    }

    @Override
    public List<Finding> analyze(DecompileResult result) {
        if (result.hir() == null) return List.of();

        // Check if the script identifies its own input
        // Common patterns: accessing "purpose" or checking TxOutRef fields
        boolean hasOwnInputIdentification = HirTreeWalker.anyMatch(result.hir(),
                node -> node instanceof HirTerm.FieldAccess fa
                        && ("purpose".equals(fa.fieldName())
                        || "scriptPurpose".equals(fa.fieldName())
                        || "txOutRef".equals(fa.fieldName())
                        || "txId".equals(fa.fieldName())));

        // Alternative: FunCall to a helper like "findOwnInput"
        boolean hasFindOwnInput = HirTreeWalker.anyMatch(result.hir(),
                node -> node instanceof HirTerm.FunCall fc
                        && fc.name().toLowerCase().contains("owninput"));

        // Check if the script processes multiple inputs (iterates over inputs list)
        boolean processesInputs = HirTreeWalker.anyMatch(result.hir(),
                node -> node instanceof HirTerm.FieldAccess fa
                        && "inputs".equals(fa.fieldName()));

        if (processesInputs && !hasOwnInputIdentification && !hasFindOwnInput) {
            return List.of(new Finding(
                    Severity.HIGH,
                    Category.DOUBLE_SATISFACTION,
                    "Possible double satisfaction vulnerability",
                    "The script processes transaction inputs but does not appear to "
                            + "identify its own input (via purpose/txOutRef). An attacker "
                            + "may craft a transaction with multiple script inputs that are "
                            + "all satisfied by a single output.",
                    "Global",
                    "Identify the script's own input using the spending purpose "
                            + "or by matching the TxOutRef against the inputs list."
            ));
        }

        return List.of();
    }
}
