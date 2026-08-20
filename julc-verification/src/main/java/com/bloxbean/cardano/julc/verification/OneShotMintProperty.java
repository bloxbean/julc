package com.bloxbean.cardano.julc.verification;

import java.util.List;
import java.util.Objects;

/** Certificate-facing IR for the E.4a one-shot authorized minting slice. */
public record OneShotMintProperty(
        int schemaVersion,
        String template,
        String propertyId,
        String validatorTitle,
        String scriptPurpose,
        String sourcePath,
        String authorityHex,
        String anchorTransactionIdHex,
        String anchorOutputIndex,
        String tokenNameHex,
        String quantity,
        String redeemerType,
        String canonicalDslJson,
        List<String> domainAssumptions,
        List<String> guaranteeRules,
        boolean ledgerValidityModeled) implements VerificationProperty {

    public static final int SCHEMA_VERSION = 1;
    public static final String TEMPLATE = "julc.dsl.one-shot-authorized-mint/v1";

    public OneShotMintProperty {
        if (schemaVersion != SCHEMA_VERSION || !TEMPLATE.equals(template)
                || !"minting".equals(scriptPurpose)) {
            throw new IllegalArgumentException("Unsupported one-shot mint property version");
        }
        propertyId = Objects.requireNonNull(propertyId, "propertyId");
        validatorTitle = Objects.requireNonNull(validatorTitle, "validatorTitle");
        sourcePath = Objects.requireNonNull(sourcePath, "sourcePath");
        authorityHex = Objects.requireNonNull(authorityHex, "authorityHex");
        anchorTransactionIdHex = Objects.requireNonNull(
                anchorTransactionIdHex, "anchorTransactionIdHex");
        anchorOutputIndex = Objects.requireNonNull(anchorOutputIndex, "anchorOutputIndex");
        tokenNameHex = Objects.requireNonNull(tokenNameHex, "tokenNameHex");
        quantity = Objects.requireNonNull(quantity, "quantity");
        redeemerType = Objects.requireNonNull(redeemerType, "redeemerType");
        canonicalDslJson = Objects.requireNonNull(canonicalDslJson, "canonicalDslJson");
        domainAssumptions = List.copyOf(domainAssumptions);
        guaranteeRules = List.copyOf(guaranteeRules);
        if (!ledgerValidityModeled
                || !domainAssumptions.equals(List.of("validMintingContext/v3-pinned"))) {
            throw new IllegalArgumentException(
                    "One-shot mint requires the pinned valid minting context domain");
        }
    }
}
