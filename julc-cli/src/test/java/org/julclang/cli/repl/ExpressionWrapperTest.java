package org.julclang.cli.repl;

import org.julclang.compiler.frontend.javac.ReplExpressionWrapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The CLI REPL's wrapper on the shared typed wrapper (ADR-068 D9): javac types the expression, an {@code int} or
 * {@code long} result is returned as a {@code BigInteger}, and a javac rejection is the REPL's error.
 */
class ExpressionWrapperTest {

    private static ReplExpressionWrapper.Wrapped wrap(String expression) {
        return ExpressionWrapper.wrap(expression, List.of(), List.of());
    }

    private static String type(String expression) {
        var wrapped = wrap(expression);
        assertTrue(wrapped.isAccepted(), () -> expression + ": " + wrapped.errors());
        return wrapped.returnType();
    }

    @Test
    void numericLiteral_infersBigInteger() {
        assertEquals("BigInteger", type("42"));
    }

    @Test
    void negativeLiteral_infersBigInteger() {
        assertEquals("BigInteger", type("-7"));
    }

    @Test
    void booleanTrue_infersBoolean() {
        assertEquals("boolean", type("true"));
    }

    @Test
    void booleanFalse_infersBoolean() {
        assertEquals("boolean", type("false"));
    }

    @Test
    void stringLiteral_infersString() {
        assertEquals("String", type("\"hello\""));
    }

    @Test
    void byteArrayConstruction_infersByteArray() {
        assertEquals("byte[]", type("new byte[]{1,2,3}"));
    }

    @Test
    void arithmeticExpression_infersBigInteger() {
        assertEquals("BigInteger", type("1 + 2"));
    }

    @Test
    void longArithmetic_infersBigInteger() {
        assertEquals("BigInteger", type("1L << 40"));
    }

    @Test
    void comparisonExpression_infersBoolean() {
        assertEquals("boolean", type("1 == 2"));
    }

    @Test
    void booleanOperator_infersBoolean() {
        assertEquals("boolean", type("true && false"));
    }

    @Test
    void libraryCall_infersItsReturnType() {
        assertEquals("BigInteger", type("MathLib.pow(BigInteger.TWO, BigInteger.TEN)"));
        assertEquals("boolean", type("ListsLib.isEmpty(ListsLib.empty())"));
        assertEquals("byte[]", type("Builtins.sha2_256(new byte[]{1})"));
    }

    @Test
    void dataExpression_keepsItsDeclaredType() {
        assertEquals("PlutusData.IntData", type("Builtins.iData(BigInteger.ONE)"));
    }

    @Test
    void wrapNumericExpression_returnsBigIntegerValueOf() {
        var wrapped = wrap("1 + 2");
        assertTrue(wrapped.source().contains("class __Repl"));
        assertTrue(wrapped.source().contains("static BigInteger __eval()"));
        assertTrue(wrapped.source().contains("return BigInteger.valueOf((1 + 2));"), wrapped.source());
    }

    @Test
    void wrapBooleanExpression_generatesValidClass() {
        var wrapped = wrap("true");
        assertTrue(wrapped.source().contains("static boolean __eval()"));
        assertTrue(wrapped.source().contains("return (true);"), wrapped.source());
    }

    @Test
    void blockOfStatements_isTypedByItsReturns() {
        var wrapped = wrap("long a = 2; return a * 3;");
        assertTrue(wrapped.isAccepted(), () -> wrapped.errors().toString());
        assertEquals("BigInteger", wrapped.returnType());
        assertTrue(wrapped.source().contains("return BigInteger.valueOf(a * 3);"), wrapped.source());
    }

    @Test
    void unknownName_isRejectedByJavacAtItsInputPosition() {
        var wrapped = wrap("undefinedName + 1");
        assertFalse(wrapped.isAccepted());
        var error = wrapped.errors().getFirst();
        assertEquals("JULC0085", error.code());
        assertTrue(error.message().contains("(compiler.err.cant.resolve.location)"), error.message());
        assertEquals(1, error.line());
        assertEquals(1, error.column(), "the column is counted from the input, not the wrapper");
    }

    @Test
    void wrapIncludesAutoImports() {
        String source = wrap("1").source();
        assertTrue(source.contains("import org.julclang.stdlib.Builtins;"));
        assertTrue(source.contains("import org.julclang.stdlib.lib.ListsLib;"));
        assertTrue(source.contains("import org.julclang.stdlib.lib.MapLib;"));
        assertTrue(source.contains("import java.math.BigInteger;"));
        assertTrue(source.contains("import org.julclang.ledger.*;"));
        assertTrue(source.contains("import org.julclang.core.PlutusData;"));
    }

    @Test
    void wrapIncludesUserImports() {
        var wrapped = ExpressionWrapper.wrap("JulcArray.of(BigInteger.ONE).length()",
                List.of("org.julclang.core.types.JulcArray"), List.of());
        assertTrue(wrapped.isAccepted(), () -> wrapped.errors().toString());
        assertTrue(wrapped.source().contains("import org.julclang.core.types.JulcArray;"));
    }

    @Test
    void unknownUserImport_isRejectedByJavac() {
        var wrapped = ExpressionWrapper.wrap("1", List.of("com.example.MyLib"), List.of());
        assertFalse(wrapped.isAccepted());
        assertTrue(wrapped.errors().getFirst().message().contains("(compiler.err.doesnt.exist)"),
                () -> wrapped.errors().toString());
    }
}
