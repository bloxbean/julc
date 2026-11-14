package org.julclang.clientlib;

import java.util.ArrayList;
import java.util.List;

/**
 * Output record for a compiled Plutus validator.
 *
 * @param type        script text-envelope type, e.g. "PlutusScriptV3"
 * @param purpose     validator purpose, e.g. "spending" or "minting"; may be empty for legacy metadata
 * @param description validator name
 * @param cborHex     double-CBOR-wrapped FLAT-encoded UPLC program
 * @param hash        script hash (28 bytes hex), empty for parameterized validators
 * @param params      comma-separated param metadata, e.g. "owner:byte[],deadline:BigInteger"
 * @param sizeBytes   FLAT-encoded script size in bytes, or -1 if unknown
 * @param validator   the validator class's binary name, e.g. "com.b.VaultValidator"; empty when unknown, as in
 *                    metadata written before issue #326
 */
public record ValidatorOutput(String type, String purpose, String description, String cborHex,
                              String hash, String params, int sizeBytes, String validator) {

    public ValidatorOutput {
        var typeParts = splitLegacyType(type);
        type = typeParts.type();
        if (purpose == null || purpose.isBlank()) {
            purpose = typeParts.purpose();
        }
        params = params != null ? params : "";
        validator = validator != null ? validator : "";
    }

    /**
     * Constructor without the validator's class name.
     */
    public ValidatorOutput(String type, String purpose, String description, String cborHex,
                           String hash, String params, int sizeBytes) {
        this(type, purpose, description, cborHex, hash, params, sizeBytes, "");
    }

    /**
     * Backward-compatible constructor without purpose.
     */
    public ValidatorOutput(String type, String description, String cborHex,
                           String hash, String params, int sizeBytes) {
        this(type, "", description, cborHex, hash, params, sizeBytes);
    }

    /**
     * Backward-compatible constructor without sizeBytes.
     */
    public ValidatorOutput(String type, String description, String cborHex, String hash, String params) {
        this(type, "", description, cborHex, hash, params, -1);
    }

    /**
     * Constructor with purpose and without sizeBytes.
     */
    public ValidatorOutput(String type, String purpose, String description,
                           String cborHex, String hash, String params) {
        this(type, purpose, description, cborHex, hash, params, -1);
    }

    /**
     * Backward-compatible constructor for non-parameterized validators.
     */
    public ValidatorOutput(String type, String description, String cborHex, String hash) {
        this(type, "", description, cborHex, hash, "", -1);
    }

    /**
     * Whether this validator has parameters that must be applied before deployment.
     */
    public boolean isParameterized() {
        return params != null && !params.isEmpty();
    }

    /**
     * Parse the params string into a list of {@link ParamInfo} records.
     *
     * @return list of parameter metadata, empty if not parameterized
     */
    public List<ParamInfo> paramList() {
        if (!isParameterized()) {
            return List.of();
        }
        var result = new ArrayList<ParamInfo>();
        for (String entry : params.split(",")) {
            String trimmed = entry.trim();
            int colonIdx = trimmed.indexOf(':');
            if (colonIdx > 0) {
                result.add(new ParamInfo(
                        trimmed.substring(0, colonIdx),
                        trimmed.substring(colonIdx + 1)));
            }
        }
        return List.copyOf(result);
    }

    /**
     * Human-readable script size, e.g. "342 B" or "14.2 KB".
     * Returns "unknown" if sizeBytes is -1.
     */
    public String sizeFormatted() {
        if (sizeBytes < 0) return "unknown";
        if (sizeBytes < 1024) return sizeBytes + " B";
        double kb = sizeBytes / 1024.0;
        if (kb < 10) return String.format("%.1f KB", kb);
        return String.format("%.0f KB", kb);
    }

    /**
     * Serialize to JSON text envelope format. The {@code validator} field is written only when it is known, so an
     * output without it serializes as before issue #326.
     */
    public String toJson() {
        String validatorField = validator.isEmpty() ? "" : "\n  \"validator\": \"" + validator + "\",";
        return """
                {
                  "type": "%s",
                  "purpose": "%s",
                  "description": "%s",%s
                  "cborHex": "%s",
                  "hash": "%s",
                  "params": "%s",
                  "sizeBytes": %d
                }
                """.formatted(type, purpose != null ? purpose : "", description, validatorField, cborHex,
                hash, params != null ? params : "", sizeBytes);
    }

    /**
     * Deserialize from JSON text envelope format.
     * Simple parser - no external JSON dependency needed.
     */
    public static ValidatorOutput fromJson(String json) {
        String type = extractField(json, "type");
        String purpose = extractFieldOptional(json, "purpose");
        String description = extractField(json, "description");
        String cborHex = extractField(json, "cborHex");
        String hash = extractField(json, "hash");
        String params = extractFieldOptional(json, "params");
        int sizeBytes = extractIntFieldOptional(json, "sizeBytes", -1);
        String validator = extractFieldOptional(json, "validator");
        return new ValidatorOutput(type, purpose, description, cborHex, hash, params, sizeBytes, validator);
    }

    private static TypeParts splitLegacyType(String type) {
        if (type == null) {
            return new TypeParts("", "");
        }
        return switch (type) {
            case "PlutusScriptV3-Spending" -> new TypeParts("PlutusScriptV3", "spending");
            case "PlutusScriptV3-Minting" -> new TypeParts("PlutusScriptV3", "minting");
            case "PlutusScriptV3-Withdraw" -> new TypeParts("PlutusScriptV3", "withdraw");
            case "PlutusScriptV3-Certifying" -> new TypeParts("PlutusScriptV3", "certifying");
            case "PlutusScriptV3-Voting" -> new TypeParts("PlutusScriptV3", "voting");
            case "PlutusScriptV3-Proposing" -> new TypeParts("PlutusScriptV3", "proposing");
            case "PlutusScriptV3-Multi" -> new TypeParts("PlutusScriptV3", "multi");
            default -> new TypeParts(type, "");
        };
    }

    private record TypeParts(String type, String purpose) {}

    private static String extractField(String json, String field) {
        int colonIdx = colonAfterKey(json, field);
        if (colonIdx < 0) {
            throw new IllegalArgumentException("Missing field: " + field);
        }
        return stringValue(json, colonIdx);
    }

    private static String extractFieldOptional(String json, String field) {
        int colonIdx = colonAfterKey(json, field);
        return colonIdx < 0 ? "" : stringValue(json, colonIdx);
    }

    private static int extractIntFieldOptional(String json, String field, int defaultValue) {
        int colonIdx = colonAfterKey(json, field);
        if (colonIdx < 0) {
            return defaultValue;
        }
        // Skip whitespace after colon, then read digits (possibly with leading minus)
        int start = colonIdx + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        int end = start;
        if (end < json.length() && json.charAt(end) == '-') end++;
        while (end < json.length() && Character.isDigit(json.charAt(end))) end++;
        try {
            return Integer.parseInt(json.substring(start, end));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * The index of the colon after {@code "field"} used as a key, or -1. A string value that spells the key, such as
     * {@code "description": "validator"}, is followed by a comma rather than a colon, so it is skipped.
     */
    private static int colonAfterKey(String json, String field) {
        String key = "\"" + field + "\"";
        for (int keyIdx = json.indexOf(key); keyIdx >= 0; keyIdx = json.indexOf(key, keyIdx + 1)) {
            int i = keyIdx + key.length();
            while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
            if (i < json.length() && json.charAt(i) == ':') {
                return i;
            }
        }
        return -1;
    }

    private static String stringValue(String json, int colonIdx) {
        int quoteStart = json.indexOf('"', colonIdx + 1);
        int quoteEnd = json.indexOf('"', quoteStart + 1);
        return json.substring(quoteStart + 1, quoteEnd);
    }

    /**
     * Metadata about a contract parameter declared with {@code @Param}.
     *
     * @param name the parameter field name
     * @param type the Java type name (e.g. "byte[]", "BigInteger")
     */
    public record ParamInfo(String name, String type) {}
}
