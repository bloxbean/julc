package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.ControlledMintProperty;
import com.bloxbean.cardano.julc.verification.dsl.ir.DslPropertySet;

/** Canonical shared AST for the annotation and typed DSL controlled-mint profile. */
public final class ControlledMintDslLowering {
    private ControlledMintDslLowering() { }

    public static DslPropertySet lower(ControlledMintProperty property) {
        return MintingDsl.controlledMintPropertySet(
                property.propertyId(), property.authorityHex(), property.tokenNameHex(),
                property.quantity());
    }
}
