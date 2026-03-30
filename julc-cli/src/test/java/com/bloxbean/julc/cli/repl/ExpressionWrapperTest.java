package com.bloxbean.julc.cli.repl;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExpressionWrapperTest {

    @Test
    void numericLiteral_infersBigInteger() {
        assertEquals("BigInteger", ExpressionWrapper.inferReturnType("42"));
    }

    @Test
    void negativeLiteral_infersBigInteger() {
        assertEquals("BigInteger", ExpressionWrapper.inferReturnType("-7"));
    }

    @Test
    void booleanTrue_infersBoolean() {
        assertEquals("boolean", ExpressionWrapper.inferReturnType("true"));
    }

    @Test
    void booleanFalse_infersBoolean() {
        assertEquals("boolean", ExpressionWrapper.inferReturnType("false"));
    }

    @Test
    void stringLiteral_infersString() {
        assertEquals("String", ExpressionWrapper.inferReturnType("\"hello\""));
    }

    @Test
    void byteArrayConstruction_infersByteArray() {
        assertEquals("byte[]", ExpressionWrapper.inferReturnType("new byte[]{1,2,3}"));
    }

    @Test
    void arithmeticExpression_infersBigInteger() {
        assertEquals("BigInteger", ExpressionWrapper.inferReturnType("1 + 2"));
    }

    @Test
    void comparisonExpression_infersBoolean() {
        assertEquals("boolean", ExpressionWrapper.inferReturnType("1 == 2"));
    }

    @Test
    void booleanOperator_infersBoolean() {
        assertEquals("boolean", ExpressionWrapper.inferReturnType("a && b"));
    }

    @Test
    void knownIntegerMethod_infersBigInteger() {
        assertEquals("BigInteger", ExpressionWrapper.inferReturnType("ListsLib.length(xs)"));
    }

    @Test
    void knownBooleanMethod_infersBoolean() {
        assertEquals("boolean", ExpressionWrapper.inferReturnType("ListsLib.isEmpty(xs)"));
    }

    @Test
    void knownBytesMethod_infersByteArray() {
        assertEquals("byte[]", ExpressionWrapper.inferReturnType("Builtins.sha2_256(data)"));
    }

    @Test
    void unknownExpression_infersPlutusData() {
        assertEquals("PlutusData", ExpressionWrapper.inferReturnType("someUnknown(x)"));
    }

    @Test
    void wrapNumericExpression_generatesValidClass() {
        String source = ExpressionWrapper.wrap("1 + 2", List.of());
        assertTrue(source.contains("class __Repl"));
        assertTrue(source.contains("static BigInteger __eval()"));
        assertTrue(source.contains("return 1 + 2;"));
    }

    @Test
    void wrapBooleanExpression_generatesValidClass() {
        String source = ExpressionWrapper.wrap("true", List.of());
        assertTrue(source.contains("static boolean __eval()"));
        assertTrue(source.contains("return true;"));
    }

    @Test
    void wrapPlutusDataExpression_addsCastWrapper() {
        String source = ExpressionWrapper.wrap("someCall(x)", List.of());
        assertTrue(source.contains("static PlutusData __eval()"));
        assertTrue(source.contains("(PlutusData)(Object)"));
    }

    @Test
    void wrapAsData_alwaysUsesCastWrapper() {
        String source = ExpressionWrapper.wrapAsData("1 + 2", List.of());
        assertTrue(source.contains("static PlutusData __eval()"));
        assertTrue(source.contains("(PlutusData)(Object)"));
    }

    @Test
    void wrapIncludesAutoImports() {
        String source = ExpressionWrapper.wrap("1", List.of());
        assertTrue(source.contains("import com.bloxbean.cardano.julc.stdlib.Builtins;"));
        assertTrue(source.contains("import com.bloxbean.cardano.julc.stdlib.lib.ListsLib;"));
        assertTrue(source.contains("import com.bloxbean.cardano.julc.stdlib.lib.MapLib;"));
        assertTrue(source.contains("import java.math.BigInteger;"));
        assertTrue(source.contains("import com.bloxbean.cardano.julc.ledger.*;"));
        assertTrue(source.contains("import com.bloxbean.cardano.julc.core.PlutusData;"));
    }

    @Test
    void wrapIncludesUserImports() {
        String source = ExpressionWrapper.wrap("1", List.of("com.example.MyLib"));
        assertTrue(source.contains("import com.example.MyLib;"));
    }

    @Test
    void mathPow_infersBigInteger() {
        assertEquals("BigInteger", ExpressionWrapper.inferReturnType("MathLib.pow(2, 10)"));
    }

    @Test
    void arithmeticInsideParens_notDetectedAsTopLevel() {
        // "foo(1 + 2)" — the + is inside parens, so not top-level arithmetic
        assertEquals("PlutusData", ExpressionWrapper.inferReturnType("foo(1 + 2)"));
    }
}
