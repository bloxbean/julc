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
 */
public record ValidatorOutput(String type, String description, String cborHex, String hash, String params) {

    /**
     * Backward-compatible constructor for non-parameterized validators.
     */
    public ValidatorOutput(String type, String description, String cborHex, String hash) {
        this(type, description, cborHex, hash, "");
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
     * Serialize to JSON text envelope format.
     */
    public String toJson() {
        return """
                {
                  "type": "%s",
                  "description": "%s",
                  "cborHex": "%s",
                  "hash": "%s",
                  "params": "%s"
                }
                """.formatted(type, description, cborHex, hash, params != null ? params : "");
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
        return new ValidatorOutput(type, description, cborHex, hash, params);
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

    /**
     * Metadata about a contract parameter declared with {@code @Param}.
     *
     * @param name the parameter field name
     * @param type the Java type name (e.g. "byte[]", "BigInteger")
     */
    public record ParamInfo(String name, String type) {}
}
