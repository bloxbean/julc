package com.bloxbean.cardano.julc.verification;

import java.util.List;
import java.util.Objects;

/** Versioned, fully resolved property IR consumed by Lean generation. */
public record RequiresSignerProperty(
        int schemaVersion,
        String template,
        String propertyId,
        String validatorTitle,
        String scriptPurpose,
        String sourcePath,
        List<PathSegment> path,
        String datumType,
        String ownerType,
        String canonicalDslJson,
        String projectedContractTypesJson,
        String contractSchemaSha256,
        SourceReference source,
        List<String> domainAssumptions,
        List<String> guaranteeRules,
        boolean ledgerValidityModeled) implements VerificationProperty {

    public static final int SCHEMA_VERSION = 1;
    public static final String TEMPLATE = "julc.requires-signer/v1";

    public RequiresSignerProperty {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported property IR schema " + schemaVersion);
        }
        template = Objects.requireNonNull(template, "template");
        propertyId = Objects.requireNonNull(propertyId, "propertyId");
        validatorTitle = Objects.requireNonNull(validatorTitle, "validatorTitle");
        scriptPurpose = Objects.requireNonNull(scriptPurpose, "scriptPurpose");
        sourcePath = Objects.requireNonNull(sourcePath, "sourcePath");
        path = List.copyOf(path);
        datumType = Objects.requireNonNull(datumType, "datumType");
        ownerType = Objects.requireNonNull(ownerType, "ownerType");
        canonicalDslJson = Objects.requireNonNull(canonicalDslJson, "canonicalDslJson");
        projectedContractTypesJson = Objects.requireNonNull(
                projectedContractTypesJson, "projectedContractTypesJson");
        contractSchemaSha256 = Objects.requireNonNull(
                contractSchemaSha256, "contractSchemaSha256");
        if (!contractSchemaSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid contract schema SHA-256");
        }
        source = Objects.requireNonNull(source, "source");
        domainAssumptions = List.copyOf(domainAssumptions);
        guaranteeRules = List.copyOf(guaranteeRules);
    }

    public record PathSegment(String kind, String name, String resolvedType) { }

    public record SourceReference(String file, int line, int column, String fragment) { }
}
