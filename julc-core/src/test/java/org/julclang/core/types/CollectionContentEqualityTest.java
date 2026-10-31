package org.julclang.core.types;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-068 D6: JuLC's off-chain collections compare {@code byte[]} elements, keys and values by content, as
 * on-chain (Data equality), and keep their prepend semantics. Each pair below is two distinct but equal arrays.
 */
class CollectionContentEqualityTest {

    private static byte[] bytes(int... values) {
        var result = new byte[values.length];
        for (int i = 0; i < values.length; i++) result[i] = (byte) values[i];
        return result;
    }

    @Nested
    class Lists {

        @Test
        void containsComparesByteArraysByContent() {
            var list = JulcList.of(bytes(1, 2), bytes(3));
            assertTrue(list.contains(bytes(1, 2)));
            assertTrue(list.contains(bytes(3)));
            assertFalse(list.contains(bytes(1)));
            assertFalse(list.contains(bytes(1, 2, 3)));
            assertFalse(list.contains(bytes()));
        }

        @Test
        void containsOnEmptyAndWithEmptyArray() {
            assertFalse(JulcList.<byte[]>empty().contains(bytes()));
            assertTrue(JulcList.of(bytes()).contains(bytes()));
        }

        @Test
        void containsOtherElementsUnchanged() {
            var list = JulcList.of(BigInteger.ONE, BigInteger.TWO);
            assertTrue(list.contains(BigInteger.valueOf(2)));
            assertFalse(list.contains(BigInteger.TEN));
        }

        @Test
        void containsNestedListsDeeply() {
            JulcList<JulcList<byte[]>> nested = JulcList.of(JulcList.of(bytes(1)), JulcList.of(bytes(2), bytes(3)));
            assertTrue(nested.contains(JulcList.of(bytes(2), bytes(3))));
            assertFalse(nested.contains(JulcList.of(bytes(3), bytes(2))));
        }

        @Test
        void equalsAndHashCodeByContent() {
            var a = JulcList.of(bytes(1, 2), bytes(3));
            var b = JulcList.of(bytes(1, 2), bytes(3));
            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
            assertNotEquals(a, JulcList.of(bytes(1, 2), bytes(4)));
            assertNotEquals(a, JulcList.of(bytes(1, 2)));
            assertNotEquals(a, JulcList.of(bytes(3), bytes(1, 2)));
        }

        @Test
        void nestedListEqualityIsDeep() {
            JulcList<JulcList<byte[]>> a = JulcList.of(JulcList.of(bytes(1)), JulcList.of(bytes(2)));
            JulcList<JulcList<byte[]>> b = JulcList.of(JulcList.of(bytes(1)), JulcList.of(bytes(2)));
            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
        }

        @Test
        void duplicatesAreKept() {
            var list = JulcList.of(bytes(1), bytes(1));
            assertEquals(2, list.size());
            assertTrue(list.contains(bytes(1)));
            assertNotEquals(list, JulcList.of(bytes(1)));
        }

        @Test
        void hashSetDeduplicatesEqualLists() {
            var set = new HashSet<JulcList<byte[]>>();
            set.add(JulcList.of(bytes(1, 2)));
            set.add(JulcList.of(bytes(1, 2)));
            set.add(JulcList.of(bytes(2, 1)));
            assertEquals(2, set.size());
            assertTrue(set.contains(JulcList.of(bytes(1, 2))));
        }

        @Test
        void arraysAreHeldNotCopied() {
            var element = bytes(1, 2);
            var list = JulcList.of(element);
            int before = list.hashCode();
            element[0] = 9;
            assertTrue(list.contains(bytes(9, 2)));
            assertFalse(list.contains(bytes(1, 2)));
            assertNotEquals(before, list.hashCode());
            assertEquals(JulcList.of(bytes(9, 2)).hashCode(), list.hashCode());
        }

        @Test
        void derivedListsKeepContentEquality() {
            var list = JulcList.of(bytes(1), bytes(2), bytes(3));
            assertTrue(list.tail().contains(bytes(3)));
            assertTrue(list.prepend(bytes(0)).contains(bytes(0)));
            assertEquals(JulcList.of(bytes(3), bytes(2), bytes(1)), list.reverse());
            assertEquals(JulcList.of(bytes(2)), list.drop(1).take(1));
        }
    }

    @Nested
    class Maps {

        @Test
        void lookupOperationsCompareKeysByContent() {
            JulcMap<byte[], BigInteger> map = JulcMap.of(bytes(1), BigInteger.ONE, bytes(2), BigInteger.TWO);
            assertEquals(BigInteger.ONE, map.get(bytes(1)));
            assertEquals(Optional.of(BigInteger.TWO), map.lookup(bytes(2)));
            assertEquals(BigInteger.TWO, map.getOrDefault(bytes(2), BigInteger.TEN));
            assertEquals(BigInteger.TEN, map.getOrDefault(bytes(3), BigInteger.TEN));
            assertTrue(map.containsKey(bytes(1)));
            assertFalse(map.containsKey(bytes(1, 0)));
            assertNull(map.get(bytes(3)));
            assertEquals(Optional.empty(), map.lookup(bytes()));
        }

        @Test
        void deleteRemovesEveryEqualKey() {
            JulcMap<byte[], BigInteger> map = JulcMap.of(bytes(1), BigInteger.ONE, bytes(2), BigInteger.TWO)
                    .insert(bytes(1), BigInteger.TEN);
            var deleted = map.delete(bytes(1));
            assertEquals(1, deleted.size());
            assertFalse(deleted.containsKey(bytes(1)));
            assertEquals(BigInteger.TWO, deleted.get(bytes(2)));
            assertEquals(map.size(), map.delete(bytes(9)).size());
        }

        @Test
        void duplicateKeysFirstEntryWins() {
            var a = bytes(7);
            var b = bytes(7);
            JulcMap<byte[], BigInteger> map = JulcMap.of(a, BigInteger.ONE).insert(b, BigInteger.TWO);
            assertEquals(2, map.size());
            // As on-chain: insert prepends, and lookup takes the first equal key.
            assertEquals(BigInteger.TWO, map.get(a));
            assertEquals(BigInteger.TWO, map.get(b));
        }

        @Test
        void valuesCompareByContent() {
            JulcMap<BigInteger, byte[]> a = JulcMap.of(BigInteger.ONE, bytes(1, 2));
            JulcMap<BigInteger, byte[]> b = JulcMap.of(BigInteger.ONE, bytes(1, 2));
            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
            assertNotEquals(a, JulcMap.of(BigInteger.ONE, bytes(2, 1)));
        }

        @Test
        void equalsAndHashCodeByContent() {
            JulcMap<byte[], byte[]> a = JulcMap.of(bytes(1), bytes(10), bytes(2), bytes(20));
            JulcMap<byte[], byte[]> b = JulcMap.of(bytes(1), bytes(10), bytes(2), bytes(20));
            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
            // Entry order is part of an association list's identity, as before.
            assertNotEquals(a, JulcMap.of(bytes(2), bytes(20), bytes(1), bytes(10)));
            assertNotEquals(a, JulcMap.of(bytes(1), bytes(10)));
        }

        @Test
        void nestedCollectionsAsKeysAndValues() {
            JulcMap<JulcList<byte[]>, JulcMap<byte[], BigInteger>> map =
                    JulcMap.of(JulcList.of(bytes(1)), JulcMap.of(bytes(2), BigInteger.TWO));
            assertTrue(map.containsKey(JulcList.of(bytes(1))));
            assertEquals(JulcMap.of(bytes(2), BigInteger.TWO), map.get(JulcList.of(bytes(1))));
        }

        @Test
        void hashSetDeduplicatesEqualMaps() {
            var set = new HashSet<JulcMap<byte[], BigInteger>>();
            set.add(JulcMap.of(bytes(1), BigInteger.ONE));
            set.add(JulcMap.of(bytes(1), BigInteger.ONE));
            set.add(JulcMap.of(bytes(1), BigInteger.TWO));
            assertEquals(2, set.size());
        }

        @Test
        void keysAreHeldNotCopied() {
            var key = bytes(1);
            JulcMap<byte[], BigInteger> map = JulcMap.of(key, BigInteger.ONE);
            int before = map.hashCode();
            key[0] = 5;
            assertTrue(map.containsKey(bytes(5)));
            assertFalse(map.containsKey(bytes(1)));
            assertNotEquals(before, map.hashCode());
        }

        @Test
        void keysAndValuesListsKeepContentEquality() {
            JulcMap<byte[], byte[]> map = JulcMap.of(bytes(1), bytes(10));
            assertTrue(map.keys().contains(bytes(1)));
            assertTrue(map.values().contains(bytes(10)));
        }
    }

    @Nested
    class Arrays {

        @Test
        void equalsAndHashCodeByContent() {
            var a = new JulcArrayImpl<>(List.of(bytes(1), bytes(2, 3)));
            var b = new JulcArrayImpl<>(List.of(bytes(1), bytes(2, 3)));
            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
            assertNotEquals(a, new JulcArrayImpl<>(List.of(bytes(1), bytes(3, 2))));
            assertNotEquals(a, new JulcArrayImpl<>(List.of(bytes(1))));
        }

        @Test
        void nestedCollectionsCompareDeeply() {
            var a = new JulcArrayImpl<>(List.of(JulcList.of(bytes(1))));
            var b = new JulcArrayImpl<>(List.of(JulcList.of(bytes(1))));
            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
            assertTrue(JulcList.of(a).contains(b));
        }

        @Test
        void hashSetDeduplicatesEqualArrays() {
            var set = new HashSet<JulcArray<byte[]>>();
            set.add(new JulcArrayImpl<>(List.of(bytes(4))));
            set.add(new JulcArrayImpl<>(List.of(bytes(4))));
            assertEquals(1, set.size());
        }

        @Test
        void elementsAreHeldNotCopied() {
            var element = bytes(1);
            var array = new JulcArrayImpl<>(List.of(element));
            element[0] = 2;
            assertSame(element, array.get(0));
            assertEquals(new JulcArrayImpl<>(List.of(bytes(2))), array);
        }
    }
}
