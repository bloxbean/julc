package com.bloxbean.cardano.julc.blueprint;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.InputFormat;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.stream.Collectors;

/** Offline validation against the repository-pinned official CIP-57 meta-schema. */
public final class BlueprintValidator {

    public static final String META_SCHEMA_ID =
            "https://cips.cardano.org/cips/cip57/schemas/plutus-blueprint.json";
    public static final String CIP_REVISION =
            "0ed8837a02ed78b64847e5646f9572ee1830c7ba";

    private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(
            SpecificationVersion.DRAFT_2020_12,
            builder -> builder.schemaIdResolvers(resolvers -> resolvers.mapPrefix(
                    "https://cips.cardano.org/cips/cip57/schemas",
                    "classpath:cip57")));
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> SCHEMA_PROPERTIES = Set.of(
            "title", "description", "$ref", "dataType", "index", "fields",
            "anyOf", "items", "keys", "values");

    private BlueprintValidator() {}

    /** Validate a serialized blueprint without network access. */
    public static void validate(String blueprintJson) {
        var schema = REGISTRY.getSchema(SchemaLocation.of(META_SCHEMA_ID));
        var errors = schema.validate(blueprintJson, InputFormat.JSON);
        if (!errors.isEmpty()) {
            String detail = errors.stream()
                    .map(Object::toString)
                    .sorted()
                    .collect(Collectors.joining("; "));
            throw new IllegalArgumentException(
                    "Generated blueprint does not satisfy pinned CIP-57 meta-schema: " + detail);
        }
        validateSchemaBodies(blueprintJson);
    }

    private static void validateSchemaBodies(String blueprintJson) {
        final JsonNode root;
        try {
            root = JSON.readTree(blueprintJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("Generated blueprint is not valid JSON", e);
        }
        JsonNode definitions = root.path("definitions");
        if (!definitions.isMissingNode() && !definitions.isObject()) {
            throw new IllegalArgumentException("Blueprint definitions must be an object");
        }
        Set<String> definitionNames = new HashSet<>();
        if (definitions.isObject()) {
            definitions.fieldNames().forEachRemaining(definitionNames::add);
            definitions.fields().forEachRemaining(entry ->
                    validateSchema(entry.getValue(), "definitions." + entry.getKey(), definitionNames));
        }
        JsonNode validators = root.path("validators");
        if (validators.isArray()) {
            for (int i = 0; i < validators.size(); i++) {
                JsonNode validator = validators.get(i);
                validateArgument(validator.path("datum"), "validators[" + i + "].datum", definitionNames);
                validateArgument(validator.path("redeemer"), "validators[" + i + "].redeemer", definitionNames);
                JsonNode parameters = validator.path("parameters");
                if (parameters.isArray()) {
                    for (int j = 0; j < parameters.size(); j++) {
                        validateArgument(parameters.get(j),
                                "validators[" + i + "].parameters[" + j + "]", definitionNames);
                    }
                }
            }
        }
    }

    private static void validateArgument(
            JsonNode argument, String path, Set<String> definitionNames) {
        if (argument.isMissingNode()) return;
        if (!argument.isObject() || !argument.has("schema")) {
            throw schemaError(path, "must contain a schema object");
        }
        validateSchema(argument.path("schema"), path + ".schema", definitionNames);
    }

    private static void validateSchema(
            JsonNode schema, String path, Set<String> definitionNames) {
        if (!schema.isObject()) {
            throw schemaError(path, "must be an object");
        }
        Iterator<String> properties = schema.fieldNames();
        while (properties.hasNext()) {
            String property = properties.next();
            if (!SCHEMA_PROPERTIES.contains(property)) {
                throw schemaError(path, "contains unsupported property '" + property + "'");
            }
        }

        int forms = (schema.has("$ref") ? 1 : 0)
                + (schema.has("dataType") ? 1 : 0)
                + (schema.has("anyOf") ? 1 : 0);
        if (forms == 0) {
            // An annotation-only schema is JuLC's explicit opaque PlutusData form.
            if (!schema.has("description")) {
                throw schemaError(path, "has no Plutus Data form");
            }
            return;
        }
        if (forms != 1) {
            throw schemaError(path, "must select exactly one of $ref, dataType, or anyOf");
        }

        if (schema.has("$ref")) {
            String ref = schema.path("$ref").asText();
            String prefix = "#/definitions/";
            if (!ref.startsWith(prefix) || ref.length() == prefix.length()) {
                throw schemaError(path, "contains unsupported reference '" + ref + "'");
            }
            String name = unescapeJsonPointer(ref.substring(prefix.length()));
            if (!definitionNames.contains(name)) {
                throw schemaError(path, "references missing definition '" + name + "'");
            }
            return;
        }

        if (schema.has("anyOf")) {
            JsonNode alternatives = schema.path("anyOf");
            if (!alternatives.isArray() || alternatives.isEmpty()) {
                throw schemaError(path, "anyOf must be a non-empty array");
            }
            for (int i = 0; i < alternatives.size(); i++) {
                validateSchema(alternatives.get(i), path + ".anyOf[" + i + "]", definitionNames);
            }
            return;
        }

        String dataType = schema.path("dataType").asText();
        switch (dataType) {
            case "integer", "bytes" -> { }
            case "list" -> validateRequiredSchema(schema, "items", path, definitionNames);
            case "map" -> {
                validateRequiredSchema(schema, "keys", path, definitionNames);
                validateRequiredSchema(schema, "values", path, definitionNames);
            }
            case "constructor" -> {
                if (!schema.path("index").canConvertToInt() || schema.path("index").asInt() < 0) {
                    throw schemaError(path, "constructor index must be a nonnegative integer");
                }
                JsonNode fields = schema.path("fields");
                if (!fields.isArray()) {
                    throw schemaError(path, "constructor fields must be an array");
                }
                for (int i = 0; i < fields.size(); i++) {
                    validateSchema(fields.get(i), path + ".fields[" + i + "]", definitionNames);
                }
            }
            default -> throw schemaError(path, "contains unsupported dataType '" + dataType + "'");
        }
    }

    private static void validateRequiredSchema(
            JsonNode parent, String property, String path, Set<String> definitionNames) {
        if (!parent.has(property)) {
            throw schemaError(path, "requires '" + property + "'");
        }
        validateSchema(parent.path(property), path + "." + property, definitionNames);
    }

    private static String unescapeJsonPointer(String value) {
        return value.replace("~1", "/").replace("~0", "~");
    }

    private static IllegalArgumentException schemaError(String path, String detail) {
        return new IllegalArgumentException("Invalid JuLC CIP-57 schema at " + path + ": " + detail);
    }
}
