package com.bloxbean.cardano.julc.core;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConstantTest {

    // --- IntegerConst ---

    @Test
    void integerConstFromLong() {
        var c = Constant.integer(42);
        assertInstanceOf(Constant.IntegerConst.class, c);
        assertEquals(BigInteger.valueOf(42), ((Constant.IntegerConst) c).value());
        assertEquals(DefaultUni.INTEGER, c.type());
    }

    @Test
    void integerConstFromBigInteger() {
        var big = new BigInteger("123456789012345678901234567890");
        var c = Constant.integer(big);
        assertEquals(big, ((Constant.IntegerConst) c).value());
    }

    @Test
    void integerConstNullRejected() {
        assertThrows(NullPointerException.class, () -> new Constant.IntegerConst(null));
    }

    @Test
    void integerConstEquality() {
        assertEquals(new Constant.IntegerConst(42), new Constant.IntegerConst(BigInteger.valueOf(42)));
    }

    // --- ByteStringConst ---

    @Test
    void byteStringConst() {
        var c = Constant.byteString(new byte[]{1, 2, 3});
        assertInstanceOf(Constant.ByteStringConst.class, c);
        assertArrayEquals(new byte[]{1, 2, 3}, ((Constant.ByteStringConst) c).value());
        assertEquals(DefaultUni.BYTESTRING, c.type());
    }

    @Test
    void byteStringConstDefensiveCopy() {
        byte[] original = {1, 2, 3};
        var c = new Constant.ByteStringConst(original);
        original[0] = 99;
        assertArrayEquals(new byte[]{1, 2, 3}, c.value());
    }

    @Test
    void byteStringConstValueDefensiveCopy() {
        var c = new Constant.ByteStringConst(new byte[]{1, 2, 3});
        byte[] val = c.value();
        val[0] = 99;
        assertArrayEquals(new byte[]{1, 2, 3}, c.value());
    }

    @Test
    void byteStringConstNullRejected() {
        assertThrows(NullPointerException.class, () -> new Constant.ByteStringConst(null));
    }

    @Test
    void byteStringConstEquality() {
        assertEquals(new Constant.ByteStringConst(new byte[]{1, 2}), new Constant.ByteStringConst(new byte[]{1, 2}));
        assertNotEquals(new Constant.ByteStringConst(new byte[]{1, 2}), new Constant.ByteStringConst(new byte[]{1, 3}));
    }

    @Test
    void byteStringConstEmpty() {
        var c = new Constant.ByteStringConst(new byte[]{});
        assertArrayEquals(new byte[]{}, c.value());
    }

    // --- StringConst ---

    @Test
    void stringConst() {
        var c = Constant.string("hello");
        assertInstanceOf(Constant.StringConst.class, c);
        assertEquals("hello", ((Constant.StringConst) c).value());
        assertEquals(DefaultUni.STRING, c.type());
    }

    @Test
    void stringConstNullRejected() {
        assertThrows(NullPointerException.class, () -> new Constant.StringConst(null));
    }

    @Test
    void stringConstEmpty() {
        var c = new Constant.StringConst("");
        assertEquals("", c.value());
    }

    // --- UnitConst ---

    @Test
    void unitConst() {
        var c = Constant.unit();
        assertInstanceOf(Constant.UnitConst.class, c);
        assertEquals(DefaultUni.UNIT, c.type());
    }

    @Test
    void unitConstEquality() {
        assertEquals(new Constant.UnitConst(), new Constant.UnitConst());
    }

    // --- BoolConst ---

    @Test
    void boolConstTrue() {
        var c = Constant.bool(true);
        assertInstanceOf(Constant.BoolConst.class, c);
        assertTrue(((Constant.BoolConst) c).value());
        assertEquals(DefaultUni.BOOL, c.type());
    }

    @Test
    void boolConstFalse() {
        var c = Constant.bool(false);
        assertFalse(((Constant.BoolConst) c).value());
    }

    // --- DataConst ---

    @Test
    void dataConst() {
        var pd = PlutusData.integer(99);
        var c = Constant.data(pd);
        assertInstanceOf(Constant.DataConst.class, c);
        assertEquals(pd, ((Constant.DataConst) c).value());
        assertEquals(DefaultUni.DATA, c.type());
    }

    @Test
    void dataConstNullRejected() {
        assertThrows(NullPointerException.class, () -> new Constant.DataConst(null));
    }

    // --- ListConst ---

    @Test
    void listConst() {
        var lc = new Constant.ListConst(DefaultUni.INTEGER, List.of(Constant.integer(1), Constant.integer(2)));
        assertEquals(2, lc.values().size());
        assertEquals(DefaultUni.listOf(DefaultUni.INTEGER), lc.type());
    }

    @Test
    void listConstImmutable() {
        var lc = new Constant.ListConst(DefaultUni.INTEGER, List.of(Constant.integer(1)));
        assertThrows(UnsupportedOperationException.class, () -> lc.values().add(Constant.integer(2)));
    }

    @Test
    void listConstEmpty() {
        var lc = new Constant.ListConst(DefaultUni.STRING, List.of());
        assertTrue(lc.values().isEmpty());
    }

    // --- PairConst ---

    @Test
    void pairConst() {
        var pc = new Constant.PairConst(Constant.integer(1), Constant.string("hello"));
        assertEquals(Constant.integer(1), pc.first());
        assertEquals(Constant.string("hello"), pc.second());
        assertEquals(DefaultUni.pairOf(DefaultUni.INTEGER, DefaultUni.STRING), pc.type());
    }

    @Test
    void pairConstNullRejected() {
        assertThrows(NullPointerException.class, () -> new Constant.PairConst(null, Constant.integer(1)));
        assertThrows(NullPointerException.class, () -> new Constant.PairConst(Constant.integer(1), null));
    }

    // --- BLS12-381 types ---

    @Test
    void bls12_381G1Element() {
        byte[] point = new byte[48]; // G1 compressed size
        var c = new Constant.Bls12_381_G1Element(point);
        assertArrayEquals(point, c.value());
        assertEquals(DefaultUni.BLS12_381_G1, c.type());
    }

    @Test
    void bls12_381G1ElementDefensiveCopy() {
        byte[] point = {1, 2, 3};
        var c = new Constant.Bls12_381_G1Element(point);
        point[0] = 99;
        assertArrayEquals(new byte[]{1, 2, 3}, c.value());
    }

    @Test
    void bls12_381G1ElementEquality() {
        assertEquals(
                new Constant.Bls12_381_G1Element(new byte[]{1, 2}),
                new Constant.Bls12_381_G1Element(new byte[]{1, 2}));
        assertNotEquals(
                new Constant.Bls12_381_G1Element(new byte[]{1, 2}),
                new Constant.Bls12_381_G1Element(new byte[]{1, 3}));
    }

    @Test
    void bls12_381G2Element() {
        byte[] point = new byte[96]; // G2 compressed size
        var c = new Constant.Bls12_381_G2Element(point);
        assertArrayEquals(point, c.value());
        assertEquals(DefaultUni.BLS12_381_G2, c.type());
    }

    @Test
    void bls12_381MlResult() {
        byte[] result = new byte[384]; // ML result size
        var c = new Constant.Bls12_381_MlResult(result);
        assertArrayEquals(result, c.value());
        assertEquals(DefaultUni.BLS12_381_ML, c.type());
    }

    // --- Sealed interface pattern matching ---

    @Test
    void sealedInterfacePatternMatch() {
        Constant c = Constant.integer(42);
        String result = switch (c) {
            case Constant.IntegerConst i -> "int:" + i.value();
            case Constant.ByteStringConst bs -> "bytes";
            case Constant.StringConst s -> "string:" + s.value();
            case Constant.UnitConst _ -> "unit";
            case Constant.BoolConst b -> "bool:" + b.value();
            case Constant.DataConst d -> "data";
            case Constant.ListConst l -> "list:" + l.values().size();
            case Constant.PairConst p -> "pair";
            case Constant.Bls12_381_G1Element _ -> "g1";
            case Constant.Bls12_381_G2Element _ -> "g2";
            case Constant.Bls12_381_MlResult _ -> "ml";
            case Constant.ArrayConst _ -> "array";
            case Constant.ValueConst _ -> "value";
        };
        assertEquals("int:42", result);
    }
}
