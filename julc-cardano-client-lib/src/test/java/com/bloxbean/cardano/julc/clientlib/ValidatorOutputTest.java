package com.bloxbean.cardano.julc.clientlib;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ValidatorOutputTest {

    @Test
    void toJsonRoundTripsTypeAndPurpose() {
        var original = new ValidatorOutput("PlutusScriptV3", "minting",
                "Policy", "8201", "aabb", "", 42);

        var parsed = ValidatorOutput.fromJson(original.toJson());

        assertEquals("PlutusScriptV3", parsed.type());
        assertEquals("minting", parsed.purpose());
        assertEquals("Policy", parsed.description());
        assertEquals("8201", parsed.cborHex());
        assertEquals("aabb", parsed.hash());
        assertEquals(42, parsed.sizeBytes());
    }

    @Test
    void fromJsonParsesLegacyPurposeSuffixedType() {
        String json = """
                {
                  "type": "PlutusScriptV3-Minting",
                  "description": "LegacyPolicy",
                  "cborHex": "8201",
                  "hash": "aabb",
                  "params": "",
                  "sizeBytes": 99
                }
                """;

        var parsed = ValidatorOutput.fromJson(json);

        assertEquals("PlutusScriptV3", parsed.type());
        assertEquals("minting", parsed.purpose());
        assertEquals("LegacyPolicy", parsed.description());
        assertEquals(99, parsed.sizeBytes());
    }

    @Test
    void fromJsonKeepsLegacyPlainTypeWithoutGuessingPurpose() {
        String json = """
                {
                  "type": "PlutusScriptV3",
                  "description": "LegacyValidator",
                  "cborHex": "8201",
                  "hash": "aabb"
                }
                """;

        var parsed = ValidatorOutput.fromJson(json);

        assertEquals("PlutusScriptV3", parsed.type());
        assertEquals("", parsed.purpose());
        assertEquals("", parsed.params());
        assertEquals(-1, parsed.sizeBytes());
    }

    @Test
    void oldConstructorsNormalizeLegacyPurposeSuffixedType() {
        var output = new ValidatorOutput("PlutusScriptV3-Withdraw",
                "LegacyWithdraw", "8201", "aabb");

        assertEquals("PlutusScriptV3", output.type());
        assertEquals("withdraw", output.purpose());
    }
}
