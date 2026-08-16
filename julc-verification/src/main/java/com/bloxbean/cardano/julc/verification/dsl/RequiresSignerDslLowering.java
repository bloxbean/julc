package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.RequiresSignerProperty;
import com.bloxbean.cardano.julc.verification.dsl.ir.DslPropertySet;

import static com.bloxbean.cardano.julc.verification.dsl.VerificationDsl.property;

/** Canonical annotation-to-DSL lowering used to prevent frontend semantic drift. */
public final class RequiresSignerDslLowering {
    private RequiresSignerDslLowering() { }

    public static DslPropertySet lower(RequiresSignerProperty propertyIr) {
        var model = new SpendingContractModel();
        String field = propertyIr.path().getLast().name();
        var signerRequired = model.context().txInfo().signatories()
                .contains(model.datum().bytesField(field));
        return DslPropertySet.of(property(propertyIr.propertyId(),
                model.exactUplcSucceeds().implies(signerRequired)));
    }
}
