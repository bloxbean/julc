package com.bloxbean.cardano.julc.gradle;

/**
 * Output record for a compiled Plutus validator.
 *
 * @param type        script text-envelope type, e.g. "PlutusScriptV3"
 * @param purpose     validator purpose, e.g. "spending" or "minting"
 * @param description validator name
 * @param cborHex     double-CBOR-wrapped FLAT-encoded UPLC program
 * @param hash        script hash (28 bytes hex)
 * @param sizeBytes   FLAT-encoded script size in bytes, or -1 if unknown
 */
public record ValidatorOutput(String type, String purpose, String description,
                              String cborHex, String hash, int sizeBytes) {

    /**
     * Backward-compatible constructor without purpose.
     */
    public ValidatorOutput(String type, String description, String cborHex,
                           String hash, int sizeBytes) {
        this(type, "", description, cborHex, hash, sizeBytes);
    }

    /**
     * Backward-compatible constructor without purpose or sizeBytes.
     */
    public ValidatorOutput(String type, String description, String cborHex, String hash) {
        this(type, "", description, cborHex, hash, -1);
    }

    /**
     * Constructor with purpose and without sizeBytes.
     */
    public ValidatorOutput(String type, String purpose, String description,
                           String cborHex, String hash) {
        this(type, purpose, description, cborHex, hash, -1);
    }

    /**
     * Serialize to JSON text envelope format.
     */
    public String toJson() {
        return """
                {
                  "type": "%s",
                  "purpose": "%s",
                  "description": "%s",
                  "cborHex": "%s",
                  "hash": "%s",
                  "sizeBytes": %d
                }
                """.formatted(type, purpose != null ? purpose : "", description, cborHex, hash, sizeBytes);
    }
}
