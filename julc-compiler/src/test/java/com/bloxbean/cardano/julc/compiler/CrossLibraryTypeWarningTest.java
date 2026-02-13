package com.bloxbean.cardano.julc.compiler;

import com.bloxbean.cardano.julc.compiler.error.CompilerDiagnostic;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that cross-library calls with typed Data arg mismatches produce warnings.
 */
class CrossLibraryTypeWarningTest {

    @Test
    void testByteStringToDataWarns() {
        var libSource = """
                @OnchainLibrary
                class DataLib {
                    static boolean check(PlutusData x) {
                        return true;
                    }
                }
                """;
        // Use @Param byte[] to get a ByteString-typed variable
        var validatorSource = """
                @Validator
                class TestValidator {
                    @Param byte[] bs;

                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        return DataLib.check(bs);
                    }
                }
                """;
        var result = new JulcCompiler().compile(validatorSource, List.of(libSource));
        assertNotNull(result.program(), "Should still compile successfully");
        var warnings = result.diagnostics().stream()
                .filter(d -> d.level() == CompilerDiagnostic.Level.WARNING)
                .toList();
        assertFalse(warnings.isEmpty(), "Should produce a warning for ByteString -> Data mismatch");
    }

    @Test
    void testIntegerToDataWarns() {
        var libSource = """
                @OnchainLibrary
                class DataLib {
                    static boolean check(PlutusData x) {
                        return true;
                    }
                }
                """;
        var validatorSource = """
                import java.math.BigInteger;

                @Validator
                class TestValidator {
                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        long n = 42;
                        return DataLib.check(n);
                    }
                }
                """;
        var result = new JulcCompiler().compile(validatorSource, List.of(libSource));
        assertNotNull(result.program(), "Should still compile successfully");
        var warnings = result.diagnostics().stream()
                .filter(d -> d.level() == CompilerDiagnostic.Level.WARNING)
                .toList();
        assertFalse(warnings.isEmpty(), "Should produce a warning for Integer -> Data mismatch");
    }

    @Test
    void testNoWarningWhenTypesMatch() {
        var libSource = """
                @OnchainLibrary
                class DataLib {
                    static boolean check(PlutusData x) {
                        return true;
                    }
                }
                """;
        var validatorSource = """
                @Validator
                class TestValidator {
                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        return DataLib.check(redeemer);
                    }
                }
                """;
        var result = new JulcCompiler().compile(validatorSource, List.of(libSource));
        assertNotNull(result.program());
        var warnings = result.diagnostics().stream()
                .filter(d -> d.level() == CompilerDiagnostic.Level.WARNING)
                .toList();
        assertTrue(warnings.isEmpty(), "Should not produce warnings when types match");
    }

    @Test
    void testNoWarningForNonLibrary() {
        // A validator with no library calls should not produce warnings
        var source = """
                @Validator
                class TestValidator {
                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        return true;
                    }
                }
                """;
        var result = new JulcCompiler().compile(source);
        assertNotNull(result.program());
        var warnings = result.diagnostics().stream()
                .filter(d -> d.level() == CompilerDiagnostic.Level.WARNING)
                .toList();
        assertTrue(warnings.isEmpty(), "Should not produce warnings without library calls");
    }

    @Test
    void testWarningDoesNotBlockCompilation() {
        var libSource = """
                @OnchainLibrary
                class DataLib {
                    static boolean check(PlutusData x) {
                        return true;
                    }
                }
                """;
        var validatorSource = """
                @Validator
                class TestValidator {
                    @Param byte[] bs;

                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        return DataLib.check(bs);
                    }
                }
                """;
        var result = new JulcCompiler().compile(validatorSource, List.of(libSource));
        assertNotNull(result.program(), "Warning should not block compilation — program should be non-null");
        assertFalse(result.hasErrors(), "Warnings should not count as errors");
    }

    @Test
    void testWarningHasSuggestion() {
        var libSource = """
                @OnchainLibrary
                class DataLib {
                    static boolean check(PlutusData x) {
                        return true;
                    }
                }
                """;
        var validatorSource = """
                @Validator
                class TestValidator {
                    @Param byte[] bs;

                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        return DataLib.check(bs);
                    }
                }
                """;
        var result = new JulcCompiler().compile(validatorSource, List.of(libSource));
        var warnings = result.diagnostics().stream()
                .filter(d -> d.level() == CompilerDiagnostic.Level.WARNING)
                .toList();
        assertFalse(warnings.isEmpty());
        var warning = warnings.get(0);
        assertTrue(warning.hasSuggestion(), "Warning should have a suggestion");
        assertTrue(warning.suggestion().contains("PlutusData"),
                "Suggestion should mention PlutusData");
    }

    @Test
    void testMultiArgOnlyBadOnesWarn() {
        var libSource = """
                @OnchainLibrary
                class DataLib {
                    static boolean check(PlutusData a, PlutusData b) {
                        return true;
                    }
                }
                """;
        var validatorSource = """
                @Validator
                class TestValidator {
                    @Param byte[] bs;

                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        return DataLib.check(redeemer, bs);
                    }
                }
                """;
        var result = new JulcCompiler().compile(validatorSource, List.of(libSource));
        assertNotNull(result.program());
        var warnings = result.diagnostics().stream()
                .filter(d -> d.level() == CompilerDiagnostic.Level.WARNING)
                .toList();
        // Only arg 2 (bs: ByteString) should produce a warning; arg 1 (redeemer: Data) is fine
        assertEquals(1, warnings.size(), "Only the mismatched arg should produce a warning");
        assertTrue(warnings.get(0).message().contains("Argument 2"),
                "Warning should be about argument 2");
    }
}
