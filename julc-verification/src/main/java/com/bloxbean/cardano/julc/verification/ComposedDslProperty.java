package com.bloxbean.cardano.julc.verification;

import java.util.List;
import java.util.Objects;

/** Certificate-facing representation of an admitted, normalized schema-3 DSL property set. */
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
        boolean ledgerValidityModeled) implements VerificationProperty {
    public static final int SCHEMA_VERSION = 1;
    public static final String TEMPLATE = "julc.dsl-composed/v1";

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
