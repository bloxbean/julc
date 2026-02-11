package com.bloxbean.cardano.julc.clientlib;

import java.util.ArrayList;
import java.util.List;

/**
 * Output record for a compiled Plutus validator.
 *
 * @param type        script type, e.g. "PlutusScriptV3" or "PlutusScriptV3-Minting"
 * @param description validator name
 * @param cborHex     double-CBOR-wrapped FLAT-encoded UPLC program
 * @param hash        script hash (28 bytes hex), empty for parameterized validators
 * @param params      comma-separated param metadata, e.g. "owner:byte[],deadline:BigInteger"
 * @param sizeBytes   FLAT-encoded script size in bytes, or -1 if unknown
 */
public record ValidatorOutput(String type, String description, String cborHex, String hash, String params, int sizeBytes) {

    /**
     * Backward-compatible constructor without sizeBytes.
     */
    public ValidatorOutput(String type, String description, String cborHex, String hash, String params) {
        this(type, description, cborHex, hash, params, -1);
    }

    /**
     * Backward-compatible constructor for non-parameterized validators.
     */
    public ValidatorOutput(String type, String description, String cborHex, String hash) {
        this(type, description, cborHex, hash, "", -1);
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
     * Serialize to JSON text envelope format.
     */
    public String toJson() {
        return """
                {
                  "type": "%s",
                  "description": "%s",
                  "cborHex": "%s",
                  "hash": "%s",
                  "params": "%s",
                  "sizeBytes": %d
                }
                """.formatted(type, description, cborHex, hash, params != null ? params : "", sizeBytes);
    }

    /**
     * Deserialize from JSON text envelope format.
     * Simple parser - no external JSON dependency needed.
     */
    public static ValidatorOutput fromJson(String json) {
        String type = extractField(json, "type");
        String description = extractField(json, "description");
        String cborHex = extractField(json, "cborHex");
        String hash = extractField(json, "hash");
        String params = extractFieldOptional(json, "params");
        int sizeBytes = extractIntFieldOptional(json, "sizeBytes", -1);
        return new ValidatorOutput(type, description, cborHex, hash, params, sizeBytes);
    }

    private static String extractField(String json, String field) {
        String key = "\"" + field + "\"";
        int keyIdx = json.indexOf(key);
        if (keyIdx < 0) {
            throw new IllegalArgumentException("Missing field: " + field);
        }
        int colonIdx = json.indexOf(':', keyIdx + key.length());
        int quoteStart = json.indexOf('"', colonIdx + 1);
        int quoteEnd = json.indexOf('"', quoteStart + 1);
        return json.substring(quoteStart + 1, quoteEnd);
    }

    private static String extractFieldOptional(String json, String field) {
        String key = "\"" + field + "\"";
        int keyIdx = json.indexOf(key);
        if (keyIdx < 0) {
            return "";
        }
        int colonIdx = json.indexOf(':', keyIdx + key.length());
        int quoteStart = json.indexOf('"', colonIdx + 1);
        int quoteEnd = json.indexOf('"', quoteStart + 1);
        return json.substring(quoteStart + 1, quoteEnd);
    }

    private static int extractIntFieldOptional(String json, String field, int defaultValue) {
        String key = "\"" + field + "\"";
        int keyIdx = json.indexOf(key);
        if (keyIdx < 0) {
            return defaultValue;
        }
        int colonIdx = json.indexOf(':', keyIdx + key.length());
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
     * Metadata about a contract parameter declared with {@code @Param}.
     *
     * @param name the parameter field name
     * @param type the Java type name (e.g. "byte[]", "BigInteger")
     */
    public record ParamInfo(String name, String type) {}
}
