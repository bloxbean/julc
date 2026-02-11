package com.bloxbean.cardano.julc.gradle;

/**
 * Output record for a compiled Plutus validator.
 *
 * @param type        script type, e.g. "PlutusScriptV3" or "PlutusScriptV3-Minting"
 * @param description validator name
 * @param cborHex     double-CBOR-wrapped FLAT-encoded UPLC program
 * @param hash        script hash (28 bytes hex)
 * @param sizeBytes   FLAT-encoded script size in bytes, or -1 if unknown
 */
public record ValidatorOutput(String type, String description, String cborHex, String hash, int sizeBytes) {

    /**
     * Backward-compatible constructor without sizeBytes.
     */
    public ValidatorOutput(String type, String description, String cborHex, String hash) {
        this(type, description, cborHex, hash, -1);
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
                  "sizeBytes": %d
                }
                """.formatted(type, description, cborHex, hash, sizeBytes);
    }
}
