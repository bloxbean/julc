package org.julclang.core;

import org.julclang.core.Constant.ValueConst;
import org.julclang.core.Constant.ValueConst.TokenEntry;
import org.julclang.core.Constant.ValueConst.ValueEntry;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pinned native Value semantics shared by the VM builtins and the ADR-045 literal fold:
 * canonical ordering, zero removal, the Int128 range, the 32-byte key limit, strict Data
 * decoding, and the exact failure text of every rejection.
 */
class NativeValueSemanticsTest {

    private static final byte[] A = {0x0a};
    private static final byte[] B = {0x0b};
    private static final byte[] C = {0x0c};
    private static final BigInteger MAX = NativeValueSemantics.MAX_QUANTITY;
    private static final BigInteger MIN = NativeValueSemantics.MIN_QUANTITY;

    static ValueConst single(byte[] policy, byte[] token, long quantity) {
        return single(policy, token, BigInteger.valueOf(quantity));
    }

    static ValueConst single(byte[] policy, byte[] token, BigInteger quantity) {
        return new ValueConst(List.of(new ValueEntry(policy, List.of(new TokenEntry(token, quantity)))));
    }

    static ValueConst insert(byte[] policy, byte[] token, long quantity, ValueConst into) {
        return NativeValueSemantics.insertCoin(policy, token, BigInteger.valueOf(quantity), into);
    }

    @Test
    void insertKeepsEntriesSortedReplacesAndRemovesOnZero() {
        var value = insert(A, A, 1, insert(B, B, 2, insert(A, C, 3, NativeValueSemantics.EMPTY)));
        assertEquals(new ValueConst(List.of(
                new ValueEntry(A, List.of(new TokenEntry(A, BigInteger.ONE), new TokenEntry(C, BigInteger.valueOf(3)))),
                new ValueEntry(B, List.of(new TokenEntry(B, BigInteger.TWO))))), value);
        assertEquals(BigInteger.valueOf(3), NativeValueSemantics.lookupCoin(A, C, value));
        assertEquals(BigInteger.valueOf(7), NativeValueSemantics.lookupCoin(A, C, insert(A, C, 7, value)));
        var removed = insert(B, B, 0, value);
        assertEquals(BigInteger.ZERO, NativeValueSemantics.lookupCoin(B, B, removed));
        assertEquals(1, removed.entries().size());
        assertEquals(NativeValueSemantics.EMPTY, insert(A, A, 0, insert(A, C, 0, removed)));
        assertEquals(NativeValueSemantics.EMPTY, insert(C, C, 0, NativeValueSemantics.EMPTY));
    }

    @Test
    void insertChecksKeysAndRangeOnlyForNonZeroQuantities() {
        var longKey = new byte[33];
        assertEquals("InsertCoin: policyId too long: 33 bytes (max 32)",
                assertThrows(NativeValueSemantics.EvaluationFailure.class,
                        () -> insert(longKey, A, 1, NativeValueSemantics.EMPTY)).getMessage());
        assertEquals("InsertCoin: tokenName too long: 33 bytes (max 32)",
                assertThrows(NativeValueSemantics.EvaluationFailure.class,
                        () -> insert(A, longKey, 1, NativeValueSemantics.EMPTY)).getMessage());
        assertEquals(NativeValueSemantics.EMPTY, insert(longKey, longKey, 0, NativeValueSemantics.EMPTY));
        assertEquals(1, insert(new byte[32], new byte[32], 1, NativeValueSemantics.EMPTY).entries().size());
        assertEquals(MAX, NativeValueSemantics.lookupCoin(A, A, NativeValueSemantics.insertCoin(A, A, MAX, NativeValueSemantics.EMPTY)));
        assertEquals(MIN, NativeValueSemantics.lookupCoin(A, A, NativeValueSemantics.insertCoin(A, A, MIN, NativeValueSemantics.EMPTY)));
        assertEquals("InsertCoin: quantity out of Int128 range: " + MAX.add(BigInteger.ONE),
                assertThrows(NativeValueSemantics.EvaluationFailure.class,
                        () -> NativeValueSemantics.insertCoin(A, A, MAX.add(BigInteger.ONE), NativeValueSemantics.EMPTY)).getMessage());
        assertEquals("InsertCoin: quantity out of Int128 range: " + MIN.subtract(BigInteger.ONE),
                assertThrows(NativeValueSemantics.EvaluationFailure.class,
                        () -> NativeValueSemantics.insertCoin(A, A, MIN.subtract(BigInteger.ONE), NativeValueSemantics.EMPTY)).getMessage());
    }

    @Test
    void lookupIsTotal() {
        var value = single(A, A, 5);
        assertEquals(BigInteger.valueOf(5), NativeValueSemantics.lookupCoin(A, A, value));
        assertEquals(BigInteger.ZERO, NativeValueSemantics.lookupCoin(A, B, value));
        assertEquals(BigInteger.ZERO, NativeValueSemantics.lookupCoin(B, A, value));
        assertEquals(BigInteger.ZERO, NativeValueSemantics.lookupCoin(new byte[40], new byte[40], value));
    }

    @Test
    void unionAddsCancelsAndChecksRange() {
        assertEquals(single(A, A, 7), NativeValueSemantics.unionValue(single(A, A, 5), single(A, A, 2)));
        assertEquals(NativeValueSemantics.EMPTY, NativeValueSemantics.unionValue(single(A, A, 5), single(A, A, -5)));
        assertEquals(new ValueConst(List.of(
                        new ValueEntry(A, List.of(new TokenEntry(A, BigInteger.ONE))),
                        new ValueEntry(B, List.of(new TokenEntry(B, BigInteger.TWO))))),
                NativeValueSemantics.unionValue(single(B, B, 2), single(A, A, 1)));
        assertEquals(single(A, A, -3), NativeValueSemantics.unionValue(single(A, A, -1), single(A, A, -2)));
        assertEquals("UnionValue: quantity out of Int128 range: " + MAX.add(BigInteger.ONE),
                assertThrows(NativeValueSemantics.EvaluationFailure.class,
                        () -> NativeValueSemantics.unionValue(single(A, A, MAX), single(A, A, 1))).getMessage());
        assertEquals(single(A, A, MAX), NativeValueSemantics.unionValue(single(A, A, MAX), NativeValueSemantics.EMPTY));
    }

    @Test
    void containsComparesEntryWiseAndRejectsNegatives() {
        assertTrue(NativeValueSemantics.valueContains(single(A, A, 5), single(A, A, 3)));
        assertTrue(NativeValueSemantics.valueContains(single(A, A, 5), single(A, A, 5)));
        assertFalse(NativeValueSemantics.valueContains(single(A, A, 3), single(A, A, 5)));
        assertFalse(NativeValueSemantics.valueContains(single(A, A, 3), single(B, A, 1)));
        assertTrue(NativeValueSemantics.valueContains(single(A, A, 3), NativeValueSemantics.EMPTY));
        assertTrue(NativeValueSemantics.valueContains(NativeValueSemantics.EMPTY, NativeValueSemantics.EMPTY));
        assertFalse(NativeValueSemantics.valueContains(NativeValueSemantics.EMPTY, single(A, A, 1)));
        assertEquals("ValueContains: negative quantity not allowed",
                assertThrows(NativeValueSemantics.EvaluationFailure.class,
                        () -> NativeValueSemantics.valueContains(single(A, A, -1), NativeValueSemantics.EMPTY)).getMessage());
        assertEquals("ValueContains: negative quantity not allowed",
                assertThrows(NativeValueSemantics.EvaluationFailure.class,
                        () -> NativeValueSemantics.valueContains(single(A, A, 9), single(A, A, -1))).getMessage());
    }

    @Test
    void scaleMultipliesZeroShortCircuitsAndChecksRange() {
        assertEquals(single(A, A, 12), NativeValueSemantics.scaleValue(BigInteger.valueOf(3), single(A, A, 4)));
        assertEquals(single(A, A, -4), NativeValueSemantics.scaleValue(BigInteger.valueOf(-1), single(A, A, 4)));
        assertEquals(NativeValueSemantics.EMPTY, NativeValueSemantics.scaleValue(BigInteger.ZERO, single(A, A, MAX)));
        assertEquals("ScaleValue: quantity out of Int128 range: " + MAX.multiply(BigInteger.TWO),
                assertThrows(NativeValueSemantics.EvaluationFailure.class,
                        () -> NativeValueSemantics.scaleValue(BigInteger.TWO, single(A, A, MAX))).getMessage());
    }

    @Test
    void valueDataIsCanonicalAndRoundTrips() {
        var value = insert(B, A, 2, insert(A, B, 3, insert(A, A, 1, NativeValueSemantics.EMPTY)));
        var data = NativeValueSemantics.valueData(value);
        assertEquals(PlutusData.map(
                new PlutusData.Pair(PlutusData.bytes(A), PlutusData.map(
                        new PlutusData.Pair(PlutusData.bytes(A), PlutusData.integer(1)),
                        new PlutusData.Pair(PlutusData.bytes(B), PlutusData.integer(3)))),
                new PlutusData.Pair(PlutusData.bytes(B), PlutusData.map(
                        new PlutusData.Pair(PlutusData.bytes(A), PlutusData.integer(2))))), data);
        assertEquals(value, NativeValueSemantics.unValueData(data));
        assertEquals(PlutusData.map(), NativeValueSemantics.valueData(NativeValueSemantics.EMPTY));
        assertEquals(NativeValueSemantics.EMPTY, NativeValueSemantics.unValueData(PlutusData.map()));
    }

    @Test
    void unValueDataRejectsEveryDeviationWithItsOwnText() {
        assertRejected("UnValueData: expected Map data, got IntData", PlutusData.integer(1));
        assertRejected("UnValueData: policyId must be BytesData",
                map(pair(PlutusData.integer(1), map(pair(PlutusData.bytes(A), PlutusData.integer(1))))));
        assertRejected("UnValueData: policyId too long: 33",
                map(pair(PlutusData.bytes(new byte[33]), map(pair(PlutusData.bytes(A), PlutusData.integer(1))))));
        assertRejected("UnValueData: currencies not strictly ordered or duplicate",
                map(pair(PlutusData.bytes(B), map(pair(PlutusData.bytes(A), PlutusData.integer(1)))),
                        pair(PlutusData.bytes(A), map(pair(PlutusData.bytes(A), PlutusData.integer(1))))));
        assertRejected("UnValueData: currencies not strictly ordered or duplicate",
                map(pair(PlutusData.bytes(A), map(pair(PlutusData.bytes(A), PlutusData.integer(1)))),
                        pair(PlutusData.bytes(A), map(pair(PlutusData.bytes(B), PlutusData.integer(1))))));
        assertRejected("UnValueData: token map must be MapData", map(pair(PlutusData.bytes(A), PlutusData.integer(1))));
        assertRejected("UnValueData: empty token map", map(pair(PlutusData.bytes(A), map())));
        assertRejected("UnValueData: tokenName must be BytesData",
                map(pair(PlutusData.bytes(A), map(pair(PlutusData.integer(1), PlutusData.integer(1))))));
        assertRejected("UnValueData: tokenName too long: 33",
                map(pair(PlutusData.bytes(A), map(pair(PlutusData.bytes(new byte[33]), PlutusData.integer(1))))));
        assertRejected("UnValueData: tokens not strictly ordered or duplicate",
                map(pair(PlutusData.bytes(A), map(pair(PlutusData.bytes(B), PlutusData.integer(1)), pair(PlutusData.bytes(A), PlutusData.integer(1))))));
        assertRejected("UnValueData: tokens not strictly ordered or duplicate",
                map(pair(PlutusData.bytes(A), map(pair(PlutusData.bytes(A), PlutusData.integer(1)), pair(PlutusData.bytes(A), PlutusData.integer(2))))));
        assertRejected("UnValueData: quantity must be IntData",
                map(pair(PlutusData.bytes(A), map(pair(PlutusData.bytes(A), PlutusData.bytes(A))))));
        assertRejected("UnValueData: zero quantity",
                map(pair(PlutusData.bytes(A), map(pair(PlutusData.bytes(A), PlutusData.integer(0))))));
        assertRejected("UnValueData: quantity out of Int128 range: " + MAX.add(BigInteger.ONE),
                map(pair(PlutusData.bytes(A), map(pair(PlutusData.bytes(A), PlutusData.integer(MAX.add(BigInteger.ONE)))))));
        // Nothing is normalised on the way in: a sorted, duplicate-free map with negatives decodes as is.
        assertEquals(single(A, A, -9), NativeValueSemantics.unValueData(
                map(pair(PlutusData.bytes(A), map(pair(PlutusData.bytes(A), PlutusData.integer(-9)))))));
    }

    private static void assertRejected(String message, PlutusData data) {
        assertEquals(message, assertThrows(NativeValueSemantics.EvaluationFailure.class,
                () -> NativeValueSemantics.unValueData(data)).getMessage());
    }

    private static PlutusData map(PlutusData.Pair... entries) { return PlutusData.map(entries); }

    private static PlutusData.Pair pair(PlutusData key, PlutusData value) { return new PlutusData.Pair(key, value); }
}
