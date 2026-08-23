package com.bloxbean.cardano.julc.verification;

import java.util.List;
import java.util.Objects;

/** Typed IR for the complete JuLC stateful-spending profile v1. */
public record StatefulSpendingProperty(
        int schemaVersion,
        String template,
        String propertyId,
        String validatorTitle,
        String scriptPurpose,
        String sourcePath,
        Selection authority,
        Selection currentState,
        Selection nextState,
        String datumType,
        String redeemerType,
        String relation,
        String outputSelection,
        String canonicalDslJson,
        String projectedContractTypesJson,
        String contractSchemaSha256,
        List<SourceReference> sources,
        List<String> domainAssumptions,
        List<String> guaranteeRules,
        boolean ledgerValidityModeled) implements VerificationProperty {

    public static final int SCHEMA_VERSION = 1;
    public static final String TEMPLATE = "julc.stateful-spending/v1";

    public StatefulSpendingProperty {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported property IR schema " + schemaVersion);
        }
        if (!TEMPLATE.equals(template)) {
            throw new IllegalArgumentException("Unsupported stateful template " + template);
        }
        propertyId = Objects.requireNonNull(propertyId, "propertyId");
        validatorTitle = Objects.requireNonNull(validatorTitle, "validatorTitle");
        scriptPurpose = Objects.requireNonNull(scriptPurpose, "scriptPurpose");
        sourcePath = Objects.requireNonNull(sourcePath, "sourcePath");
        authority = Objects.requireNonNull(authority, "authority");
        currentState = Objects.requireNonNull(currentState, "currentState");
        nextState = Objects.requireNonNull(nextState, "nextState");
        datumType = Objects.requireNonNull(datumType, "datumType");
        redeemerType = Objects.requireNonNull(redeemerType, "redeemerType");
        relation = Objects.requireNonNull(relation, "relation");
        outputSelection = Objects.requireNonNull(outputSelection, "outputSelection");
        canonicalDslJson = Objects.requireNonNull(canonicalDslJson, "canonicalDslJson");
        projectedContractTypesJson = Objects.requireNonNull(
                projectedContractTypesJson, "projectedContractTypesJson");
        contractSchemaSha256 = Objects.requireNonNull(
                contractSchemaSha256, "contractSchemaSha256");
        if (!contractSchemaSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid contract schema SHA-256");
        }
        sources = List.copyOf(sources);
        domainAssumptions = List.copyOf(domainAssumptions);
        guaranteeRules = List.copyOf(guaranteeRules);
    }

    public record Selection(String root, String field, String resolvedType) {
        public Selection {
            root = Objects.requireNonNull(root, "root");
            field = Objects.requireNonNull(field, "field");
            resolvedType = Objects.requireNonNull(resolvedType, "resolvedType");
        }

        public String path() {
            return root + "." + field;
        }
    }

    public record SourceReference(
            String annotation, String file, int line, int column, String fragment) { }
}
