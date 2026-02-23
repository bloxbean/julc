package com.bloxbean.cardano.julc.analysis.rules;

import com.bloxbean.cardano.julc.analysis.Category;
import com.bloxbean.cardano.julc.analysis.Finding;
import com.bloxbean.cardano.julc.analysis.Severity;
import com.bloxbean.cardano.julc.decompiler.DecompileResult;
import com.bloxbean.cardano.julc.decompiler.hir.HirTerm;

import java.util.List;

/**
 * Checks for datum integrity — validators that produce outputs
 * should validate their datum.
 * <p>
 * A state-machine style contract that doesn't validate the output datum
 * may allow an attacker to set an arbitrary datum on the continuing output,
 * breaking the expected state transition.
 */
public final class DatumIntegrityRule implements SecurityRule {

    @Override
    public String name() {
        return "DatumIntegrity";
    }

    @Override
    public List<Finding> analyze(DecompileResult result) {
        if (result.hir() == null) return List.of();

        // Check if the script accesses outputs
        boolean accessesOutputs = HirTreeWalker.anyMatch(result.hir(),
                node -> node instanceof HirTerm.FieldAccess fa
                        && "outputs".equals(fa.fieldName()));

        // Check if any datum field is accessed on outputs
        boolean checksDatum = HirTreeWalker.anyMatch(result.hir(),
                node -> node instanceof HirTerm.FieldAccess fa
                        && ("datum".equals(fa.fieldName())
                        || "datumHash".equals(fa.fieldName())
                        || "outputDatum".equals(fa.fieldName())));

        // Also check for "getContinuingOutputs" or similar function calls
        boolean hasContinuingOutputs = HirTreeWalker.anyMatch(result.hir(),
                node -> node instanceof HirTerm.FunCall fc
                        && fc.name().toLowerCase().contains("continuing"));

        // Check if outputs are accessed via iteration (ForEach/While over outputs)
        boolean iteratesOutputs = accessesOutputs && HirTreeWalker.anyMatch(result.hir(),
                node -> node instanceof HirTerm.ForEach || node instanceof HirTerm.While);

        if ((accessesOutputs || hasContinuingOutputs) && !checksDatum && iteratesOutputs) {
            return List.of(new Finding(
                    Severity.MEDIUM,
                    Category.DATUM_INTEGRITY,
                    "Outputs accessed without datum validation",
                    "The script accesses transaction outputs and iterates over them "
                            + "but does not appear to validate output datums. In a state-machine "
                            + "contract, this may allow an attacker to set an arbitrary datum "
                            + "on the continuing output.",
                    "Global",
                    "Validate the datum on continuing outputs to ensure correct "
                            + "state transitions."
            ));
        }

        return List.of();
    }
}
