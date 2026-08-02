package com.bloxbean.cardano.julc.vm.java.cost;

import com.bloxbean.cardano.julc.core.DefaultFun;
import com.bloxbean.cardano.julc.vm.ProtocolFeatureProfile;
import com.bloxbean.cardano.julc.vm.java.CekEvaluationException;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Objects;

/**
 * Indicates that a builtin which is available to an evaluation profile has no
 * complete CPU/memory price in the selected cost model.
 * <p>
 * This is an evaluator-configuration error, not a zero-cost builtin. It is a
 * {@link CekEvaluationException} so a defensive failure at the charging
 * boundary is reported through the normal VM failure path.
 */
public final class IncompleteCostModelException extends CekEvaluationException {

    public IncompleteCostModelException(String message) {
        super(message);
    }

    static IncompleteCostModelException missing(
            ProtocolFeatureProfile profile, Collection<DefaultFun> missingBuiltins) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(missingBuiltins, "missingBuiltins");
        var ordered = missingBuiltins.isEmpty()
                ? EnumSet.noneOf(DefaultFun.class)
                : EnumSet.copyOf(missingBuiltins);
        return new IncompleteCostModelException(
                "Incomplete builtin cost model for " + profile.target()
                        + ": missing CPU/memory cost for " + ordered);
    }
}
