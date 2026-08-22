package com.bloxbean.cardano.julc.verification.dsl.ir;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record DslPropertySet(
        int schemaVersion,
        DslPurpose purpose,
        String contractSchemaSha256,
        List<DslProperty> properties) {
    public static final int SCHEMA_VERSION = 1;
    public static final int MINTING_SCHEMA_VERSION = 2;
    public static final int COMPOSITION_SCHEMA_VERSION = 3;
    public static final int TYPED_SCHEMA_VERSION = 4;
    public static final int LEDGER_SCHEMA_VERSION = 5;
    public static final int AUTHORIZATION_SCHEMA_VERSION = 6;
    public static final int CERTIFICATE_PAYLOAD_SCHEMA_VERSION = 7;
    public static final int VALUE_ALGEBRA_SCHEMA_VERSION = 8;
    public static final int GOVERNANCE_SCHEMA_VERSION = 9;

    public DslPropertySet {
        if (schemaVersion != SCHEMA_VERSION
                && schemaVersion != MINTING_SCHEMA_VERSION
                && schemaVersion != COMPOSITION_SCHEMA_VERSION
                && schemaVersion != TYPED_SCHEMA_VERSION
                && schemaVersion != LEDGER_SCHEMA_VERSION
                && schemaVersion != AUTHORIZATION_SCHEMA_VERSION
                && schemaVersion != CERTIFICATE_PAYLOAD_SCHEMA_VERSION
                && schemaVersion != VALUE_ALGEBRA_SCHEMA_VERSION
                && schemaVersion != GOVERNANCE_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported DSL property schema " + schemaVersion);
        }
        if (schemaVersion == COMPOSITION_SCHEMA_VERSION
                || schemaVersion == TYPED_SCHEMA_VERSION
                || schemaVersion == LEDGER_SCHEMA_VERSION
                || schemaVersion == AUTHORIZATION_SCHEMA_VERSION
                || schemaVersion == CERTIFICATE_PAYLOAD_SCHEMA_VERSION
                || schemaVersion == VALUE_ALGEBRA_SCHEMA_VERSION
                || schemaVersion == GOVERNANCE_SCHEMA_VERSION) {
            purpose = Objects.requireNonNull(purpose,
                    "DSL property schemas 3 through 9 require an explicit purpose");
        } else if (purpose != null) {
            throw new IllegalArgumentException(
                    "DSL property schemas 1 and 2 do not carry an explicit purpose");
        }
        if (schemaVersion == TYPED_SCHEMA_VERSION
                || schemaVersion == LEDGER_SCHEMA_VERSION
                || schemaVersion == AUTHORIZATION_SCHEMA_VERSION
                || schemaVersion == CERTIFICATE_PAYLOAD_SCHEMA_VERSION
                || schemaVersion == VALUE_ALGEBRA_SCHEMA_VERSION
                || schemaVersion == GOVERNANCE_SCHEMA_VERSION) {
            if (contractSchemaSha256 == null
                    || !contractSchemaSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                    "DSL property schemas 4 through 9 require a canonical contract schema SHA-256");
            }
        } else if (contractSchemaSha256 != null) {
            throw new IllegalArgumentException(
                    "DSL property schemas 1 through 3 do not carry a contract schema hash");
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
            if ((schemaVersion == COMPOSITION_SCHEMA_VERSION
                    || schemaVersion == TYPED_SCHEMA_VERSION
                    || schemaVersion == LEDGER_SCHEMA_VERSION
                    || schemaVersion == AUTHORIZATION_SCHEMA_VERSION
                    || schemaVersion == CERTIFICATE_PAYLOAD_SCHEMA_VERSION
                    || schemaVersion == VALUE_ALGEBRA_SCHEMA_VERSION
                    || schemaVersion == GOVERNANCE_SCHEMA_VERSION)
                    && property.domain() == null) {
                throw new IllegalArgumentException(
                        "Compositional DSL property requires an explicit domain for "
                                + property.id());
            }
            if (schemaVersion != COMPOSITION_SCHEMA_VERSION
                    && schemaVersion != TYPED_SCHEMA_VERSION
                    && schemaVersion != LEDGER_SCHEMA_VERSION
                    && schemaVersion != AUTHORIZATION_SCHEMA_VERSION
                    && schemaVersion != CERTIFICATE_PAYLOAD_SCHEMA_VERSION
                    && schemaVersion != VALUE_ALGEBRA_SCHEMA_VERSION
                    && schemaVersion != GOVERNANCE_SCHEMA_VERSION
                    && property.domain() != null) {
                throw new IllegalArgumentException(
                        "DSL property schemas 1 and 2 encode their domain in the expression");
            }
        }
    }

    /** Frozen schema-1/schema-2 constructor retained for source and JSON compatibility. */
    public DslPropertySet(int schemaVersion, List<DslProperty> properties) {
        this(schemaVersion, null, null, properties);
    }

    /** Frozen three-component constructor retained for schema-1 through schema-3 source use. */
    public DslPropertySet(
            int schemaVersion, DslPurpose purpose, List<DslProperty> properties) {
        this(schemaVersion, purpose, null, properties);
    }

    public static DslPropertySet of(DslProperty... properties) {
        return new DslPropertySet(SCHEMA_VERSION, null, null, List.of(properties));
    }

    public static DslPropertySet minting(DslProperty property) {
        return new DslPropertySet(MINTING_SCHEMA_VERSION, null, null, List.of(property));
    }

    public static DslPropertySet composed(DslPurpose purpose, DslProperty... properties) {
        return new DslPropertySet(COMPOSITION_SCHEMA_VERSION, purpose, null,
                List.of(properties));
    }

    public static DslPropertySet typedV4(
            DslPurpose purpose, String contractSchemaSha256, DslProperty... properties) {
        return new DslPropertySet(TYPED_SCHEMA_VERSION, purpose,
                contractSchemaSha256, List.of(properties));
    }

    public static DslPropertySet typedV5(
            DslPurpose purpose, String contractSchemaSha256, DslProperty... properties) {
        return new DslPropertySet(LEDGER_SCHEMA_VERSION, purpose,
                contractSchemaSha256, List.of(properties));
    }

    public static DslPropertySet typedV6(
            DslPurpose purpose, String contractSchemaSha256, DslProperty... properties) {
        return new DslPropertySet(AUTHORIZATION_SCHEMA_VERSION, purpose,
                contractSchemaSha256, List.of(properties));
    }

    public static DslPropertySet typedV7(
            DslPurpose purpose, String contractSchemaSha256, DslProperty... properties) {
        return new DslPropertySet(CERTIFICATE_PAYLOAD_SCHEMA_VERSION, purpose,
                contractSchemaSha256, List.of(properties));
    }

    public static DslPropertySet typedV8(
            DslPurpose purpose, String contractSchemaSha256, DslProperty... properties) {
        return new DslPropertySet(VALUE_ALGEBRA_SCHEMA_VERSION, purpose,
                contractSchemaSha256, List.of(properties));
    }

    public static DslPropertySet typedV9(
            DslPurpose purpose, String contractSchemaSha256, DslProperty... properties) {
        return new DslPropertySet(GOVERNANCE_SCHEMA_VERSION, purpose,
                contractSchemaSha256, List.of(properties));
    }
}
