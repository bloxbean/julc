package com.bloxbean.julc.cli.blueprint;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.PrimitiveType;
import com.github.javaparser.ast.type.Type;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Extracts CIP-57 type schemas from validator Java source.
 * Parses entrypoint parameters, sealed interfaces, and records to build
 * the "datum", "redeemer", "parameters", and "definitions" sections.
 */
public final class SchemaGenerator {

    private SchemaGenerator() {}

    /** Schema reference or inline schema. */
    public record Schema(String title, String ref, String dataType, String description,
                         Integer index, List<FieldSchema> fields, List<Schema> anyOf) {

        static Schema ref(String title, String ref) {
            return new Schema(title, ref, null, null, null, null, null);
        }
        static Schema primitive(String dataType) {
            return new Schema(null, null, dataType, null, null, null, null);
        }
        static Schema data() {
            return new Schema("Data", null, null, "Any Plutus data.", null, null, null);
        }
        static Schema constructor(String title, int index, List<FieldSchema> fields) {
            return new Schema(title, "constructor", null, null, index, fields, null);
        }
        static Schema sum(String title, List<Schema> variants) {
            return new Schema(title, null, null, null, null, null, variants);
        }
    }

    public record FieldSchema(String title, String ref, String dataType) {}

    /** Result of schema extraction for a single validator. */
    public record ValidatorSchema(
            Schema datum,         // null if no datum param (minting, etc.)
            Schema redeemer,
            List<Schema> parameters,  // @Param fields
            Map<String, Schema> definitions
    ) {}

    private static final Pattern ENTRYPOINT_PATTERN = Pattern.compile("@Entrypoint");
    private static final Pattern VALIDATOR_PATTERN = Pattern.compile(
            "@(Validator|SpendingValidator|MintingPolicy|MintingValidator|" +
            "WithdrawValidator|CertifyingValidator|VotingValidator|ProposingValidator|MultiValidator)");

    /**
     * Extract schema from a validator source file.
     */
    public static ValidatorSchema extract(String validatorSource) {
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
        CompilationUnit cu = StaticJavaParser.parse(validatorSource);

        var validatorClass = cu.findAll(ClassOrInterfaceDeclaration.class).stream()
                .filter(c -> !c.isInterface())
                .filter(c -> VALIDATOR_PATTERN.matcher(c.toString()).find())
                .findFirst()
                .orElse(null);
        if (validatorClass == null) return null;

        // Find entrypoint method
        var entrypoint = validatorClass.getMethods().stream()
                .filter(m -> m.getAnnotationByName("Entrypoint").isPresent())
                .findFirst()
                .orElse(null);
        if (entrypoint == null) return null;

        var definitions = new LinkedHashMap<String, Schema>();
        var params = entrypoint.getParameters();
        int paramCount = params.size();

        // Last param is always ScriptContext — skip it
        // For spending: (datum, redeemer, ctx) or (redeemer, ctx) when datum is Optional
        // For minting/others: (redeemer, ctx)

        Schema datum = null;
        Schema redeemer = null;

        if (paramCount == 3) {
            // Spending with datum: (datum, redeemer, ctx)
            var datumParam = params.get(0);
            var redeemerParam = params.get(1);
            datum = buildParamSchema(datumParam, validatorClass, definitions);
            redeemer = buildParamSchema(redeemerParam, validatorClass, definitions);
        } else if (paramCount == 2) {
            // Minting or spending without explicit datum: (redeemer, ctx)
            var redeemerParam = params.get(0);
            redeemer = buildParamSchema(redeemerParam, validatorClass, definitions);
        }

        // Extract @Param fields
        var parameterSchemas = new ArrayList<Schema>();
        for (var field : validatorClass.getFields()) {
            if (field.getAnnotationByName("Param").isPresent()) {
                for (var varDecl : field.getVariables()) {
                    String fieldName = varDecl.getNameAsString();
                    String typeStr = field.getElementType().asString();
                    String defKey = mapTypeToDefinitionKey(typeStr);
                    ensureDefinition(defKey, typeStr, validatorClass, definitions);
                    parameterSchemas.add(Schema.ref(fieldName, "#/definitions/" + defKey));
                }
            }
        }

        return new ValidatorSchema(datum, redeemer, parameterSchemas, definitions);
    }

    private static Schema buildParamSchema(Parameter param, ClassOrInterfaceDeclaration validatorClass,
                                           Map<String, Schema> definitions) {
        String paramName = param.getNameAsString();
        String typeStr = param.getTypeAsString();

        // Handle Optional<T> — extract inner type
        if (typeStr.startsWith("Optional<")) {
            typeStr = typeStr.substring("Optional<".length(), typeStr.length() - 1);
        }

        // Primitive/builtin types
        String defKey = mapTypeToDefinitionKey(typeStr);
        ensureDefinition(defKey, typeStr, validatorClass, definitions);
        return Schema.ref(paramName, "#/definitions/" + defKey);
    }

    private static String mapTypeToDefinitionKey(String javaType) {
        return switch (javaType) {
            case "PlutusData" -> "Data";
            case "BigInteger", "int", "long" -> "Int";
            case "byte[]" -> "ByteArray";
            case "String" -> "ByteArray";
            case "boolean" -> "Bool";
            default -> javaType; // User-defined type: use simple name
        };
    }

    private static void ensureDefinition(String key, String javaType,
                                         ClassOrInterfaceDeclaration validatorClass,
                                         Map<String, Schema> definitions) {
        if (definitions.containsKey(key)) return;

        // Primitives
        switch (key) {
            case "Data" -> { definitions.put(key, Schema.data()); return; }
            case "Int" -> { definitions.put(key, Schema.primitive("integer")); return; }
            case "ByteArray" -> { definitions.put(key, Schema.primitive("bytes")); return; }
            case "Bool" -> { definitions.put(key, Schema.primitive("boolean")); return; }
        }

        // Look for sealed interface (sum type) inside validator class
        var sealedInterface = validatorClass.findAll(ClassOrInterfaceDeclaration.class).stream()
                .filter(c -> c.isInterface() && c.getNameAsString().equals(javaType))
                .findFirst()
                .orElse(null);

        if (sealedInterface != null) {
            // Find all records that implement this interface
            var variants = new ArrayList<Schema>();
            int index = 0;
            for (var member : validatorClass.getMembers()) {
                if (member instanceof RecordDeclaration rd) {
                    boolean implements_ = rd.getImplementedTypes().stream()
                            .anyMatch(t -> t.getNameAsString().equals(javaType));
                    if (implements_) {
                        var fields = new ArrayList<FieldSchema>();
                        for (var p : rd.getParameters()) {
                            String fieldName = p.getNameAsString();
                            String fieldType = p.getTypeAsString();
                            String fieldDefKey = mapTypeToDefinitionKey(fieldType);
                            ensureDefinition(fieldDefKey, fieldType, validatorClass, definitions);
                            fields.add(new FieldSchema(fieldName, "#/definitions/" + fieldDefKey, null));
                        }
                        variants.add(Schema.constructor(rd.getNameAsString(), index, fields));
                        index++;
                    }
                }
            }
            definitions.put(key, Schema.sum(javaType, variants));
            return;
        }

        // Look for a record (single-constructor type) inside validator class
        var record = validatorClass.findAll(RecordDeclaration.class).stream()
                .filter(rd -> rd.getNameAsString().equals(javaType))
                .findFirst()
                .orElse(null);

        if (record != null) {
            var fields = new ArrayList<FieldSchema>();
            for (var p : record.getParameters()) {
                String fieldName = p.getNameAsString();
                String fieldType = p.getTypeAsString();
                String fieldDefKey = mapTypeToDefinitionKey(fieldType);
                ensureDefinition(fieldDefKey, fieldType, validatorClass, definitions);
                fields.add(new FieldSchema(fieldName, "#/definitions/" + fieldDefKey, null));
            }
            var variant = Schema.constructor(javaType, 0, fields);
            definitions.put(key, Schema.sum(javaType, List.of(variant)));
            return;
        }

        // Unknown type — treat as opaque Data
        definitions.put(key, Schema.data());
    }
}
