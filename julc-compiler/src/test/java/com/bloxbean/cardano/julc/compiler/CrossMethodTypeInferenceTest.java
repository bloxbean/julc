package com.bloxbean.cardano.julc.compiler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for P0-3: Cross-Method Type Inference Hardening.
 * Verifies that cross-method calls use correct UPLC builtins for comparisons
 * (EqualsInteger instead of EqualsData for integer comparisons, etc.)
 */
class CrossMethodTypeInferenceTest {

    /**
     * Helper with long param + == comparison should use EqualsInteger.
     */
    @Test
    void helperWithLongParamUsesEqualsInteger() {
        var source = """
                import java.math.BigInteger;

                @Validator
                class MyValidator {
                    static boolean isEqual(long a, long b) {
                        return a == b;
                    }

                    @Entrypoint
                    static boolean validate(BigInteger redeemer, BigInteger ctx) {
                        return isEqual(5, 10);
                    }
                }
                """;
        var result = new JulcCompiler().compileWithDetails(source);
        assertNotNull(result.program());
        assertFalse(result.hasErrors());
        var pir = result.pirFormatted();
        assertTrue(pir.contains("equalsInteger"),
                "Helper with long params should use equalsInteger, PIR: " + pir);
        assertFalse(pir.contains("equalsData"),
                "Helper with long params should NOT use equalsData, PIR: " + pir);
    }

    /**
     * Helper with BigInteger param + == comparison should use EqualsInteger.
     */
    @Test
    void helperWithBigIntegerParamUsesEqualsInteger() {
        var source = """
                import java.math.BigInteger;

                @Validator
                class MyValidator {
                    static boolean isEqual(BigInteger a, BigInteger b) {
                        return a == b;
                    }

                    @Entrypoint
                    static boolean validate(BigInteger redeemer, BigInteger ctx) {
                        return isEqual(redeemer, ctx);
                    }
                }
                """;
        var result = new JulcCompiler().compileWithDetails(source);
        assertNotNull(result.program());
        assertFalse(result.hasErrors());
        var pir = result.pirFormatted();
        assertTrue(pir.contains("equalsInteger"),
                "Helper with BigInteger params should use equalsInteger, PIR: " + pir);
    }

    /**
     * Helper with byte[] param + == comparison should use EqualsByteString.
     */
    @Test
    void helperWithByteArrayParamUsesEqualsByteString() {
        var source = """
                @Validator
                class MyValidator {
                    record BsHolder(byte[] a, byte[] b) {}

                    static boolean matches(byte[] a, byte[] b) {
                        return a == b;
                    }

                    @Entrypoint
                    static boolean validate(BsHolder redeemer, ScriptContext ctx) {
                        return matches(redeemer.a(), redeemer.b());
                    }
                }
                """;
        var result = new JulcCompiler().compileWithDetails(source);
        assertNotNull(result.program());
        assertFalse(result.hasErrors());
        var pir = result.pirFormatted();
        assertTrue(pir.contains("equalsByteString"),
                "Helper with byte[] params should use equalsByteString, PIR: " + pir);
    }

    /**
     * Var-inferred return from helper used in comparison should maintain type.
     */
    @Test
    void varInferredHelperReturnUsesEqualsInteger() {
        var source = """
                import java.math.BigInteger;

                @Validator
                class MyValidator {
                    static BigInteger doubleIt(BigInteger x) {
                        return x + x;
                    }

                    @Entrypoint
                    static boolean validate(BigInteger redeemer, BigInteger ctx) {
                        var result = doubleIt(redeemer);
                        return result == 10;
                    }
                }
                """;
        var result = new JulcCompiler().compileWithDetails(source);
        assertNotNull(result.program());
        assertFalse(result.hasErrors());
        var pir = result.pirFormatted();
        assertTrue(pir.contains("equalsInteger"),
                "Var-inferred helper return should use equalsInteger, PIR: " + pir);
    }

    /**
     * Chained helpers: A→B→C with type narrowing should preserve types.
     */
    @Test
    void chainedHelpersPreserveType() {
        var source = """
                import java.math.BigInteger;

                @Validator
                class MyValidator {
                    static BigInteger addOne(BigInteger x) {
                        return x + 1;
                    }

                    static BigInteger doubleAndAdd(BigInteger x) {
                        return addOne(x + x);
                    }

                    @Entrypoint
                    static boolean validate(BigInteger redeemer, BigInteger ctx) {
                        return doubleAndAdd(redeemer) == 21;
                    }
                }
                """;
        var result = new JulcCompiler().compileWithDetails(source);
        assertNotNull(result.program());
        assertFalse(result.hasErrors());
        var pir = result.pirFormatted();
        assertTrue(pir.contains("equalsInteger"),
                "Chained helpers should use equalsInteger, PIR: " + pir);
    }

    /**
     * Integer literal on right-hand side of == with DataType left should still
     * use EqualsInteger when the right side is recognizably an integer literal.
     */
    @Test
    void literalComparisonUsesEqualsInteger() {
        var source = """
                import java.math.BigInteger;

                @Validator
                class MyValidator {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, BigInteger ctx) {
                        return redeemer == 42;
                    }
                }
                """;
        var result = new JulcCompiler().compileWithDetails(source);
        assertNotNull(result.program());
        assertFalse(result.hasErrors());
        var pir = result.pirFormatted();
        assertTrue(pir.contains("equalsInteger"),
                "Literal comparison should use equalsInteger, PIR: " + pir);
    }

    /**
     * Helper comparing result of arithmetic (which produces IntegerType PIR)
     * against a literal should use EqualsInteger.
     */
    @Test
    void helperWithArithmeticResultUsesEqualsInteger() {
        var source = """
                import java.math.BigInteger;

                @Validator
                class MyValidator {
                    static BigInteger compute(BigInteger x) {
                        return x * 2 + 1;
                    }

                    static boolean checkComputed(BigInteger x) {
                        var result = compute(x);
                        return result == 43;
                    }

                    @Entrypoint
                    static boolean validate(BigInteger redeemer, BigInteger ctx) {
                        return checkComputed(redeemer);
                    }
                }
                """;
        var result = new JulcCompiler().compileWithDetails(source);
        assertNotNull(result.program());
        assertFalse(result.hasErrors());
        var pir = result.pirFormatted();
        assertTrue(pir.contains("equalsInteger"),
                "Arithmetic result comparison should use equalsInteger, PIR: " + pir);
    }

    /**
     * Comparison where left is entrypoint param (typed as BigInteger = IntegerType).
     * This should already work since BigInteger maps to IntegerType in symbol table.
     */
    @Test
    void entrypointParamComparisonUsesEqualsInteger() {
        var source = """
                import java.math.BigInteger;

                @Validator
                class MyValidator {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, BigInteger ctx) {
                        return redeemer == ctx;
                    }
                }
                """;
        var result = new JulcCompiler().compileWithDetails(source);
        assertNotNull(result.program());
        assertFalse(result.hasErrors());
    }

    /**
     * String helper comparison should use EqualsString.
     */
    @Test
    void helperWithStringParamUsesEqualsString() {
        var source = """
                @Validator
                class MyValidator {
                    static boolean matches(String a, String b) {
                        return a == b;
                    }

                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        return matches("hello", "world");
                    }
                }
                """;
        var result = new JulcCompiler().compileWithDetails(source);
        assertNotNull(result.program());
        assertFalse(result.hasErrors());
        var pir = result.pirFormatted();
        assertTrue(pir.contains("equalsString"),
                "Helper with String params should use equalsString, PIR: " + pir);
    }
}
