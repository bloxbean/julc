package com.bloxbean.cardano.julc.verification;

import java.util.List;
import java.util.Objects;

/** Typed IR for the exact-own-policy controlled minting profile v1. */
public record ControlledMintProperty(
        int schemaVersion,
        String template,
        String propertyId,
        String validatorTitle,
        String scriptPurpose,
        String sourcePath,
        String authorityHex,
        String tokenNameHex,
        String quantity,
        String action,
        String redeemerType,
        SourceReference source,
        List<String> domainAssumptions,
        List<String> guaranteeRules,
        boolean ledgerValidityModeled) implements VerificationProperty {

    public static final int SCHEMA_VERSION = 1;
    public static final String TEMPLATE = "julc.controlled-mint/v1";

    public ControlledMintProperty {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported property IR schema " + schemaVersion);
        }
        if (!TEMPLATE.equals(template)) {
            throw new IllegalArgumentException("Unsupported controlled-mint template " + template);
        }
        propertyId = Objects.requireNonNull(propertyId, "propertyId");
        validatorTitle = Objects.requireNonNull(validatorTitle, "validatorTitle");
        scriptPurpose = Objects.requireNonNull(scriptPurpose, "scriptPurpose");
        sourcePath = Objects.requireNonNull(sourcePath, "sourcePath");
        authorityHex = Objects.requireNonNull(authorityHex, "authorityHex");
        tokenNameHex = Objects.requireNonNull(tokenNameHex, "tokenNameHex");
        quantity = Objects.requireNonNull(quantity, "quantity");
        action = Objects.requireNonNull(action, "action");
        redeemerType = Objects.requireNonNull(redeemerType, "redeemerType");
        source = Objects.requireNonNull(source, "source");
        domainAssumptions = List.copyOf(domainAssumptions);
        guaranteeRules = List.copyOf(guaranteeRules);
    }

    public record SourceReference(String file, int line, int column, String fragment) { }
}
