package org.julclang.clientlib;

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

    @Test
    void validatorRoundTrips() {
        var original = new ValidatorOutput("PlutusScriptV3", "spending",
                "VaultValidator", "8201", "aabb", "", 42, "com.b.Outer$VaultValidator");

        var parsed = ValidatorOutput.fromJson(original.toJson());

        assertEquals("com.b.Outer$VaultValidator", parsed.validator());
        assertEquals("VaultValidator", parsed.description());
        assertEquals("8201", parsed.cborHex());
        assertEquals("aabb", parsed.hash());
        assertEquals(42, parsed.sizeBytes());
    }

    /** Metadata without the class name serializes exactly as before issue #326. */
    @Test
    void outputWithoutValidatorSerializesAsBefore() {
        var output = new ValidatorOutput("PlutusScriptV3", "minting", "Policy", "8201", "aabb", "a:byte[]", 42);

        assertEquals("", output.validator());
        assertEquals("""
                {
                  "type": "PlutusScriptV3",
                  "purpose": "minting",
                  "description": "Policy",
                  "cborHex": "8201",
                  "hash": "aabb",
                  "params": "a:byte[]",
                  "sizeBytes": 42
                }
                """, output.toJson());
        assertEquals(output, ValidatorOutput.fromJson(output.toJson()));
    }

    @Test
    void validatorIsWrittenAfterTheDescription() {
        var output = new ValidatorOutput("PlutusScriptV3", "spending", "VaultValidator", "8201", "aabb", "", 7,
                "com.b.VaultValidator");

        assertEquals("""
                {
                  "type": "PlutusScriptV3",
                  "purpose": "spending",
                  "description": "VaultValidator",
                  "validator": "com.b.VaultValidator",
                  "cborHex": "8201",
                  "hash": "aabb",
                  "params": "",
                  "sizeBytes": 7
                }
                """, output.toJson());
    }

    @Test
    void legacyJsonHasNoValidator() {
        var parsed = ValidatorOutput.fromJson("""
                {
                  "type": "PlutusScriptV3",
                  "description": "LegacyValidator",
                  "cborHex": "8201",
                  "hash": "aabb"
                }
                """);

        assertEquals("", parsed.validator());
    }

    @Test
    void nullValidatorIsEmpty() {
        var output = new ValidatorOutput("PlutusScriptV3", "spending", "V", "8201", "aabb", "", 1, null);

        assertEquals("", output.validator());
        assertFalse(output.toJson().contains("validator"));
    }

    /** A value that spells a key is not that key: older JSON for a class named {@code validator} records no class. */
    @Test
    void aValueSpellingAKeyIsNotThatKey() {
        var parsed = ValidatorOutput.fromJson("""
                {
                  "type": "PlutusScriptV3",
                  "purpose": "spending",
                  "description": "validator",
                  "cborHex": "8201",
                  "hash": "aabb",
                  "params": "",
                  "sizeBytes": 3
                }
                """);

        assertEquals("", parsed.validator());
        assertEquals("validator", parsed.description());
        assertEquals("8201", parsed.cborHex());

        var recorded = new ValidatorOutput("PlutusScriptV3", "spending", "validator", "8201", "aabb", "", 3,
                "com.b.validator");
        assertEquals("com.b.validator", ValidatorOutput.fromJson(recorded.toJson()).validator());
    }
}
