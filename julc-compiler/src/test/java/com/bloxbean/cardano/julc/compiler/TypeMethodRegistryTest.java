package com.bloxbean.cardano.julc.compiler;

import com.bloxbean.cardano.julc.compiler.pir.PirTerm;
import com.bloxbean.cardano.julc.compiler.pir.PirType;
import com.bloxbean.cardano.julc.compiler.pir.TypeMethodRegistry;
import com.bloxbean.cardano.julc.core.Constant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TypeMethodRegistry dispatch and return type resolution.
 */
class TypeMethodRegistryTest {

    TypeMethodRegistry registry;

    @BeforeEach
    void setUp() {
        registry = TypeMethodRegistry.defaultRegistry();
    }

    // --- Registry contains checks ---

    @Test
    void containsIntegerMethods() {
        assertTrue(registry.contains("IntegerType", "abs"));
        assertTrue(registry.contains("IntegerType", "negate"));
        assertTrue(registry.contains("IntegerType", "max"));
        assertTrue(registry.contains("IntegerType", "min"));
        assertTrue(registry.contains("IntegerType", "equals"));
        assertTrue(registry.contains("IntegerType", "add"));
        assertTrue(registry.contains("IntegerType", "subtract"));
        assertTrue(registry.contains("IntegerType", "multiply"));
        assertTrue(registry.contains("IntegerType", "divide"));
        assertTrue(registry.contains("IntegerType", "remainder"));
        assertTrue(registry.contains("IntegerType", "mod"));
        assertTrue(registry.contains("IntegerType", "signum"));
        assertTrue(registry.contains("IntegerType", "compareTo"));
    }

    @Test
    void containsByteStringMethods() {
        assertTrue(registry.contains("ByteStringType", "length"));
        assertTrue(registry.contains("ByteStringType", "equals"));
    }

    @Test
    void containsStringMethods() {
        assertTrue(registry.contains("StringType", "length"));
        assertTrue(registry.contains("StringType", "equals"));
    }

    @Test
    void containsDataEqualsMethods() {
        assertTrue(registry.contains("DataType", "equals"));
        assertTrue(registry.contains("RecordType", "equals"));
        assertTrue(registry.contains("SumType", "equals"));
    }

    @Test
    void containsListMethods() {
        assertTrue(registry.contains("ListType", "size"));
        assertTrue(registry.contains("ListType", "isEmpty"));
        assertTrue(registry.contains("ListType", "head"));
        assertTrue(registry.contains("ListType", "tail"));
        assertTrue(registry.contains("ListType", "contains"));
        assertTrue(registry.contains("ListType", "get"));
    }

    @Test
    void containsOptionalMethods() {
        assertTrue(registry.contains("OptionalType", "isPresent"));
        assertTrue(registry.contains("OptionalType", "isEmpty"));
        assertTrue(registry.contains("OptionalType", "get"));
    }

    @Test
    void containsMapMethods() {
        assertTrue(registry.contains("MapType", "get"));
        assertTrue(registry.contains("MapType", "containsKey"));
        assertTrue(registry.contains("MapType", "size"));
    }

    @Test
    void doesNotContainUnknownMethod() {
        assertFalse(registry.contains("IntegerType", "unknown"));
        assertFalse(registry.contains("UnknownType", "abs"));
    }

    // --- Dispatch tests ---

    @Test
    void dispatchReturnsEmptyForUnknownMethod() {
        var scope = new PirTerm.Const(Constant.integer(BigInteger.ONE));
        var result = registry.dispatch(scope, "nonexistent", List.of(),
                new PirType.IntegerType(), List.of());
        assertTrue(result.isEmpty());
    }

    @Test
    void dispatchReturnsEmptyForUnknownType() {
        var scope = new PirTerm.Const(Constant.unit());
        var result = registry.dispatch(scope, "abs", List.of(),
                new PirType.UnitType(), List.of());
        assertTrue(result.isEmpty());
    }

    @Test
    void dispatchIntegerAbsReturnsPresent() {
        var scope = new PirTerm.Const(Constant.integer(BigInteger.valueOf(-5)));
        var result = registry.dispatch(scope, "abs", List.of(),
                new PirType.IntegerType(), List.of());
        assertTrue(result.isPresent());
        assertInstanceOf(PirTerm.IfThenElse.class, result.get());
    }

    @Test
    void dispatchIntegerNegateReturnsPresent() {
        var scope = new PirTerm.Const(Constant.integer(BigInteger.valueOf(5)));
        var result = registry.dispatch(scope, "negate", List.of(),
                new PirType.IntegerType(), List.of());
        assertTrue(result.isPresent());
        assertInstanceOf(PirTerm.App.class, result.get());
    }

    @Test
    void dispatchListIsEmptyReturnsPresent() {
        var scope = new PirTerm.Var("myList", new PirType.ListType(new PirType.DataType()));
        var result = registry.dispatch(scope, "isEmpty", List.of(),
                new PirType.ListType(new PirType.DataType()), List.of());
        assertTrue(result.isPresent());
    }

    @Test
    void dispatchStringEqualsReturnsPresent() {
        var scope = new PirTerm.Const(Constant.string("hello"));
        var arg = new PirTerm.Const(Constant.string("world"));
        var result = registry.dispatch(scope, "equals", List.of(arg),
                new PirType.StringType(), List.of(new PirType.StringType()));
        assertTrue(result.isPresent());
    }

    // --- Return type resolution tests ---

    @Test
    void resolveReturnTypeIntegerAbs() {
        var rt = registry.resolveReturnType(new PirType.IntegerType(), "abs");
        assertTrue(rt.isPresent());
        assertInstanceOf(PirType.IntegerType.class, rt.get());
    }

    @Test
    void resolveReturnTypeIntegerNegate() {
        var rt = registry.resolveReturnType(new PirType.IntegerType(), "negate");
        assertTrue(rt.isPresent());
        assertInstanceOf(PirType.IntegerType.class, rt.get());
    }

    @Test
    void resolveReturnTypeIntegerMax() {
        var rt = registry.resolveReturnType(new PirType.IntegerType(), "max");
        assertTrue(rt.isPresent());
        assertInstanceOf(PirType.IntegerType.class, rt.get());
    }

    @Test
    void resolveReturnTypeIntegerMin() {
        var rt = registry.resolveReturnType(new PirType.IntegerType(), "min");
        assertTrue(rt.isPresent());
        assertInstanceOf(PirType.IntegerType.class, rt.get());
    }

    @Test
    void resolveReturnTypeIntegerEquals() {
        var rt = registry.resolveReturnType(new PirType.IntegerType(), "equals");
        assertTrue(rt.isPresent());
        assertInstanceOf(PirType.BoolType.class, rt.get());
    }

    @Test
    void resolveReturnTypeByteStringLength() {
        var rt = registry.resolveReturnType(new PirType.ByteStringType(), "length");
        assertTrue(rt.isPresent());
        assertInstanceOf(PirType.IntegerType.class, rt.get());
    }

    @Test
    void resolveReturnTypeByteStringEquals() {
        var rt = registry.resolveReturnType(new PirType.ByteStringType(), "equals");
        assertTrue(rt.isPresent());
        assertInstanceOf(PirType.BoolType.class, rt.get());
    }

    @Test
    void resolveReturnTypeStringLength() {
        var rt = registry.resolveReturnType(new PirType.StringType(), "length");
        assertTrue(rt.isPresent());
        assertInstanceOf(PirType.IntegerType.class, rt.get());
    }

    @Test
    void resolveReturnTypeStringEquals() {
        var rt = registry.resolveReturnType(new PirType.StringType(), "equals");
        assertTrue(rt.isPresent());
        assertInstanceOf(PirType.BoolType.class, rt.get());
    }

    @Test
    void resolveReturnTypeDataEquals() {
        var rt = registry.resolveReturnType(new PirType.DataType(), "equals");
        assertTrue(rt.isPresent());
        assertInstanceOf(PirType.BoolType.class, rt.get());
    }

    @Test
    void resolveReturnTypeRecordEquals() {
        var rt = registry.resolveReturnType(
                new PirType.RecordType("Foo", List.of()), "equals");
        assertTrue(rt.isPresent());
        assertInstanceOf(PirType.BoolType.class, rt.get());
    }

    @Test
    void resolveReturnTypeSumTypeEquals() {
        var rt = registry.resolveReturnType(
                new PirType.SumType("Bar", List.of()), "equals");
        assertTrue(rt.isPresent());
        assertInstanceOf(PirType.BoolType.class, rt.get());
    }

    @Test
    void resolveReturnTypeListSize() {
        var rt = registry.resolveReturnType(
                new PirType.ListType(new PirType.DataType()), "size");
        assertTrue(rt.isPresent());
        assertInstanceOf(PirType.IntegerType.class, rt.get());
    }

    @Test
    void resolveReturnTypeListIsEmpty() {
        var rt = registry.resolveReturnType(
                new PirType.ListType(new PirType.DataType()), "isEmpty");
        assertTrue(rt.isPresent());
        assertInstanceOf(PirType.BoolType.class, rt.get());
    }

    @Test
    void resolveReturnTypeListHead() {
        var rt = registry.resolveReturnType(
                new PirType.ListType(new PirType.ByteStringType()), "head");
        assertTrue(rt.isPresent());
        assertInstanceOf(PirType.ByteStringType.class, rt.get());
    }

    @Test
    void resolveReturnTypeListTail() {
        var lt = new PirType.ListType(new PirType.IntegerType());
        var rt = registry.resolveReturnType(lt, "tail");
        assertTrue(rt.isPresent());
        assertEquals(lt, rt.get());
    }

    @Test
    void resolveReturnTypeListContains() {
        var rt = registry.resolveReturnType(
                new PirType.ListType(new PirType.DataType()), "contains");
        assertTrue(rt.isPresent());
        assertInstanceOf(PirType.BoolType.class, rt.get());
    }

    @Test
    void resolveReturnTypeOptionalIsPresent() {
        var rt = registry.resolveReturnType(
                new PirType.OptionalType(new PirType.IntegerType()), "isPresent");
        assertTrue(rt.isPresent());
        assertInstanceOf(PirType.BoolType.class, rt.get());
    }

    @Test
    void resolveReturnTypeOptionalIsEmpty() {
        var rt = registry.resolveReturnType(
                new PirType.OptionalType(new PirType.IntegerType()), "isEmpty");
        assertTrue(rt.isPresent());
        assertInstanceOf(PirType.BoolType.class, rt.get());
    }

    @Test
    void resolveReturnTypeOptionalGet() {
        var rt = registry.resolveReturnType(
                new PirType.OptionalType(new PirType.ByteStringType()), "get");
        assertTrue(rt.isPresent());
        assertInstanceOf(PirType.ByteStringType.class, rt.get());
    }

    @Test
    void resolveReturnTypeUnknownReturnsEmpty() {
        var rt = registry.resolveReturnType(new PirType.IntegerType(), "unknown");
        assertTrue(rt.isEmpty());
    }

    @Test
    void resolveReturnTypeIntegerAdd() {
        var rt = registry.resolveReturnType(new PirType.IntegerType(), "add");
        assertTrue(rt.isPresent());
        assertInstanceOf(PirType.IntegerType.class, rt.get());
    }

    @Test
    void resolveReturnTypeIntegerSubtract() {
        var rt = registry.resolveReturnType(new PirType.IntegerType(), "subtract");
        assertTrue(rt.isPresent());
        assertInstanceOf(PirType.IntegerType.class, rt.get());
    }

    @Test
    void resolveReturnTypeIntegerMultiply() {
        var rt = registry.resolveReturnType(new PirType.IntegerType(), "multiply");
        assertTrue(rt.isPresent());
        assertInstanceOf(PirType.IntegerType.class, rt.get());
    }

    @Test
    void resolveReturnTypeIntegerDivide() {
        var rt = registry.resolveReturnType(new PirType.IntegerType(), "divide");
        assertTrue(rt.isPresent());
        assertInstanceOf(PirType.IntegerType.class, rt.get());
    }

    @Test
    void resolveReturnTypeIntegerRemainder() {
        var rt = registry.resolveReturnType(new PirType.IntegerType(), "remainder");
        assertTrue(rt.isPresent());
        assertInstanceOf(PirType.IntegerType.class, rt.get());
    }

    @Test
    void resolveReturnTypeIntegerMod() {
        var rt = registry.resolveReturnType(new PirType.IntegerType(), "mod");
        assertTrue(rt.isPresent());
        assertInstanceOf(PirType.IntegerType.class, rt.get());
    }

    @Test
    void resolveReturnTypeIntegerSignum() {
        var rt = registry.resolveReturnType(new PirType.IntegerType(), "signum");
        assertTrue(rt.isPresent());
        assertInstanceOf(PirType.IntegerType.class, rt.get());
    }

    @Test
    void resolveReturnTypeIntegerCompareTo() {
        var rt = registry.resolveReturnType(new PirType.IntegerType(), "compareTo");
        assertTrue(rt.isPresent());
        assertInstanceOf(PirType.IntegerType.class, rt.get());
    }

    @Test
    void dispatchIntegerAddReturnsPresent() {
        var scope = new PirTerm.Const(Constant.integer(BigInteger.valueOf(10)));
        var arg = new PirTerm.Const(Constant.integer(BigInteger.valueOf(5)));
        var result = registry.dispatch(scope, "add", List.of(arg),
                new PirType.IntegerType(), List.of(new PirType.IntegerType()));
        assertTrue(result.isPresent());
        assertInstanceOf(PirTerm.App.class, result.get());
    }

    @Test
    void dispatchIntegerSignumReturnsPresent() {
        var scope = new PirTerm.Const(Constant.integer(BigInteger.valueOf(42)));
        var result = registry.dispatch(scope, "signum", List.of(),
                new PirType.IntegerType(), List.of());
        assertTrue(result.isPresent());
        assertInstanceOf(PirTerm.IfThenElse.class, result.get());
    }

    @Test
    void dispatchIntegerCompareToReturnsPresent() {
        var scope = new PirTerm.Const(Constant.integer(BigInteger.valueOf(3)));
        var arg = new PirTerm.Const(Constant.integer(BigInteger.valueOf(7)));
        var result = registry.dispatch(scope, "compareTo", List.of(arg),
                new PirType.IntegerType(), List.of(new PirType.IntegerType()));
        assertTrue(result.isPresent());
        assertInstanceOf(PirTerm.IfThenElse.class, result.get());
    }

    @Test
    void dispatchListGetReturnsPresent() {
        var scope = new PirTerm.Var("myList", new PirType.ListType(new PirType.DataType()));
        var idx = new PirTerm.Const(Constant.integer(BigInteger.valueOf(2)));
        var result = registry.dispatch(scope, "get", List.of(idx),
                new PirType.ListType(new PirType.DataType()), List.of(new PirType.IntegerType()));
        assertTrue(result.isPresent());
        assertInstanceOf(PirTerm.LetRec.class, result.get());
    }

    @Test
    void dispatchMapGetReturnsPresent() {
        var scope = new PirTerm.Var("myMap",
                new PirType.MapType(new PirType.DataType(), new PirType.DataType()));
        var key = new PirTerm.Const(Constant.integer(BigInteger.ONE));
        var result = registry.dispatch(scope, "get", List.of(key),
                new PirType.MapType(new PirType.DataType(), new PirType.DataType()),
                List.of(new PirType.DataType()));
        assertTrue(result.isPresent());
        assertInstanceOf(PirTerm.Let.class, result.get());
    }

    @Test
    void dispatchMapContainsKeyReturnsPresent() {
        var scope = new PirTerm.Var("myMap",
                new PirType.MapType(new PirType.DataType(), new PirType.DataType()));
        var key = new PirTerm.Const(Constant.integer(BigInteger.ONE));
        var result = registry.dispatch(scope, "containsKey", List.of(key),
                new PirType.MapType(new PirType.DataType(), new PirType.DataType()),
                List.of(new PirType.DataType()));
        assertTrue(result.isPresent());
        assertInstanceOf(PirTerm.Let.class, result.get());
    }

    @Test
    void dispatchMapSizeReturnsPresent() {
        var scope = new PirTerm.Var("myMap",
                new PirType.MapType(new PirType.DataType(), new PirType.DataType()));
        var result = registry.dispatch(scope, "size", List.of(),
                new PirType.MapType(new PirType.DataType(), new PirType.DataType()), List.of());
        assertTrue(result.isPresent());
    }

    @Test
    void resolveReturnTypeListGet() {
        var rt = registry.resolveReturnType(
                new PirType.ListType(new PirType.ByteStringType()), "get");
        assertTrue(rt.isPresent());
        assertInstanceOf(PirType.ByteStringType.class, rt.get());
    }

    @Test
    void resolveReturnTypeMapGet() {
        var rt = registry.resolveReturnType(
                new PirType.MapType(new PirType.ByteStringType(), new PirType.IntegerType()), "get");
        assertTrue(rt.isPresent());
        assertInstanceOf(PirType.OptionalType.class, rt.get());
        var opt = (PirType.OptionalType) rt.get();
        assertInstanceOf(PirType.IntegerType.class, opt.elemType());
    }

    @Test
    void resolveReturnTypeMapContainsKey() {
        var rt = registry.resolveReturnType(
                new PirType.MapType(new PirType.DataType(), new PirType.DataType()), "containsKey");
        assertTrue(rt.isPresent());
        assertInstanceOf(PirType.BoolType.class, rt.get());
    }

    @Test
    void resolveReturnTypeMapSize() {
        var rt = registry.resolveReturnType(
                new PirType.MapType(new PirType.DataType(), new PirType.DataType()), "size");
        assertTrue(rt.isPresent());
        assertInstanceOf(PirType.IntegerType.class, rt.get());
    }

    // --- Type key derivation ---

    @Test
    void typeKeyUsesSimpleClassName() {
        // Verify dispatch uses getClass().getSimpleName() correctly
        var scope = new PirTerm.Const(Constant.integer(BigInteger.ONE));
        // IntegerType should dispatch to "IntegerType.abs"
        var result = registry.dispatch(scope, "abs", List.of(),
                new PirType.IntegerType(), List.of());
        assertTrue(result.isPresent(), "IntegerType should dispatch to IntegerType.abs");
    }
}
