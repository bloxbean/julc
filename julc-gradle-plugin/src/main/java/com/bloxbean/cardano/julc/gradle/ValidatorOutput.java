package com.bloxbean.cardano.julc.gradle;

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
}
