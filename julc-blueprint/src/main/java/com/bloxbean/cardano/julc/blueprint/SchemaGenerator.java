package com.bloxbean.cardano.julc.blueprint;

import com.bloxbean.cardano.julc.compiler.pir.PirType;
import com.bloxbean.cardano.julc.compiler.schema.ContractSchema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Converts compiler-owned contract metadata to CIP-57 schemas. */
public final class SchemaGenerator {

    private SchemaGenerator() {}

    /** A composable CIP-57 Plutus Data schema. */
    public record Schema(
            String title,
            String ref,
            String dataType,
            String description,
            Integer index,
            List<Schema> fields,
            List<Schema> anyOf,
            Schema items,
            Schema keys,
            Schema values) {

        public Schema {
            fields = fields == null ? null : List.copyOf(fields);
            anyOf = anyOf == null ? null : List.copyOf(anyOf);
        }

        static Schema ref(String title, String ref) {
            return new Schema(title, ref, null, null, null,
                    null, null, null, null, null);
        }

        static Schema primitive(String dataType) {
            return new Schema(null, null, dataType, null, null,
                    null, null, null, null, null);
        }

        static Schema data() {
            return new Schema(null, null, null, "Any Plutus data.", null,
                    null, null, null, null, null);
        }

        static Schema constructor(String title, int index, List<Schema> fields) {
            return new Schema(title, null, "constructor", null, index,
                    fields, null, null, null, null);
        }

        static Schema sum(String title, List<Schema> variants) {
            return new Schema(title, null, null, null, null,
                    null, variants, null, null, null);
        }

        static Schema list(Schema items) {
            return new Schema(null, null, "list", null, null,
                    null, null, items, null, null);
        }

        static Schema map(Schema keys, Schema values) {
            return new Schema(null, null, "map", null, null,
                    null, null, null, keys, values);
        }

        Schema titled(String newTitle) {
            return new Schema(newTitle, ref, dataType, description, index,
                    fields, anyOf, items, keys, values);
        }

        Schema untitled() {
            return titled(null);
        }
    }

    /** CIP-57 roots and definitions for one compiled validator. */
    public record ValidatorSchema(
            Schema datum,
            Schema redeemer,
            List<Schema> parameters,
            Map<String, Schema> definitions) {
        public ValidatorSchema {
            parameters = List.copyOf(parameters);
            definitions = Collections.unmodifiableMap(new LinkedHashMap<>(definitions));
        }
    }

    public static ValidatorSchema from(ContractSchema contractSchema) {
        return from(contractSchema, null);
    }

    /** Convert a contract schema using a validator-specific definition namespace. */
    public static ValidatorSchema from(ContractSchema contractSchema, String namespace) {
        Objects.requireNonNull(contractSchema, "contractSchema");
        return from(contractSchema, contractSchema.singleInterface(), namespace);
    }

    /** Convert one selected interface while retaining the contract's shared metadata. */
    public static ValidatorSchema from(
            ContractSchema contractSchema,
            ContractSchema.ValidatorInterface validatorInterface,
            String namespace) {
        return from(contractSchema, validatorInterface, namespace, false);
    }

    /**
     * Convert one interface, optionally using stable compiler identities when
     * two named types in the same script share a Java simple name.
     */
    public static ValidatorSchema from(
            ContractSchema contractSchema,
            ContractSchema.ValidatorInterface validatorInterface,
            String namespace,
            boolean distinguishSameSimpleNames) {
        Objects.requireNonNull(contractSchema, "contractSchema");
        Objects.requireNonNull(validatorInterface, "validatorInterface");
        if (!contractSchema.interfaces().contains(validatorInterface)) {
            throw new IllegalArgumentException("Selected interface does not belong to contract schema");
        }
        var converter = new Converter(
                namespace, contractSchema.namedDefinitions(), distinguishSameSimpleNames);
        Schema datum = validatorInterface.datum() == null
                ? null
                : converter.root(validatorInterface.datum());
        Schema redeemer = converter.root(validatorInterface.redeemer());
        var parameters = contractSchema.parameters().stream()
                .map(converter::root)
                .toList();
        return new ValidatorSchema(datum, redeemer, parameters, converter.definitions);
    }

    private static final class Converter {
        private final String namespace;
        private final Map<String, PirType> namedDefinitions;
        private final boolean distinguishSameSimpleNames;
        private final Map<String, Schema> definitions = new LinkedHashMap<>();
        private final Map<String, PirType> definitionTypes = new LinkedHashMap<>();
        private final List<String> inProgress = new ArrayList<>();

        private Converter(
                String namespace,
                Map<String, PirType> namedDefinitions,
                boolean distinguishSameSimpleNames) {
            this.namespace = namespace == null || namespace.isBlank() ? null : namespace;
            this.namedDefinitions = namedDefinitions;
            this.distinguishSameSimpleNames = distinguishSameSimpleNames;
        }

        private Schema root(ContractSchema.Argument argument) {
            try {
                String key = definitionKey(argument.type());
                ensureDefinition(key, argument.type());
                return Schema.ref(argument.name(), "#/definitions/" + jsonPointer(key));
            } catch (SchemaGenerationException e) {
                String location = argument.sourceLocation() == null
                        ? ""
                        : " " + argument.sourceLocation();
                throw new SchemaGenerationException(
                        "Cannot generate schema for contract argument '" + argument.name()
                                + "': " + e.getMessage() + location, e);
            }
        }

        private void ensureDefinition(String key, PirType type) {
            PirType resolvedType = resolveNamed(type);
            PirType canonicalType = canonicalType(resolvedType);
            var existingType = definitionTypes.get(key);
            if (existingType != null) {
                if (!existingType.equals(canonicalType)) {
                    throw new SchemaGenerationException(
                            "Schema definition name collision for '" + key + "'");
                }
                return;
            }
            if (inProgress.contains(key)) {
                return;
            }
            definitionTypes.put(key, canonicalType);
            inProgress.add(key);
            Schema schema = schemaForDefinition(resolvedType);
            inProgress.removeLast();
            definitions.put(key, schema.titled(namedTitle(resolvedType, key)));
        }

        private Schema schemaFor(PirType type, String title) {
            Schema schema = switch (type) {
                case PirType.IntegerType _ -> Schema.primitive("integer");
                case PirType.ByteStringType _ -> Schema.primitive("bytes");
                case PirType.StringType _ -> Schema.primitive("bytes");
                case PirType.DataType _ -> Schema.data();
                case PirType.BoolType _ -> Schema.sum("Bool", List.of(
                        Schema.constructor("False", 0, List.of()),
                        Schema.constructor("True", 1, List.of())));
                case PirType.ListType list -> Schema.list(schemaFor(list.elemType(), null));
                case PirType.MapType map -> Schema.map(
                        schemaFor(map.keyType(), null), schemaFor(map.valueType(), null));
                case PirType.OptionalType optional -> Schema.sum("Optional", List.of(
                        Schema.constructor("Some", 0,
                                List.of(schemaFor(optional.elemType(), "value"))),
                        Schema.constructor("None", 1, List.of())));
                case PirType.RecordType record -> namedReference(record);
                case PirType.SumType sum -> namedReference(sum);
                case PirType.NamedTypeRef ref -> namedReference(ref);
                case PirType.UnitType _ -> Schema.constructor("Unit", 0, List.of());
                case PirType.PairType _, PirType.ArrayType _, PirType.FunType _ ->
                        throw new SchemaGenerationException(
                                "Unsupported compiler boundary type " + type.getClass().getSimpleName());
            };
            return title == null ? schema : schema.titled(title);
        }

        private Schema namedReference(PirType type) {
            String key = definitionKey(type);
            ensureDefinition(key, type);
            return Schema.ref(null, "#/definitions/" + jsonPointer(key));
        }

        private Schema namedDefinition(PirType.RecordType record) {
            var fields = record.fields().stream()
                    .map(field -> schemaFor(field.type(), field.name()))
                    .toList();
            return Schema.sum(record.name(), List.of(
                    Schema.constructor(record.name(), 0, fields)));
        }

        private Schema namedDefinition(PirType.SumType sum) {
            var constructors = sum.constructors().stream()
                    .map(constructor -> Schema.constructor(
                            constructor.name(),
                            constructor.tag(),
                            constructor.fields().stream()
                                    .map(field -> schemaFor(field.type(), field.name()))
                                    .toList()))
                    .toList();
            return Schema.sum(sum.name(), constructors);
        }

        private String definitionKey(PirType type) {
            return switch (type) {
                case PirType.IntegerType _ -> "@julc:Int";
                case PirType.ByteStringType _, PirType.StringType _ -> "@julc:ByteArray";
                case PirType.DataType _ -> "@julc:Data";
                case PirType.BoolType _ -> "@julc:Bool";
                case PirType.ListType list -> "@julc:List_" + definitionKey(list.elemType());
                case PirType.MapType map -> "@julc:Map_" + definitionKey(map.keyType())
                        + "_" + definitionKey(map.valueType());
                case PirType.OptionalType optional -> "@julc:Optional_"
                        + definitionKey(optional.elemType());
                case PirType.RecordType record -> namedKey(record, record.name());
                case PirType.SumType sum -> namedKey(sum, sum.name());
                case PirType.NamedTypeRef ref -> namedKey(ref, ref.name());
                case PirType.UnitType _ -> "@julc:Unit";
                case PirType.PairType _, PirType.ArrayType _, PirType.FunType _ ->
                        throw new SchemaGenerationException(
                                "Unsupported compiler boundary type " + type.getClass().getSimpleName());
            };
        }

        private String namedKey(PirType type, String simpleName) {
            String identity = simpleName;
            if (distinguishSameSimpleNames && hasDuplicateSimpleName(simpleName)) {
                identity = stableIdentity(type, simpleName);
            }
            return namespace == null ? identity : namespace + ":" + identity;
        }

        private boolean hasDuplicateSimpleName(String simpleName) {
            return namedDefinitions.values().stream()
                    .filter(type -> namedTypeName(type).equals(simpleName))
                    .limit(2)
                    .count() > 1;
        }

        private String stableIdentity(PirType type, String fallback) {
            if (type instanceof PirType.NamedTypeRef ref) return ref.stableId();
            return namedDefinitions.entrySet().stream()
                    .filter(entry -> entry.getValue() == type)
                    .map(Map.Entry::getKey)
                    .findFirst()
                    .orElse(fallback);
        }

        private String namedTypeName(PirType type) {
            return switch (type) {
                case PirType.RecordType record -> record.name();
                case PirType.SumType sum -> sum.name();
                case PirType.NamedTypeRef ref -> ref.name();
                default -> "";
            };
        }

        private PirType canonicalType(PirType type) {
            return switch (type) {
                case PirType.StringType _ -> new PirType.ByteStringType();
                case PirType.ListType list -> new PirType.ListType(canonicalType(list.elemType()));
                case PirType.MapType map -> new PirType.MapType(
                        canonicalType(map.keyType()), canonicalType(map.valueType()));
                case PirType.OptionalType optional ->
                        new PirType.OptionalType(canonicalType(optional.elemType()));
                case PirType.ArrayType array -> new PirType.ArrayType(canonicalType(array.elemType()));
                case PirType.PairType pair -> new PirType.PairType(
                        canonicalType(pair.first()), canonicalType(pair.second()));
                case PirType.FunType fun -> new PirType.FunType(
                        canonicalType(fun.paramType()), canonicalType(fun.returnType()));
                case PirType.RecordType record -> new PirType.RecordType(
                        record.name(), record.fields().stream()
                                .map(field -> new PirType.Field(
                                        field.name(), canonicalType(field.type())))
                                .toList());
                case PirType.SumType sum -> new PirType.SumType(
                        sum.name(), sum.constructors().stream()
                                .map(constructor -> new PirType.Constructor(
                                        constructor.name(), constructor.tag(),
                                        constructor.fields().stream()
                                                .map(field -> new PirType.Field(
                                                        field.name(), canonicalType(field.type())))
                                                .toList()))
                                .toList());
                case PirType.NamedTypeRef ref -> ref;
                default -> type;
            };
        }

        private String namedTitle(PirType type, String fallback) {
            return switch (type) {
                case PirType.RecordType record -> record.name();
                case PirType.SumType sum -> sum.name();
                case PirType.NamedTypeRef ref -> ref.name();
                default -> fallback;
            };
        }

        private Schema schemaForDefinition(PirType type) {
            return switch (type) {
                case PirType.RecordType record -> namedDefinition(record);
                case PirType.SumType sum -> namedDefinition(sum);
                case PirType.NamedTypeRef ref -> schemaForDefinition(resolveNamed(ref));
                default -> schemaFor(type, null);
            };
        }

        private PirType resolveNamed(PirType type) {
            if (!(type instanceof PirType.NamedTypeRef ref)) return type;
            PirType definition = namedDefinitions.get(ref.stableId());
            if (definition == null) {
                throw new SchemaGenerationException(
                        "Unknown recursive compiler type '" + ref.stableId() + "'");
            }
            return definition;
        }

        private static String jsonPointer(String key) {
            return key.replace("~", "~0").replace("/", "~1");
        }
    }

    /** Raised when a resolved compiler type cannot be represented truthfully. */
    public static class SchemaGenerationException extends IllegalArgumentException {
        public SchemaGenerationException(String message) {
            super(message);
        }

        public SchemaGenerationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
