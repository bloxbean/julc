package com.bloxbean.cardano.plutus.compiler.pir;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PirTypeTest {

    @Test void integerType() { assertNotNull(new PirType.IntegerType()); }
    @Test void byteStringType() { assertNotNull(new PirType.ByteStringType()); }
    @Test void stringType() { assertNotNull(new PirType.StringType()); }
    @Test void boolType() { assertNotNull(new PirType.BoolType()); }
    @Test void unitType() { assertNotNull(new PirType.UnitType()); }
    @Test void dataType() { assertNotNull(new PirType.DataType()); }

    @Test void listType() {
        var t = new PirType.ListType(new PirType.IntegerType());
        assertEquals(new PirType.IntegerType(), t.elemType());
    }

    @Test void mapType() {
        var t = new PirType.MapType(new PirType.StringType(), new PirType.IntegerType());
        assertEquals(new PirType.StringType(), t.keyType());
    }

    @Test void funType() {
        var t = new PirType.FunType(new PirType.IntegerType(), new PirType.BoolType());
        assertEquals(new PirType.IntegerType(), t.paramType());
        assertEquals(new PirType.BoolType(), t.returnType());
    }

    @Test void recordType() {
        var fields = List.of(
                new PirType.Field("x", new PirType.IntegerType()),
                new PirType.Field("y", new PirType.ByteStringType()));
        var t = new PirType.RecordType("Point", fields);
        assertEquals("Point", t.name());
        assertEquals(2, t.fields().size());
    }

    @Test void sumType() {
        var constructors = List.of(
                new PirType.Constructor("Left", 0, List.of(new PirType.Field("value", new PirType.IntegerType()))),
                new PirType.Constructor("Right", 1, List.of(new PirType.Field("value", new PirType.StringType()))));
        var t = new PirType.SumType("Either", constructors);
        assertEquals(2, t.constructors().size());
    }

    @Test void equality() {
        assertEquals(new PirType.IntegerType(), new PirType.IntegerType());
        assertEquals(new PirType.ListType(new PirType.BoolType()), new PirType.ListType(new PirType.BoolType()));
    }

    @Test void inequality() {
        assertNotEquals(new PirType.IntegerType(), new PirType.StringType());
    }
}
