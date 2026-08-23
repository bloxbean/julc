package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.DslDomain;
import com.bloxbean.cardano.julc.verification.dsl.ir.DslPropertySet;
import com.bloxbean.cardano.julc.verification.dsl.ir.DslPurpose;

import java.math.BigInteger;

import static com.bloxbean.cardano.julc.verification.dsl.VerificationDsl.integer;
import static com.bloxbean.cardano.julc.verification.dsl.VerificationDsl.keyHash;
import static com.bloxbean.cardano.julc.verification.dsl.VerificationDsl.property;
import static com.bloxbean.cardano.julc.verification.dsl.VerificationDsl.tokenName;

/** Reviewed controlled-mint guarantee in the public schema-1 DSL. */
public final class MintingDsl {
    private MintingDsl() { }

    public static DslPropertySet controlledMintPropertySet(
            String propertyId,
            String authorityHex,
            String tokenNameHex,
            String quantity,
            String contractSchemaSha256) {
        var contract = new MintingContractModel();
        var signedQuantity = integer(quantity);
        var direction = new BigInteger(quantity).signum() > 0
                ? signedQuantity.gt(integer(0)) : signedQuantity.lt(integer(0));
        var guarantee = contract.redeemerStrictlyDecodes()
                .and(contract.context().txInfo().signatories().contains(keyHash(authorityHex)))
                .and(contract.context().txInfo().mint().exactOwnPolicyAsset(
                        contract.ownPolicy(), tokenName(tokenNameHex), signedQuantity))
                .and(direction);
        return DslPropertySet.schema1(DslPurpose.MINTING, contractSchemaSha256,
                property(propertyId, DslDomain.NONE, guarantee));
    }
}
