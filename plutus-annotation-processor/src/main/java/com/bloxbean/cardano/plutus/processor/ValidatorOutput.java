package com.bloxbean.cardano.plutus.processor;

/**
 * Output record for a compiled Plutus validator.
 *
 * @param type        script type, e.g. "PlutusScriptV3" or "PlutusScriptV3-Minting"
 * @param description validator name
 * @param cborHex     double-CBOR-wrapped FLAT-encoded UPLC program
 * @param hash        script hash (28 bytes hex)
 */
public record ValidatorOutput(String type, String description, String cborHex, String hash) {

    /**
     * Serialize to JSON text envelope format.
     */
    public String toJson() {
        return """
                {
                  "type": "%s",
                  "description": "%s",
                  "cborHex": "%s",
                  "hash": "%s"
                }
                """.formatted(type, description, cborHex, hash);
    }

    /**
     * Deserialize from JSON text envelope format.
     * Simple parser — no external JSON dependency needed.
     */
    public static ValidatorOutput fromJson(String json) {
        String type = extractField(json, "type");
        String description = extractField(json, "description");
        String cborHex = extractField(json, "cborHex");
        String hash = extractField(json, "hash");
        return new ValidatorOutput(type, description, cborHex, hash);
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
}
