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
        SourceReference source,
        List<String> domainAssumptions,
        List<String> guaranteeRules,
        boolean ledgerValidityModeled) {

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
        source = Objects.requireNonNull(source, "source");
        domainAssumptions = List.copyOf(domainAssumptions);
        guaranteeRules = List.copyOf(guaranteeRules);
    }

    public record PathSegment(String kind, String name, String resolvedType) { }

    public record SourceReference(String file, int line, int column, String fragment) { }
}
