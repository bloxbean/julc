package com.bloxbean.cardano.julc.verification;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Objects;

/** Certificate-facing representation of an admitted, normalized schema-3 DSL property set. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ComposedDslProperty(
        int schemaVersion,
        String template,
        String propertyId,
        String validatorTitle,
        String scriptPurpose,
        String sourcePath,
        String canonicalDslJson,
        List<Claim> claims,
        List<String> domainAssumptions,
        List<String> guaranteeRules,
        boolean ledgerValidityModeled,
        String projectedContractTypesJson,
        String contractSchemaSha256) implements VerificationProperty {
    public static final int SCHEMA_VERSION = 1;
    public static final int TYPED_SCHEMA_VERSION = 2;
    public static final String TEMPLATE = "julc.dsl-composed/v1";
    public static final String TYPED_TEMPLATE = "julc.dsl-typed/v1";

    public ComposedDslProperty {
        template = Objects.requireNonNull(template, "template");
        propertyId = Objects.requireNonNull(propertyId, "propertyId");
        validatorTitle = Objects.requireNonNull(validatorTitle, "validatorTitle");
        scriptPurpose = Objects.requireNonNull(scriptPurpose, "scriptPurpose");
        sourcePath = Objects.requireNonNull(sourcePath, "sourcePath");
        if (sourcePath.isBlank() || sourcePath.length() > 4096
                || sourcePath.indexOf('\n') >= 0 || sourcePath.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid composed DSL source path");
        }
        canonicalDslJson = Objects.requireNonNull(canonicalDslJson, "canonicalDslJson");
        claims = List.copyOf(claims);
        domainAssumptions = List.copyOf(domainAssumptions);
        guaranteeRules = List.copyOf(guaranteeRules);
        if (claims.isEmpty()) throw new IllegalArgumentException("At least one claim is required");
        if (schemaVersion == TYPED_SCHEMA_VERSION) {
            projectedContractTypesJson = Objects.requireNonNull(
                    projectedContractTypesJson, "projectedContractTypesJson");
            if (contractSchemaSha256 == null
                    || !contractSchemaSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                        "Typed DSL property requires a contract schema SHA-256");
            }
        } else if (schemaVersion == SCHEMA_VERSION) {
            if (projectedContractTypesJson != null || contractSchemaSha256 != null) {
                throw new IllegalArgumentException(
                        "Schema-3 composed property cannot carry schema-4 type metadata");
            }
        } else {
            throw new IllegalArgumentException(
                    "Unsupported composed DSL property schema " + schemaVersion);
        }
    }

    /** Compatibility constructor for the frozen schema-3 property representation. */
    public ComposedDslProperty(
            int schemaVersion,
            String template,
            String propertyId,
            String validatorTitle,
            String scriptPurpose,
            String sourcePath,
            String canonicalDslJson,
            List<Claim> claims,
            List<String> domainAssumptions,
            List<String> guaranteeRules,
            boolean ledgerValidityModeled) {
        this(schemaVersion, template, propertyId, validatorTitle, scriptPurpose,
                sourcePath, canonicalDslJson, claims, domainAssumptions,
                guaranteeRules, ledgerValidityModeled, null, null);
    }

    /** One independently executed and certified theorem claim. */
    public record Claim(
            String id,
            String generatedName,
            String domain,
            String guaranteeSha256,
            String envelopeSha256,
            List<String> capabilities,
            List<String> guaranteeRules,
            String counterexampleDomain,
            boolean ledgerValidCounterexampleEstablished,
            boolean concreteVmCounterexampleReproduced) {
        public Claim {
            id = Objects.requireNonNull(id, "id");
            generatedName = Objects.requireNonNull(generatedName, "generatedName");
            domain = Objects.requireNonNull(domain, "domain");
            guaranteeSha256 = Objects.requireNonNull(guaranteeSha256, "guaranteeSha256");
            envelopeSha256 = Objects.requireNonNull(envelopeSha256, "envelopeSha256");
            capabilities = List.copyOf(capabilities);
            guaranteeRules = List.copyOf(guaranteeRules);
            counterexampleDomain = Objects.requireNonNull(
                    counterexampleDomain, "counterexampleDomain");
        }
    }
}
