package com.bloxbean.cardano.julc.compiler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PIR/UPLC inspection via compileWithDetails().
 */
class CompilationInspectionTest {

    private static final String SIMPLE_VALIDATOR = """
            @Validator
            class SimpleValidator {
                @Entrypoint
                static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                    return true;
                }
            }
            """;

    @Test
    void compileWithDetailsReturnsNonNullPir() {
        var result = new JulcCompiler().compileWithDetails(SIMPLE_VALIDATOR);
        assertNotNull(result.pirTerm(), "compileWithDetails should populate pirTerm");
    }

    @Test
    void compileWithDetailsReturnsNonNullUplc() {
        var result = new JulcCompiler().compileWithDetails(SIMPLE_VALIDATOR);
        assertNotNull(result.uplcTerm(), "compileWithDetails should populate uplcTerm");
    }

    @Test
    void compileWithDetailsReturnsValidProgram() {
        var result = new JulcCompiler().compileWithDetails(SIMPLE_VALIDATOR);
        assertNotNull(result.program());
        assertFalse(result.hasErrors());
    }

    @Test
    void pirFormattedContainsExpectedConstructs() {
        var result = new JulcCompiler().compileWithDetails(SIMPLE_VALIDATOR);
        var pirText = result.pirFormatted();
        assertNotNull(pirText);
        assertTrue(pirText.contains("lam"), "PIR should contain lambda for validator wrapper");
    }

    @Test
    void pirPrettyContainsIndentation() {
        var result = new JulcCompiler().compileWithDetails(SIMPLE_VALIDATOR);
        var pirPretty = result.pirPretty();
        assertNotNull(pirPretty);
        assertTrue(pirPretty.contains("\n"), "Pretty PIR should have newlines");
    }

    @Test
    void uplcFormattedMatchesUplcPrinter() {
        var result = new JulcCompiler().compileWithDetails(SIMPLE_VALIDATOR);
        var uplcText = result.uplcFormatted();
        assertNotNull(uplcText);
        // UplcPrinter output should start with (program ...)
        assertTrue(uplcText.startsWith("(program"), "UPLC should start with (program ...)");
    }

    @Test
    void standardCompileReturnsNullPir() {
        var result = new JulcCompiler().compile(SIMPLE_VALIDATOR);
        assertNull(result.pirTerm(), "Standard compile should not capture PIR");
    }

    @Test
    void standardCompileReturnsNullUplc() {
        var result = new JulcCompiler().compile(SIMPLE_VALIDATOR);
        assertNull(result.uplcTerm(), "Standard compile should not capture UPLC term");
    }

    @Test
    void pirFormattedIsNullForStandardCompile() {
        var result = new JulcCompiler().compile(SIMPLE_VALIDATOR);
        assertNull(result.pirFormatted());
    }

    @Test
    void uplcFormattedWorksForBothModes() {
        // uplcFormatted() uses program(), which is always populated
        var standard = new JulcCompiler().compile(SIMPLE_VALIDATOR);
        var detailed = new JulcCompiler().compileWithDetails(SIMPLE_VALIDATOR);
        assertNotNull(standard.uplcFormatted());
        assertNotNull(detailed.uplcFormatted());
        // Both should produce the same UPLC output
        assertEquals(standard.uplcFormatted(), detailed.uplcFormatted());
    }

    @Test
    void compileWithDetailsWorksWithHelperMethods() {
        var source = """
                import java.math.BigInteger;

                @Validator
                class HelperValidator {
                    static boolean isPositive(long x) {
                        return x > 0;
                    }

                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        return isPositive(42);
                    }
                }
                """;
        var result = new JulcCompiler().compileWithDetails(source);
        assertNotNull(result.pirTerm());
        assertNotNull(result.uplcTerm());
        assertNotNull(result.program());

        var pirText = result.pirFormatted();
        assertTrue(pirText.contains("isPositive"), "PIR should reference helper method");
    }
}
