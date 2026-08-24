package com.bloxbean.cardano.julc.verification.dsl.ir;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Canonical public verification DSL envelope. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DslPropertySet(
        String format,
        int schemaVersion,
        DslPurpose purpose,
        String contractSchemaSha256,
        List<DslProperty> properties) {
    public static final String FORMAT = "julc.verification.dsl";
    public static final int SCHEMA_VERSION = 1;

    public DslPropertySet {
        if (!FORMAT.equals(format)) {
            throw new IllegalArgumentException(
                    "Unsupported DSL property format: " + format);
        }
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported DSL property schema " + schemaVersion
                            + "; expected " + FORMAT + " schema " + SCHEMA_VERSION);
        }
        purpose = Objects.requireNonNull(
                purpose, "DSL property schema 1 requires an explicit purpose");
        if (contractSchemaSha256 == null
                || !contractSchemaSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "DSL property schema 1 requires a canonical contract schema SHA-256");
        }
        properties = List.copyOf(properties == null ? List.of() : properties);
        if (properties.isEmpty()) {
            throw new IllegalArgumentException("At least one property is required");
        }
        var ids = new HashSet<String>();
        for (DslProperty property : properties) {
            Objects.requireNonNull(property, "property");
            if (!ids.add(property.id())) {
                throw new IllegalArgumentException("Duplicate property ID: " + property.id());
            }
            if (property.domain() == null) {
                throw new IllegalArgumentException(
                        "DSL property requires an explicit domain for " + property.id());
            }
        }
    }

    public static DslPropertySet schema1(
            DslPurpose purpose,
            String contractSchemaSha256,
            DslProperty... properties) {
        return new DslPropertySet(FORMAT, SCHEMA_VERSION, purpose,
                contractSchemaSha256, List.of(properties));
    }
}
