package org.julclang.compiler;

import org.julclang.compiler.fixtures.ByteMapOps;
import org.julclang.compiler.fixtures.ConstrMapOps;
import org.julclang.compiler.fixtures.IntMapOps;
import org.julclang.core.PlutusData;
import org.julclang.testkit.JulcEval;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for JulcMap/Map instance methods across different value types.
 * Uses file-based fixtures with JulcEval proxy interfaces for type-safe evaluation.
 */
class JulcMapTest {

    static final Path TEST_SOURCE_ROOT = Path.of("src/test/java");

    // --- Proxy interfaces ---

    interface IntMapProxy {
        BigInteger get(PlutusData m, PlutusData key);
        boolean lookupPresent(PlutusData m, PlutusData key);
        boolean lookupMissing(PlutusData m, PlutusData key);
        boolean containsKey(PlutusData m, PlutusData key);
        long size(PlutusData m);
        boolean isEmpty(PlutusData m);
        long keys(PlutusData m);
        long values(PlutusData m);
        long insertThenSize(PlutusData m, PlutusData key, PlutusData val);
        long deleteThenSize(PlutusData m, PlutusData key);
        BigInteger getWithIntKey(PlutusData m);
        boolean containsKeyWithIntKey(PlutusData m);
    }

    interface ByteMapProxy {
        byte[] get(PlutusData m, PlutusData key);
        boolean lookupPresent(PlutusData m, PlutusData key);
        boolean lookupMissing(PlutusData m, PlutusData key);
        boolean containsKey(PlutusData m, PlutusData key);
        long size(PlutusData m);
        long insertThenSize(PlutusData m, PlutusData key, PlutusData val);
        long deleteThenSize(PlutusData m, PlutusData key);
    }

    interface ConstrMapProxy {
        PlutusData get(PlutusData m, PlutusData key);
        boolean lookupPresent(PlutusData m, PlutusData key);
        boolean lookupMissing(PlutusData m, PlutusData key);
        boolean containsKey(PlutusData m, PlutusData key);
        long size(PlutusData m);
        long insertThenSize(PlutusData m, PlutusData key, PlutusData val);
        long deleteThenSize(PlutusData m, PlutusData key);
    }

    // --- Helpers ---

    static PlutusData intMap(long k1, long v1, long k2, long v2) {
        return PlutusData.map(
                new PlutusData.Pair(PlutusData.integer(k1), PlutusData.integer(v1)),
                new PlutusData.Pair(PlutusData.integer(k2), PlutusData.integer(v2)));
    }

    static PlutusData emptyMap() {
        return PlutusData.map();
    }

    static PlutusData byteMap() {
        return PlutusData.map(
                new PlutusData.Pair(PlutusData.bytes(new byte[]{0x0a}), PlutusData.bytes(new byte[]{1, 2})),
                new PlutusData.Pair(PlutusData.bytes(new byte[]{0x0b}), PlutusData.bytes(new byte[]{3, 4})));
    }

    static PlutusData constrMap() {
        return PlutusData.map(
                new PlutusData.Pair(PlutusData.integer(1), PlutusData.constr(0, PlutusData.integer(10))),
                new PlutusData.Pair(PlutusData.integer(2), PlutusData.constr(0, PlutusData.integer(20))));
    }

    // =========================================================================
    // Integer value map tests
    // =========================================================================

    @Nested
    class IntegerValueMap {

        final IntMapProxy proxy = JulcEval
                .forClass(IntMapOps.class, TEST_SOURCE_ROOT)
                .create(IntMapProxy.class);

        @Test
        void getFirstEntry() {
            assertEquals(BigInteger.valueOf(42),
                    proxy.get(intMap(1, 42, 2, 100), PlutusData.integer(1)));
        }

        @Test
        void getSecondEntry() {
            assertEquals(BigInteger.valueOf(100),
                    proxy.get(intMap(1, 42, 2, 100), PlutusData.integer(2)));
        }

        @Test
        void getMissingKeyCrashes() {
            assertThrows(Exception.class,
                    () -> proxy.get(intMap(1, 42, 2, 100), PlutusData.integer(99)));
        }

        @Test
        void getWithIntKey() {
            assertEquals(BigInteger.valueOf(42),
                    proxy.getWithIntKey(intMap(1, 42, 2, 100)));
        }

        @Test
        void lookupPresentReturnsTrue() {
            assertTrue(proxy.lookupPresent(intMap(1, 42, 2, 100), PlutusData.integer(1)));
        }

        @Test
        void lookupMissingReturnsTrue() {
            assertTrue(proxy.lookupMissing(intMap(1, 42, 2, 100), PlutusData.integer(99)));
        }

        @Test
        void containsKeyFound() {
            assertTrue(proxy.containsKey(intMap(1, 42, 2, 100), PlutusData.integer(2)));
        }

        @Test
        void containsKeyMissing() {
            assertFalse(proxy.containsKey(intMap(1, 42, 2, 100), PlutusData.integer(99)));
        }

        @Test
        void containsKeyWithIntKey() {
            assertTrue(proxy.containsKeyWithIntKey(intMap(1, 42, 2, 100)));
        }

        @Test
        void size() {
            assertEquals(2, proxy.size(intMap(1, 42, 2, 100)));
        }

        @Test
        void sizeEmpty() {
            assertEquals(0, proxy.size(emptyMap()));
        }

        @Test
        void isEmptyTrue() {
            assertTrue(proxy.isEmpty(emptyMap()));
        }

        @Test
        void isEmptyFalse() {
            assertFalse(proxy.isEmpty(intMap(1, 42, 2, 100)));
        }

        @Test
        void keysCount() {
            assertEquals(2, proxy.keys(intMap(1, 42, 2, 100)));
        }

        @Test
        void valuesCount() {
            assertEquals(2, proxy.values(intMap(1, 42, 2, 100)));
        }

        @Test
        void insertThenSize() {
            assertEquals(3, proxy.insertThenSize(
                    intMap(1, 42, 2, 100),
                    PlutusData.integer(3), PlutusData.integer(300)));
        }

        @Test
        void deleteThenSize() {
            assertEquals(1, proxy.deleteThenSize(
                    intMap(1, 42, 2, 100), PlutusData.integer(1)));
        }
    }

    // =========================================================================
    // ByteString value map tests
    // =========================================================================

    @Nested
    class ByteStringValueMap {

        final ByteMapProxy proxy = JulcEval
                .forClass(ByteMapOps.class, TEST_SOURCE_ROOT)
                .create(ByteMapProxy.class);

        @Test
        void getFound() {
            assertArrayEquals(new byte[]{1, 2},
                    proxy.get(byteMap(), PlutusData.bytes(new byte[]{0x0a})));
        }

        @Test
        void getMissingKeyCrashes() {
            assertThrows(Exception.class,
                    () -> proxy.get(byteMap(), PlutusData.bytes(new byte[]{(byte) 0xff})));
        }

        @Test
        void lookupPresentReturnsTrue() {
            assertTrue(proxy.lookupPresent(byteMap(), PlutusData.bytes(new byte[]{0x0a})));
        }

        @Test
        void lookupMissingReturnsTrue() {
            assertTrue(proxy.lookupMissing(byteMap(), PlutusData.bytes(new byte[]{(byte) 0xff})));
        }

        @Test
        void containsKeyFound() {
            assertTrue(proxy.containsKey(byteMap(), PlutusData.bytes(new byte[]{0x0a})));
        }

        @Test
        void size() {
            assertEquals(2, proxy.size(byteMap()));
        }

        @Test
        void insertThenSize() {
            assertEquals(3, proxy.insertThenSize(
                    byteMap(),
                    PlutusData.bytes(new byte[]{0x0c}), PlutusData.bytes(new byte[]{5, 6})));
        }

        @Test
        void deleteThenSize() {
            assertEquals(1, proxy.deleteThenSize(
                    byteMap(), PlutusData.bytes(new byte[]{0x0a})));
        }
    }

    // =========================================================================
    // ConstrData value map tests
    // =========================================================================

    @Nested
    class ConstrValueMap {

        final ConstrMapProxy proxy = JulcEval
                .forClass(ConstrMapOps.class, TEST_SOURCE_ROOT)
                .create(ConstrMapProxy.class);

        @Test
        void getFound() {
            PlutusData result = proxy.get(constrMap(), PlutusData.integer(1));
            assertEquals(PlutusData.constr(0, PlutusData.integer(10)), result);
        }

        @Test
        void getMissingKeyCrashes() {
            assertThrows(Exception.class,
                    () -> proxy.get(constrMap(), PlutusData.integer(99)));
        }

        @Test
        void lookupPresentReturnsTrue() {
            assertTrue(proxy.lookupPresent(constrMap(), PlutusData.integer(1)));
        }

        @Test
        void lookupMissingReturnsTrue() {
            assertTrue(proxy.lookupMissing(constrMap(), PlutusData.integer(99)));
        }

        @Test
        void containsKeyFound() {
            assertTrue(proxy.containsKey(constrMap(), PlutusData.integer(2)));
        }

        @Test
        void size() {
            assertEquals(2, proxy.size(constrMap()));
        }

        @Test
        void insertThenSize() {
            assertEquals(3, proxy.insertThenSize(
                    constrMap(),
                    PlutusData.integer(3), PlutusData.constr(0, PlutusData.integer(30))));
        }

        @Test
        void deleteThenSize() {
            assertEquals(1, proxy.deleteThenSize(
                    constrMap(), PlutusData.integer(1)));
        }
    }

    // =========================================================================
    // For-each + map.get(loop key) tests
    // =========================================================================

    // =========================================================================
    // Java's forms of a map loop: keys() and values(), under the enforcing javac gate
    // =========================================================================

    @Nested
    class ForEachOverKeysAndValues {

        static final String SOURCE = """
                import org.julclang.core.PlutusData;
                import org.julclang.core.types.JulcMap;
                import java.math.BigInteger;

                class KeysAndValuesOps {
                    @SuppressWarnings("unchecked")
                    static long sumValues(PlutusData m) {
                        JulcMap<BigInteger, BigInteger> map = (JulcMap<BigInteger, BigInteger>)(Object) m;
                        BigInteger total = BigInteger.ZERO;
                        for (BigInteger value : map.values()) {
                            total = total.add(value);
                        }
                        return total.longValue();
                    }

                    @SuppressWarnings("unchecked")
                    static long sumKeys(PlutusData m) {
                        JulcMap<BigInteger, BigInteger> map = (JulcMap<BigInteger, BigInteger>)(Object) m;
                        BigInteger total = BigInteger.ZERO;
                        for (BigInteger key : map.keys()) {
                            total = total.add(key);
                        }
                        return total.longValue();
                    }

                    @SuppressWarnings("unchecked")
                    static long sumThroughKeys(PlutusData m) {
                        JulcMap<BigInteger, BigInteger> map = (JulcMap<BigInteger, BigInteger>)(Object) m;
                        BigInteger total = BigInteger.ZERO;
                        for (BigInteger key : map.keys()) {
                            total = total.add(map.get(key));
                        }
                        return total.longValue();
                    }
                }
                """;

        interface KeysAndValuesProxy {
            long sumValues(PlutusData m);
            long sumKeys(PlutusData m);
            long sumThroughKeys(PlutusData m);
        }

        final KeysAndValuesProxy proxy = JulcEval.forSource(SOURCE).create(KeysAndValuesProxy.class);

        @Test
        void valuesAndKeysIterate() {
            assertEquals(142, proxy.sumValues(intMap(1, 42, 2, 100)));
            assertEquals(3, proxy.sumKeys(intMap(1, 42, 2, 100)));
            assertEquals(142, proxy.sumThroughKeys(intMap(1, 42, 2, 100)));
        }

        @Test
        void anEmptyMapIteratesNothing() {
            assertEquals(0, proxy.sumValues(emptyMap()));
            assertEquals(0, proxy.sumKeys(emptyMap()));
            assertEquals(0, proxy.sumThroughKeys(emptyMap()));
        }
    }

    // =========================================================================
    // While-loop using instance methods (head/tail/isEmpty)
    // =========================================================================

    // =========================================================================
    // While-loop multi-accumulator patterns
    // =========================================================================

    // =========================================================================
    // Nested while-loop patterns
    // =========================================================================

    // =========================================================================
    // Edge cases
    // =========================================================================

    // --- Additional helpers ---

    static PlutusData intMap3(long k1, long v1, long k2, long v2, long k3, long v3) {
        return PlutusData.map(
                new PlutusData.Pair(PlutusData.integer(k1), PlutusData.integer(v1)),
                new PlutusData.Pair(PlutusData.integer(k2), PlutusData.integer(v2)),
                new PlutusData.Pair(PlutusData.integer(k3), PlutusData.integer(v3)));
    }

    static PlutusData intMap4(long k1, long v1, long k2, long v2, long k3, long v3, long k4, long v4) {
        return PlutusData.map(
                new PlutusData.Pair(PlutusData.integer(k1), PlutusData.integer(v1)),
                new PlutusData.Pair(PlutusData.integer(k2), PlutusData.integer(v2)),
                new PlutusData.Pair(PlutusData.integer(k3), PlutusData.integer(v3)),
                new PlutusData.Pair(PlutusData.integer(k4), PlutusData.integer(v4)));
    }

    static PlutusData intMap5(long k1, long v1, long k2, long v2, long k3, long v3,
                              long k4, long v4, long k5, long v5) {
        return PlutusData.map(
                new PlutusData.Pair(PlutusData.integer(k1), PlutusData.integer(v1)),
                new PlutusData.Pair(PlutusData.integer(k2), PlutusData.integer(v2)),
                new PlutusData.Pair(PlutusData.integer(k3), PlutusData.integer(v3)),
                new PlutusData.Pair(PlutusData.integer(k4), PlutusData.integer(v4)),
                new PlutusData.Pair(PlutusData.integer(k5), PlutusData.integer(v5)));
    }

    static PlutusData singleIntMap(long k, long v) {
        return PlutusData.map(
                new PlutusData.Pair(PlutusData.integer(k), PlutusData.integer(v)));
    }
}
