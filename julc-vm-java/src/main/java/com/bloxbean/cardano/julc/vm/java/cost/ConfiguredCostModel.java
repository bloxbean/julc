package com.bloxbean.cardano.julc.vm.java.cost;

import com.bloxbean.cardano.julc.vm.LedgerEvaluationTarget;
import com.bloxbean.cardano.julc.vm.ProtocolFeatureProfile;

import java.util.Objects;

/**
 * A parsed cost model atomically associated with its resolved ledger profile.
 * Construction enforces the shared Java/Truffle completeness invariant before
 * either backend can start CEK evaluation.
 */
public record ConfiguredCostModel(
        CostModelParser.ParsedCostModel costModel,
        LedgerEvaluationTarget target,
        ProtocolFeatureProfile profile) {

    public ConfiguredCostModel {
        Objects.requireNonNull(costModel, "costModel");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(profile, "profile");
        if (!profile.target().equals(target)) {
            throw new IllegalArgumentException(
                    "Cost model target " + target + " does not match profile " + profile.target());
        }
        costModel.builtinCostModel().validateCompleteFor(profile);
    }
}
