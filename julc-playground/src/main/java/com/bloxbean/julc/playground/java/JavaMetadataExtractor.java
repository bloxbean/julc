package com.bloxbean.julc.playground.java;

import com.bloxbean.cardano.julc.compiler.error.CompilerDiagnostic;
import com.bloxbean.cardano.julc.compiler.validate.SubsetValidator;
import com.bloxbean.julc.playground.model.*;
import com.github.javaparser.ParseProblemException;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

/**
 * Extracts contract metadata from Java validator source code.
 * <p>
 * Parses annotations, entrypoint parameters, record types, and sealed interfaces
 * to produce the same metadata shape that the JRL check pipeline provides.
 */
public final class JavaMetadataExtractor {

    private static final List<String> VALIDATOR_ANNOTATIONS = List.of(
            "Validator", "SpendingValidator",
            "MintingPolicy", "MintingValidator",
            "WithdrawValidator", "CertifyingValidator",
            "VotingValidator", "ProposingValidator",
            "MultiValidator"
    );

    private static final Map<String, String> ANNOTATION_TO_PURPOSE = Map.of(
            "SpendingValidator", "SPENDING",
            "MintingPolicy", "MINTING",
            "MintingValidator", "MINTING",
            "WithdrawValidator", "WITHDRAW",
            "CertifyingValidator", "CERTIFYING",
            "VotingValidator", "VOTING",
            "ProposingValidator", "PROPOSING",
            "MultiValidator", "MULTI"
    );

    /** Maps Java type names to display names matching the TestPanel's expected types. */
    private static final Map<String, String> TYPE_DISPLAY_NAMES = Map.ofEntries(
            Map.entry("BigInteger", "Integer"),
            Map.entry("java.math.BigInteger", "Integer"),
            Map.entry("byte[]", "ByteString"),
            Map.entry("boolean", "Boolean"),
            Map.entry("Boolean", "Boolean"),
            Map.entry("String", "ByteString"),
            Map.entry("PlutusData", "Data")
    );

    /** Known ledger types that pass through as-is. */
    private static final Set<String> PASSTHROUGH_TYPES = Set.of(
            "PubKeyHash", "ScriptHash", "ValidatorHash", "PolicyId",
            "TokenName", "DatumHash", "TxId", "Credential",
            "Address", "Value", "TxOut", "TxOutRef", "TxInfo",
            "ScriptContext", "ScriptInfo", "Interval", "POSIXTime"
    );

    private JavaMetadataExtractor() {}

    /**
     * Extract contract metadata from Java validator source code.
     * Returns a CheckResponse with the same structure the frontend expects.
     */
    public static CheckResponse extract(String source) {
        StaticJavaParser.getParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);

        CompilationUnit cu;
        try {
            cu = StaticJavaParser.parse(source);
        } catch (ParseProblemException e) {
            var diagnostics = new ArrayList<DiagnosticDto>();
            for (var problem : e.getProblems()) {
                String msg = problem.getMessage();
                // Extract just the first line (before "Problem stacktrace")
                int stackIdx = msg.indexOf("Problem stacktrace");
                if (stackIdx > 0) msg = msg.substring(0, stackIdx).trim();
                var loc = problem.getLocation().orElse(null);
                Integer line = loc != null ? loc.getBegin().getRange().map(r -> r.begin.line).orElse(null) : null;
                Integer col = loc != null ? loc.getBegin().getRange().map(r -> r.begin.column).orElse(null) : null;
                diagnostics.add(new DiagnosticDto("ERROR", null, msg, line, col, null, null, null));
            }
            if (diagnostics.isEmpty()) {
                diagnostics.add(new DiagnosticDto("ERROR", null, "Parse error", null, null, null, null, null));
            }
            return new CheckResponse(false, null, null, List.of(), null, List.of(), List.of(), List.of(), diagnostics);
        } catch (Exception e) {
            return errorResponse("Parse error: " + e.getMessage());
        }

        // Run subset validation
        var subsetValidator = new SubsetValidator();
        var compilerDiags = subsetValidator.validate(cu);
        var diagnostics = new ArrayList<>(compilerDiags.stream().map(DiagnosticDto::from).toList());

        // Find the validator class
        ClassOrInterfaceDeclaration validatorClass = null;
        String annotationName = null;
        for (var type : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            for (var ann : VALIDATOR_ANNOTATIONS) {
                if (type.getAnnotationByName(ann).isPresent()) {
                    validatorClass = type;
                    annotationName = ann;
                    break;
                }
            }
            if (validatorClass != null) break;
        }

        if (validatorClass == null) {
            diagnostics.add(new DiagnosticDto("ERROR", null,
                    "No validator annotation found. Add @Validator, @SpendingValidator, @MintingPolicy, etc.",
                    null, null, null, null, null));
            return new CheckResponse(false, null, null, List.of(), null, List.of(), List.of(), List.of(), diagnostics);
        }

        String contractName = validatorClass.getNameAsString();
        String purpose = determinePurpose(annotationName, validatorClass);

        // Extract @Param fields
        List<FieldDto> params = extractParams(validatorClass);

        // Find @Entrypoint method
        MethodDeclaration entrypoint = findEntrypoint(validatorClass);
        if (entrypoint == null) {
            diagnostics.add(new DiagnosticDto("WARNING", null,
                    "No @Entrypoint method found",
                    null, null, null, null, null));
            boolean hasErrors = diagnostics.stream().anyMatch(d -> "ERROR".equals(d.level()));
            return new CheckResponse(!hasErrors, contractName, purpose, params, null,
                    List.of(), List.of(), List.of(), diagnostics);
        }

        // Extract datum and redeemer metadata from entrypoint params
        String datumName = null;
        List<FieldDto> datumFields = List.of();
        List<VariantDto> redeemerVariants = List.of();
        List<FieldDto> redeemerFields = List.of();

        var entrypointParams = entrypoint.getParameters();
        boolean isSpending = "SPENDING".equals(purpose);

        if (isSpending && entrypointParams.size() >= 3) {
            // 3-param spending: (datum, redeemer, ctx)
            String datumType = entrypointParams.get(0).getTypeAsString();
            datumName = datumType;
            datumFields = extractRecordFields(validatorClass, datumType);

            String redeemerType = entrypointParams.get(1).getTypeAsString();
            var redeemerInfo = extractRedeemerInfo(validatorClass, redeemerType);
            redeemerVariants = redeemerInfo.variants();
            redeemerFields = redeemerInfo.fields();
        } else if (isSpending && entrypointParams.size() == 2) {
            // 2-param spending: (redeemer, ctx)
            String redeemerType = entrypointParams.get(0).getTypeAsString();
            var redeemerInfo = extractRedeemerInfo(validatorClass, redeemerType);
            redeemerVariants = redeemerInfo.variants();
            redeemerFields = redeemerInfo.fields();
        } else if (!isSpending && entrypointParams.size() >= 2) {
            // Non-spending: (redeemer, ctx)
            String redeemerType = entrypointParams.get(0).getTypeAsString();
            var redeemerInfo = extractRedeemerInfo(validatorClass, redeemerType);
            redeemerVariants = redeemerInfo.variants();
            redeemerFields = redeemerInfo.fields();
        }

        boolean hasErrors = diagnostics.stream().anyMatch(d -> "ERROR".equals(d.level()));
        return new CheckResponse(!hasErrors, contractName, purpose, params, datumName,
                datumFields, redeemerVariants, redeemerFields, diagnostics);
    }

    private static String determinePurpose(String annotationName, ClassOrInterfaceDeclaration cls) {
        String mapped = ANNOTATION_TO_PURPOSE.get(annotationName);
        if (mapped != null) return mapped;

        // @Validator — determine from entrypoint param count
        var entrypoint = findEntrypoint(cls);
        if (entrypoint != null && entrypoint.getParameters().size() >= 3) {
            return "SPENDING";
        }
        // Default to SPENDING for @Validator with unknown param count
        return "SPENDING";
    }

    private static List<FieldDto> extractParams(ClassOrInterfaceDeclaration cls) {
        var params = new ArrayList<FieldDto>();
        for (var field : cls.getFields()) {
            if (field.getAnnotationByName("Param").isPresent()) {
                for (var var_ : field.getVariables()) {
                    String type = mapDisplayType(var_.getTypeAsString());
                    params.add(new FieldDto(var_.getNameAsString(), type));
                }
            }
        }
        return params;
    }

    private static MethodDeclaration findEntrypoint(ClassOrInterfaceDeclaration cls) {
        for (var method : cls.getMethods()) {
            if (method.getAnnotationByName("Entrypoint").isPresent()) {
                return method;
            }
        }
        return null;
    }

    /**
     * Extract fields from a record type defined within the validator class.
     */
    private static List<FieldDto> extractRecordFields(ClassOrInterfaceDeclaration cls, String typeName) {
        // Look for record declaration with matching name
        for (var record : cls.findAll(RecordDeclaration.class)) {
            if (record.getNameAsString().equals(typeName)) {
                return record.getParameters().stream()
                        .map(p -> new FieldDto(p.getNameAsString(),
                                mapDisplayType(p.getTypeAsString(), p.getNameAsString())))
                        .toList();
            }
        }
        return List.of();
    }

    /**
     * Extract redeemer info: either variants (from sealed interface) or fields (from record).
     */
    private static RedeemerInfo extractRedeemerInfo(ClassOrInterfaceDeclaration cls, String typeName) {
        // Check for sealed interface first
        for (var inner : cls.findAll(ClassOrInterfaceDeclaration.class)) {
            if (inner.getNameAsString().equals(typeName) && inner.isInterface()) {
                // Sealed interface — extract variant records
                var variants = new ArrayList<VariantDto>();
                int tag = 0;
                for (var member : inner.getMembers()) {
                    if (member instanceof RecordDeclaration record) {
                        var fields = record.getParameters().stream()
                                .map(p -> new FieldDto(p.getNameAsString(),
                                        mapDisplayType(p.getTypeAsString(), p.getNameAsString())))
                                .toList();
                        variants.add(new VariantDto(record.getNameAsString(), tag, fields));
                        tag++;
                    }
                }
                if (!variants.isEmpty()) {
                    return new RedeemerInfo(variants, List.of());
                }
            }
        }

        // Check for record type
        for (var record : cls.findAll(RecordDeclaration.class)) {
            if (record.getNameAsString().equals(typeName)) {
                var fields = record.getParameters().stream()
                        .map(p -> new FieldDto(p.getNameAsString(),
                                mapDisplayType(p.getTypeAsString(), p.getNameAsString())))
                        .toList();
                return new RedeemerInfo(List.of(), fields);
            }
        }

        // Primitive type (BigInteger, PlutusData, etc.) — no structured redeemer
        return new RedeemerInfo(List.of(), List.of());
    }

    static String mapDisplayType(String javaType) {
        return mapDisplayType(javaType, null);
    }

    /**
     * Map Java type to display type. When fieldName is provided and the type is PlutusData,
     * use field name heuristics to suggest a more specific type for the test panel.
     */
    static String mapDisplayType(String javaType, String fieldName) {
        // Check direct mapping
        String mapped = TYPE_DISPLAY_NAMES.get(javaType);
        if (mapped != null) {
            // If mapped to "Data" and we have a field name, try heuristics
            if ("Data".equals(mapped) && fieldName != null) {
                String hinted = hintTypeFromFieldName(fieldName);
                if (hinted != null) return hinted;
            }
            return mapped;
        }

        // Check passthrough types
        if (PASSTHROUGH_TYPES.contains(javaType)) return javaType;

        // Strip Optional<...>
        if (javaType.startsWith("Optional<") && javaType.endsWith(">")) {
            return "Optional<" + mapDisplayType(javaType.substring(9, javaType.length() - 1), fieldName) + ">";
        }

        // Strip List<...>
        if (javaType.startsWith("List<") && javaType.endsWith(">")) {
            return "List<" + mapDisplayType(javaType.substring(5, javaType.length() - 1), null) + ">";
        }

        // Default — return as-is (could be a user-defined record)
        return javaType;
    }

    /**
     * Guess a display type from the field name when the actual type is PlutusData.
     */
    private static String hintTypeFromFieldName(String name) {
        String lower = name.toLowerCase();
        if (lower.contains("pkh") || lower.contains("pubkey") || lower.contains("signer")
                || lower.contains("beneficiary") || lower.contains("owner")) {
            return "PubKeyHash";
        }
        if (lower.contains("time") || lower.contains("deadline") || lower.contains("expir")
                || lower.contains("valid")) {
            return "POSIXTime";
        }
        if (lower.contains("amount") || lower.contains("lovelace") || lower.contains("count")
                || lower.contains("threshold")) {
            return "Integer";
        }
        if (lower.contains("policy") || lower.contains("currency")) {
            return "PolicyId";
        }
        if (lower.contains("token") || lower.contains("asset")) {
            return "TokenName";
        }
        if (lower.contains("hash") || lower.contains("secret")) {
            return "ByteString";
        }
        return null;
    }

    private static CheckResponse errorResponse(String message) {
        return new CheckResponse(false, null, null, List.of(), null, List.of(), List.of(), List.of(),
                List.of(new DiagnosticDto("ERROR", null, message, null, null, null, null, null)));
    }

    private record RedeemerInfo(List<VariantDto> variants, List<FieldDto> fields) {}
}
