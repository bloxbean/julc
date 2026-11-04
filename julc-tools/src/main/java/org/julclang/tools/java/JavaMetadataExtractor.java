package org.julclang.tools.java;

import org.julclang.compiler.JavaSourceIntrospector;
import org.julclang.compiler.JulcCompiler;
import org.julclang.tools.model.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Extracts contract metadata from Java validator source code.
 * <p>
 * Parses annotations, entrypoint parameters, record types, and sealed interfaces
 * to produce the contract metadata the playground's check returns.
 */
public final class JavaMetadataExtractor {

    private static final List<String> VALIDATOR_ANNOTATIONS =
            JavaSourceIntrospector.SUPPORTED_VALIDATOR_ANNOTATIONS;

    private static final Map<String, String> ANNOTATION_TO_PURPOSE = Map.of(
            "SpendingValidator", "SPENDING",
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
        return extract(source, new JulcCompiler(), List.of());
    }

    /**
     * {@link #extract(String)} for a source {@code compiler} compiles with {@code librarySources}: the subset is checked as
     * that compilation checks it ({@link JulcCompiler#subsetDiagnostics}), with its libraries ranked as it ranks them.
     */
    public static CheckResponse extract(String source, JulcCompiler compiler, List<String> librarySources) {
        return JavacMetadataReading.extract(source, VALIDATOR_ANNOTATIONS, compiler, librarySources);
    }

    /** The purpose a validator annotation names; every supported annotation names one. */
    static String purposeOf(String annotationName) {
        String mapped = ANNOTATION_TO_PURPOSE.get(annotationName);
        return mapped != null ? mapped : "SPENDING";
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
}
