package org.julclang.compiler.frontend.javac.lower;

import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerFrontend;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.frontend.javac.NotPorted;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.julclang.compiler.frontend.javac.lower.LoweringFixture.assertMethodNotPorted;
import static org.julclang.compiler.frontend.javac.lower.LoweringFixture.assertMethodRejected;
import static org.julclang.compiler.frontend.javac.lower.LoweringFixture.assertSameMethodAsReference;
import static org.julclang.compiler.frontend.javac.lower.LoweringFixture.assertSameMethodRejectionAsReference;

/** Literals (ADR-068 I9), against the reference's bytes at every level; the value comes from javac in one and JuLC's reader in the other. */
class JavacLiteralsTest {

    private static String returning(String type, String expression) {
        return """
                import java.math.BigInteger;
                import org.julclang.core.PlutusData;
                class P {
                    static %s m() { return %s; }
                }
                """.formatted(type, expression);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "5", "2147483647", "0x7FFFFFFF", "0xFFFFFFFF", "0x80000000", "017", "0b101", "1_000",
            "0L", "5L", "0xFFFFFFFFFFFFFFFFL", "9223372036854775807L", "0x8000000000000000L", "0777L", "0b1L",
            "(5)", "((5))"})
    void integerLiterals(String literal) {
        assertSameMethodAsReference(returning("long", literal), "m");
    }

    /** javac folds these into one literal, and the reference lowers each as {@code 0 - n}. */
    @ParameterizedTest
    @ValueSource(strings = {"-5", "- 5", "-/*c*/5", "-1", "-2147483648", "-2147483647", "-5L", "-9223372036854775808L", "(-5)",
            "\\u002d5", "\\uuu002D5"})
    void aMinusSignJavacFoldsIntoTheLiteral(String literal) {
        assertSameMethodAsReference(returning("long", literal), "m");
    }

    /** A negative value alone does not say javac folded a sign: {@code 0xFFFFFFFF} is -1, spelled with no sign. */
    @ParameterizedTest
    @ValueSource(strings = {"\\u0030xFFFFFFFF", "\\u0030x80000000", "\\u0030xFFFFFFFFFFFFFFFFL"})
    void aSpelledNegativeValueThroughAUnicodeEscapeIsNoNegation(String literal) {
        assertSameMethodAsReference(returning("long", literal), "m");
    }

    /** These stay unary to javac, so they are the operator's. */
    @ParameterizedTest
    @ValueSource(strings = {"-0", "-0x10", "-017", "-0b11", "-0L", "-(5)", "- -5"})
    void aMinusSignJavacKeepsIsAnOperator(String expression) {
        assertSameMethodAsReference(returning("long", expression), "m");
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "false", "(true)"})
    void booleans(String literal) {
        assertSameMethodAsReference(returning("boolean", literal), "m");
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"\"", "\"a\"", "\"a\\nb\"", "\"\\t\\\"\\\\\"", "\"a\\sb\"", "\"\\101\"", "\"\\7\"", "\"\\377\"",
            "\"é\"", "\"😀\"", "\"\\uD83D\\uDE00\"", "\"\\u0041\"", "\"\\uuuu0041\"",
            "\"\\\\user\"", "\"\\\\unicode\"", "\"\\\\u\"", "\"\\\\uu\"", "\"\\\\\\\\u0041\""})
    void strings(String literal) {
        assertSameMethodAsReference(returning("String", literal), "m");
    }

    @ParameterizedTest
    @ValueSource(strings = {"new byte[]{}", "new byte[]{1, 2, 3}", "new byte[]{-1, 0x7F, -0x10, - 1, -128, 127}", "new byte[]{-0}",
            "new byte[]{0, 017, 0b11}"})
    void byteArrays(String literal) {
        assertSameMethodAsReference(returning("byte[]", literal), "m");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "BigInteger|BigInteger.ZERO", "BigInteger|BigInteger.ONE", "BigInteger|BigInteger.TWO", "BigInteger|BigInteger.TEN",
            "PlutusData|PlutusData.UNIT", "BigInteger|(BigInteger.TEN)"})
    void apiConstants(String type, String expression) {
        assertSameMethodAsReference(returning(type, expression), "m");
    }

    /** A qualified class name is rejected by the reference ({@code Undefined variable: java}), so it is not accepted here. */
    @Test
    void anApiConstantSpelledAnotherWayIsTheReferencesRejection() {
        var options = new CompilerOptions().setFrontend(CompilerFrontend.JAVAC);
        var failure = assertThrows(CompilerException.class, () -> new JulcCompiler(null, options)
                .compileMethod(returning("BigInteger", "java.math.BigInteger.ZERO"), "m", List.of()));
        assertTrue(failure.getMessage().contains("Undefined variable: java"), failure::getMessage);
    }

    /** The constant is the field javac resolved: a user's type of that name is not the API's, on any spelling. */
    @Test
    void aUsersBigIntegerIsNotTheApisConstant() {
        assertMethodRejected("""
                class P {
                    static class BigInteger { static final long ZERO = 7; }
                    static long m() { return BigInteger.ZERO; }
                }
                """, "m", "JULC0125", 3);
    }

    /** A variable as the qualifier reads a static field through it, which the reference rejects. */
    @Test
    void anApiConstantReadThroughAVariableIsTheReferencesRejection() {
        assertSameMethodRejectionAsReference("""
                import java.math.BigInteger;
                class P {
                    static BigInteger m(BigInteger x) { return x.ZERO; }
                }
                """, "m");
    }

    /** A char has no on-chain representation: JULC0124 in the reference's words for its type, which it gives uncoded (M5). */
    @Test
    void aCharLiteralHasNoRepresentation() {
        var failure = assertThrows(CompilerException.class, () -> new JulcCompiler(null, new CompilerOptions().setFrontend(CompilerFrontend.JAVAC))
                .compileMethod(returning("int", "'a'"), "m", List.of()));
        assertEquals("JULC0124", failure.diagnostics().getFirst().code(), failure::getMessage);
        assertEquals("Unsupported primitive type: char. Supported types: boolean, int, long", failure.diagnostics().getFirst().message());
    }

    /** {@code null} is the reference's rejection, with its code: {@code JULC0017}. */
    @Test
    void nullIsTheReferencesRejection() {
        assertSameMethodRejectionAsReference(returning("String", "null"), "m");
    }

    /** Floating point is the reference's rejection, at its position. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"double|1.5", "float|1f"})
    void floatingPointIsTheReferencesRejection(String type, String literal) {
        LoweringFixture.assertSameMethodRejectionAsReference(returning(type, literal), "m");
    }

    /** {@code "a" + "b"} joins at run time: the tree keeps the {@code +}, and each literal is its own. */
    @Test
    void aConcatenationOfLiteralsIsAnOperator() {
        assertSameMethodAsReference(returning("String", "\"a\" + \"b\""), "m");
        assertMethodRejected(returning("String", "\"a\" + \"\"\"\n    b\"\"\""), "m", "JULC0125", 4);
    }

    /** Each literal is its own, so half a pair stops on both frontends, in each one's words. */
    @Test
    void aPairSplitAcrossAConcatenationIsRejected() {
        var source = returning("String", "\"\\uD83D\" + \"\\uDE00\"");
        var options = new CompilerOptions().setFrontend(CompilerFrontend.JAVAC);
        var failure = assertThrows(CompilerException.class, () -> new JulcCompiler(null, options).compileMethod(source, "m", List.of()));
        assertTrue(failure.getMessage().contains("unpaired surrogate U+D83D"), failure::getMessage);
        var reference = assertThrows(CompilerException.class, () -> new JulcCompiler().compileMethod(source, "m", List.of()));
        assertTrue(reference.getMessage().contains("unpaired surrogate U+D83D"), reference::getMessage);
    }

    @Test
    void aTextBlockIsNotAStringLiteralOfTheSubset() {
        assertMethodRejected("""
                class P {
                    static String m() { return \"""
                        hi
                        \"""; }
                }
                """, "m", "JULC0125", 2);
        assertMethodRejected("""
                class P {
                    static String m() { return \\u0022\\u0022\\u0022
                        hi
                        \"""; }
                }
                """, "m", "JULC0125", 2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"new byte[2]", "new long[]{1}", "new byte[][]{new byte[]{1}}", "new byte[]{(byte) 1}", "new byte[]{'a'}"})
    void otherArraysAreTheReferencesRejection(String expression) {
        String type = expression.contains("long") ? "long[]" : expression.contains("[][]") ? "byte[][]" : "byte[]";
        assertSameMethodRejectionAsReference(returning(type, expression), "m");
    }
}
