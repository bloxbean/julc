package com.bloxbean.cardano.julc.verification.dsl.type;

import com.bloxbean.cardano.julc.compiler.schema.ContractSchema;

import java.util.List;
import java.util.Objects;

/** Deterministic verification projection of one selected compiler-owned interface. */
public record ProjectedContractTypes(
        int schemaVersion,
        ContractSchema.Purpose purpose,
        VerificationTypeRef datumType,
        VerificationTypeRef redeemerType,
        List<Parameter> parameters,
        List<NominalDefinition> definitions) {
    public static final int SCHEMA_VERSION = 1;

    public ProjectedContractTypes {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported projected contract type schema " + schemaVersion);
        }
        purpose = Objects.requireNonNull(purpose, "purpose");
        redeemerType = Objects.requireNonNull(redeemerType, "redeemerType");
        parameters = List.copyOf(parameters);
        definitions = List.copyOf(definitions);
    }

    public record Parameter(String name, VerificationTypeRef type) {
        public Parameter {
            name = Objects.requireNonNull(name, "name");
            type = Objects.requireNonNull(type, "type");
        }
    }

    public record NominalDefinition(
            String stableId,
            String sourceName,
            NominalTypeRef.NominalKind nominalKind,
            List<Field> fields,
            List<Constructor> constructors) {
        public NominalDefinition {
            stableId = Objects.requireNonNull(stableId, "stableId");
            sourceName = Objects.requireNonNull(sourceName, "sourceName");
            nominalKind = Objects.requireNonNull(nominalKind, "nominalKind");
            fields = List.copyOf(fields);
            constructors = List.copyOf(constructors);
            if (nominalKind == NominalTypeRef.NominalKind.RECORD
                    && !constructors.isEmpty()) {
                throw new IllegalArgumentException("Record definition cannot have constructors");
            }
            if (nominalKind == NominalTypeRef.NominalKind.SUM && !fields.isEmpty()) {
                throw new IllegalArgumentException("Sum definition cannot have record fields");
            }
            if (nominalKind == NominalTypeRef.NominalKind.NEWTYPE) {
                throw new IllegalArgumentException(
                        "Compiler-owned newtype identity is not available yet");
            }
        }
    }

    public record Field(String name, VerificationTypeRef type) {
        public Field {
            name = Objects.requireNonNull(name, "name");
            type = Objects.requireNonNull(type, "type");
        }
    }

    public record Constructor(String name, int tag, List<Field> fields) {
        public Constructor {
            name = Objects.requireNonNull(name, "name");
            if (tag < 0) throw new IllegalArgumentException("Constructor tag cannot be negative");
            fields = List.copyOf(fields);
        }
    }
}
