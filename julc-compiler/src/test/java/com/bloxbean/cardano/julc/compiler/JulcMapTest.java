package com.bloxbean.cardano.julc.compiler;

import com.bloxbean.cardano.julc.compiler.fixtures.ByteMapOps;
import com.bloxbean.cardano.julc.compiler.fixtures.ConstrMapOps;
import com.bloxbean.cardano.julc.compiler.fixtures.IntMapOps;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.testkit.JulcEval;
import org.junit.jupiter.api.Nested;
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

    @Nested
    class ForEachMapGet {

        static final String SOURCE = """
                import com.bloxbean.cardano.julc.core.PlutusData;
                import com.bloxbean.cardano.julc.core.types.JulcMap;
                import java.math.BigInteger;

                class ForEachGetOps {
                    @SuppressWarnings("unchecked")
                    static long sumViaGet(PlutusData m) {
                        JulcMap<PlutusData, BigInteger> map = (JulcMap)(Object) m;
                        BigInteger total = 0;
                        for (var entry : map) {
                            PlutusData key = entry.key();
                            total = total + map.get(key);
                        }
                        return total;
                    }

                    @SuppressWarnings("unchecked")
                    static long sumViaDirect(PlutusData m) {
                        JulcMap<PlutusData, BigInteger> map = (JulcMap)(Object) m;
                        BigInteger total = 0;
                        for (var entry : map) {
                            total = total + entry.value();
                        }
                        return total;
                    }

                    @SuppressWarnings("unchecked")
                    static boolean allKeysPresent(PlutusData m) {
                        JulcMap<PlutusData, BigInteger> map = (JulcMap)(Object) m;
                        boolean allFound = true;
                        for (var entry : map) {
                            boolean found = map.containsKey(entry.key());
                            allFound = allFound && found;
                        }
                        return allFound;
                    }

                    @SuppressWarnings("unchecked")
                    static long crossMapLookup(PlutusData mPrices, PlutusData mQuantities) {
                        JulcMap<PlutusData, BigInteger> prices = (JulcMap)(Object) mPrices;
                        JulcMap<PlutusData, BigInteger> quantities = (JulcMap)(Object) mQuantities;
                        BigInteger total = 0;
                        for (var entry : prices) {
                            BigInteger price = entry.value();
                            BigInteger qty = quantities.get(entry.key());
                            total = total + price * qty;
                        }
                        return total;
                    }

                }
                """;

        interface ForEachGetProxy {
            long sumViaGet(PlutusData m);
            long sumViaDirect(PlutusData m);
            boolean allKeysPresent(PlutusData m);
            long crossMapLookup(PlutusData mPrices, PlutusData mQuantities);
        }

        final ForEachGetProxy proxy = JulcEval
                .forSource(SOURCE)
                .create(ForEachGetProxy.class);

        @Test
        void sumViaGet_twoEntries() {
            assertEquals(142, proxy.sumViaGet(intMap(1, 42, 2, 100)));
        }

        @Test
        void sumViaGet_threeEntries() {
            assertEquals(60, proxy.sumViaGet(intMap3(1, 10, 2, 20, 3, 30)));
        }

        @Test
        void sumViaDirect_twoEntries() {
            assertEquals(142, proxy.sumViaDirect(intMap(1, 42, 2, 100)));
        }

        @Test
        void allKeysPresent() {
            assertTrue(proxy.allKeysPresent(intMap(1, 42, 2, 100)));
        }

        @Test
        void crossMapLookup() {
            PlutusData prices = intMap(1, 10, 2, 20);
            PlutusData quantities = intMap(1, 3, 2, 5);
            assertEquals(130, proxy.crossMapLookup(prices, quantities));
        }

        @Test
        void sumViaGet_emptyMap() {
            assertEquals(0, proxy.sumViaGet(emptyMap()));
        }

        @Test
        void sumViaGet_singleEntry() {
            assertEquals(99, proxy.sumViaGet(singleIntMap(7, 99)));
        }
    }

    // =========================================================================
    // While-loop using instance methods (head/tail/isEmpty)
    // =========================================================================

    @Nested
    class WhileLoopMapGet {

        static final String SOURCE = """
                import com.bloxbean.cardano.julc.core.PlutusData;
                import com.bloxbean.cardano.julc.core.types.JulcMap;
                import java.math.BigInteger;

                class WhileLoopGetOps {
                    @SuppressWarnings("unchecked")
                    static long sumViaGet(PlutusData m) {
                        JulcMap<PlutusData, BigInteger> map = (JulcMap)(Object) m;
                        var cursor = map;
                        BigInteger total = 0;
                        while (!cursor.isEmpty()) {
                            var entry = cursor.head();
                            PlutusData key = entry.key();
                            total = total + map.get(key);
                            cursor = cursor.tail();
                        }
                        return total;
                    }

                    @SuppressWarnings("unchecked")
                    static long sumViaDirect(PlutusData m) {
                        JulcMap<PlutusData, BigInteger> map = (JulcMap)(Object) m;
                        var cursor = map;
                        BigInteger total = 0;
                        while (!cursor.isEmpty()) {
                            var entry = cursor.head();
                            total = total + entry.value();
                            cursor = cursor.tail();
                        }
                        return total;
                    }

                    @SuppressWarnings("unchecked")
                    static boolean allKeysPresent(PlutusData m) {
                        JulcMap<PlutusData, BigInteger> map = (JulcMap)(Object) m;
                        var cursor = map;
                        boolean allFound = true;
                        while (!cursor.isEmpty()) {
                            var entry = cursor.head();
                            boolean found = map.containsKey(entry.key());
                            allFound = allFound && found;
                            cursor = cursor.tail();
                        }
                        return allFound;
                    }

                    @SuppressWarnings("unchecked")
                    static long crossMapLookup(PlutusData mPrices, PlutusData mQuantities) {
                        JulcMap<PlutusData, BigInteger> prices = (JulcMap)(Object) mPrices;
                        JulcMap<PlutusData, BigInteger> quantities = (JulcMap)(Object) mQuantities;
                        var cursor = prices;
                        BigInteger total = 0;
                        while (!cursor.isEmpty()) {
                            var entry = cursor.head();
                            BigInteger price = entry.value();
                            BigInteger qty = quantities.get(entry.key());
                            total = total + price * qty;
                            cursor = cursor.tail();
                        }
                        return total;
                    }
                }
                """;

        interface WhileLoopGetProxy {
            long sumViaGet(PlutusData m);
            long sumViaDirect(PlutusData m);
            boolean allKeysPresent(PlutusData m);
            long crossMapLookup(PlutusData mPrices, PlutusData mQuantities);
        }

        final WhileLoopGetProxy proxy = JulcEval
                .forSource(SOURCE)
                .create(WhileLoopGetProxy.class);

        @Test
        void sumViaGet_twoEntries() {
            assertEquals(142, proxy.sumViaGet(intMap(1, 42, 2, 100)));
        }

        @Test
        void sumViaGet_threeEntries() {
            assertEquals(60, proxy.sumViaGet(intMap3(1, 10, 2, 20, 3, 30)));
        }

        @Test
        void sumViaDirect_twoEntries() {
            assertEquals(142, proxy.sumViaDirect(intMap(1, 42, 2, 100)));
        }

        @Test
        void allKeysPresent() {
            assertTrue(proxy.allKeysPresent(intMap(1, 42, 2, 100)));
        }

        @Test
        void crossMapLookup() {
            PlutusData prices = intMap(1, 10, 2, 20);
            PlutusData quantities = intMap(1, 3, 2, 5);
            assertEquals(130, proxy.crossMapLookup(prices, quantities));
        }

        @Test
        void sumViaGet_emptyMap() {
            assertEquals(0, proxy.sumViaGet(emptyMap()));
        }

        @Test
        void sumViaGet_singleEntry() {
            assertEquals(99, proxy.sumViaGet(singleIntMap(7, 99)));
        }
    }

    // =========================================================================
    // While-loop multi-accumulator patterns
    // =========================================================================

    @Nested
    class WhileLoopMultiAcc {

        static final String SOURCE = """
                import com.bloxbean.cardano.julc.core.PlutusData;
                import com.bloxbean.cardano.julc.core.types.JulcMap;
                import com.bloxbean.cardano.julc.stdlib.Builtins;
                import java.math.BigInteger;

                class WhileLoopMultiAccOps {
                    @SuppressWarnings("unchecked")
                    static boolean findKey(PlutusData m, PlutusData target) {
                        JulcMap<PlutusData, BigInteger> map = (JulcMap)(Object) m;
                        var cursor = map;
                        boolean found = false;
                        while (!cursor.isEmpty()) {
                            var entry = cursor.head();
                            PlutusData key = entry.key();
                            if (Builtins.equalsData(key, target)) {
                                found = true;
                            }
                            cursor = cursor.tail();
                        }
                        return found;
                    }

                    @SuppressWarnings("unchecked")
                    static long countAndSum(PlutusData m) {
                        JulcMap<PlutusData, BigInteger> map = (JulcMap)(Object) m;
                        var cursor = map;
                        BigInteger count = 0;
                        BigInteger sum = 0;
                        while (!cursor.isEmpty()) {
                            var entry = cursor.head();
                            count = count + 1;
                            sum = sum + entry.value();
                            cursor = cursor.tail();
                        }
                        return count * 1000 + sum;
                    }

                    @SuppressWarnings("unchecked")
                    static long countEven(PlutusData m) {
                        JulcMap<PlutusData, BigInteger> map = (JulcMap)(Object) m;
                        var cursor = map;
                        BigInteger count = 0;
                        while (!cursor.isEmpty()) {
                            var entry = cursor.head();
                            BigInteger val = entry.value();
                            if (val % 2 == 0) {
                                count = count + 1;
                            }
                            cursor = cursor.tail();
                        }
                        return count;
                    }

                    @SuppressWarnings("unchecked")
                    static long countAboveThreshold(PlutusData m, PlutusData threshold) {
                        JulcMap<PlutusData, BigInteger> map = (JulcMap)(Object) m;
                        BigInteger thresh = Builtins.unIData(threshold);
                        var cursor = map;
                        BigInteger count = 0;
                        while (!cursor.isEmpty()) {
                            var entry = cursor.head();
                            BigInteger val = entry.value();
                            if (val >= thresh) {
                                count = count + 1;
                            }
                            cursor = cursor.tail();
                        }
                        return count;
                    }
                }
                """;

        interface MultiAccProxy {
            boolean findKey(PlutusData m, PlutusData target);
            long countAndSum(PlutusData m);
            long countEven(PlutusData m);
            long countAboveThreshold(PlutusData m, PlutusData threshold);
        }

        final MultiAccProxy proxy = JulcEval
                .forSource(SOURCE)
                .create(MultiAccProxy.class);

        @Test
        void booleanGuard_findKey() {
            assertTrue(proxy.findKey(intMap(1, 42, 2, 100), PlutusData.integer(2)));
        }

        @Test
        void booleanGuard_findKeyMissing() {
            assertFalse(proxy.findKey(intMap(1, 42, 2, 100), PlutusData.integer(99)));
        }

        @Test
        void countAndSum() {
            // count=2, sum=142 → 2*1000 + 142 = 2142
            assertEquals(2142, proxy.countAndSum(intMap(1, 42, 2, 100)));
        }

        @Test
        void countEvenValues() {
            // {1:3, 2:4, 3:5, 4:6} → even values at keys 2,4 → count 2
            assertEquals(2, proxy.countEven(intMap4(1, 3, 2, 4, 3, 5, 4, 6)));
        }

        @Test
        void countAboveThreshold() {
            // {1:10, 2:100, 3:5} with threshold=50 → only key 2 → count 1
            assertEquals(1, proxy.countAboveThreshold(intMap3(1, 10, 2, 100, 3, 5), PlutusData.integer(50)));
        }
    }

    // =========================================================================
    // Nested while-loop patterns
    // =========================================================================

    @Nested
    class WhileLoopNestedMap {

        static final String SOURCE = """
                import com.bloxbean.cardano.julc.core.PlutusData;
                import com.bloxbean.cardano.julc.core.types.JulcMap;
                import com.bloxbean.cardano.julc.stdlib.Builtins;
                import java.math.BigInteger;

                class WhileLoopNestedOps {
                    @SuppressWarnings("unchecked")
                    static long cartesianSum(PlutusData m1, PlutusData m2) {
                        JulcMap<PlutusData, BigInteger> map1 = (JulcMap)(Object) m1;
                        JulcMap<PlutusData, BigInteger> map2 = (JulcMap)(Object) m2;
                        var outer = map1;
                        BigInteger total = 0;
                        while (!outer.isEmpty()) {
                            var e1 = outer.head();
                            BigInteger v1 = e1.value();
                            var inner = map2;
                            while (!inner.isEmpty()) {
                                var e2 = inner.head();
                                BigInteger v2 = e2.value();
                                total = total + v1 * v2;
                                inner = inner.tail();
                            }
                            outer = outer.tail();
                        }
                        return total;
                    }

                    @SuppressWarnings("unchecked")
                    static long sumMatchingKeys(PlutusData m, PlutusData keyList) {
                        JulcMap<PlutusData, BigInteger> map = (JulcMap)(Object) m;
                        var cursor = map;
                        BigInteger total = 0;
                        while (!cursor.isEmpty()) {
                            var entry = cursor.head();
                            PlutusData key = entry.key();
                            var keys = Builtins.unListData(keyList);
                            boolean matched = false;
                            while (!Builtins.nullList(keys)) {
                                PlutusData k = Builtins.headList(keys);
                                if (Builtins.equalsData(k, key)) {
                                    matched = true;
                                }
                                keys = Builtins.tailList(keys);
                            }
                            if (matched) {
                                total = total + entry.value();
                            }
                            cursor = cursor.tail();
                        }
                        return total;
                    }
                }
                """;

        interface NestedProxy {
            long cartesianSum(PlutusData m1, PlutusData m2);
            long sumMatchingKeys(PlutusData m, PlutusData keyList);
        }

        final NestedProxy proxy = JulcEval
                .forSource(SOURCE)
                .create(NestedProxy.class);

        @Test
        void nestedMapIteration() {
            // {1:2, 3:4} × {5:6, 7:8} → 2*6 + 2*8 + 4*6 + 4*8 = 12+16+24+32 = 84
            assertEquals(84, proxy.cartesianSum(intMap(1, 2, 3, 4), intMap(5, 6, 7, 8)));
        }

        @Test
        void mapIterWithInnerListWhile() {
            // map={1:10, 2:20, 3:30}, keys=[1,3] → sum values where key in list: 10+30 = 40
            PlutusData keyList = PlutusData.list(PlutusData.integer(1), PlutusData.integer(3));
            assertEquals(40, proxy.sumMatchingKeys(intMap3(1, 10, 2, 20, 3, 30), keyList));
        }
    }

    // =========================================================================
    // Edge cases
    // =========================================================================

    @Nested
    class WhileLoopMapEdgeCases {

        static final String SOURCE = """
                import com.bloxbean.cardano.julc.core.PlutusData;
                import com.bloxbean.cardano.julc.core.types.JulcMap;
                import java.math.BigInteger;

                class WhileLoopEdgeOps {
                    @SuppressWarnings("unchecked")
                    static long sumViaGet(PlutusData m) {
                        JulcMap<PlutusData, BigInteger> map = (JulcMap)(Object) m;
                        var cursor = map;
                        BigInteger total = 0;
                        while (!cursor.isEmpty()) {
                            var entry = cursor.head();
                            PlutusData key = entry.key();
                            total = total + map.get(key);
                            cursor = cursor.tail();
                        }
                        return total;
                    }

                    @SuppressWarnings("unchecked")
                    static long tailSize(PlutusData m) {
                        JulcMap<PlutusData, BigInteger> map = (JulcMap)(Object) m;
                        var rest = map.tail();
                        return rest.size();
                    }

                    @SuppressWarnings("unchecked")
                    static long headTailConsistent(PlutusData m) {
                        JulcMap<PlutusData, BigInteger> map = (JulcMap)(Object) m;
                        BigInteger first = map.head().value();
                        BigInteger second = map.tail().head().value();
                        return first + second;
                    }
                }
                """;

        interface EdgeProxy {
            long sumViaGet(PlutusData m);
            long tailSize(PlutusData m);
            long headTailConsistent(PlutusData m);
        }

        final EdgeProxy proxy = JulcEval
                .forSource(SOURCE)
                .create(EdgeProxy.class);

        @Test
        void singleEntryIteration() {
            assertEquals(99, proxy.sumViaGet(singleIntMap(7, 99)));
        }

        @Test
        void emptyMapSkipsLoop() {
            assertEquals(0, proxy.sumViaGet(emptyMap()));
        }

        @Test
        void largeMap() {
            // 5-entry map: 10+20+30+40+50 = 150
            assertEquals(150, proxy.sumViaGet(intMap5(1, 10, 2, 20, 3, 30, 4, 40, 5, 50)));
        }

        @Test
        void tailPreservesMapType() {
            // 2-entry map → tail → size == 1
            assertEquals(1, proxy.tailSize(intMap(1, 42, 2, 100)));
        }

        @Test
        void headThenTailConsistency() {
            // {1:42, 2:100} → head().value() + tail().head().value() == 142
            assertEquals(142, proxy.headTailConsistent(intMap(1, 42, 2, 100)));
        }
    }

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
