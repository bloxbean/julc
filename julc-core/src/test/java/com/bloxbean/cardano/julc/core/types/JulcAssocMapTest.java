package com.bloxbean.cardano.julc.core.types;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JulcAssocMapTest {

    // --- Factory methods ---

    @Test
    void emptyMap() {
        JulcMap<String, Integer> map = JulcAssocMap.empty();
        assertTrue(map.isEmpty());
        assertEquals(0, map.size());
    }

    @Test
    void ofCreatesSingleEntryMap() {
        JulcMap<String, Integer> map = JulcAssocMap.of("a", 1);
        assertEquals(1, map.size());
        assertEquals(1, map.get("a"));
    }

    // --- Lookup ---

    @Nested
    class Lookup {
        @Test
        void getFindsExistingKey() {
            JulcMap<String, Integer> map = JulcAssocMap.<String, Integer>empty()
                    .insert("a", 1).insert("b", 2);
            assertEquals(2, map.get("b"));
        }

        @Test
        void getReturnsNullForMissingKey() {
            JulcMap<String, Integer> map = JulcAssocMap.of("a", 1);
            assertNull(map.get("z"));
        }

        @Test
        void containsKeyForExisting() {
            JulcMap<String, Integer> map = JulcAssocMap.of("a", 1);
            assertTrue(map.containsKey("a"));
        }

        @Test
        void containsKeyForMissing() {
            JulcMap<String, Integer> map = JulcAssocMap.of("a", 1);
            assertFalse(map.containsKey("z"));
        }

        @Test
        void containsKeyOnEmptyMap() {
            JulcMap<String, Integer> map = JulcAssocMap.empty();
            assertFalse(map.containsKey("a"));
        }
    }

    // --- Modification ---

    @Nested
    class Modification {
        @Test
        void insertAddsEntry() {
            JulcMap<String, Integer> map = JulcAssocMap.<String, Integer>empty()
                    .insert("a", 1);
            assertEquals(1, map.size());
            assertEquals(1, map.get("a"));
        }

        @Test
        void insertMultipleEntries() {
            JulcMap<String, Integer> map = JulcAssocMap.<String, Integer>empty()
                    .insert("a", 1).insert("b", 2).insert("c", 3);
            assertEquals(3, map.size());
            assertEquals(1, map.get("a"));
            assertEquals(2, map.get("b"));
            assertEquals(3, map.get("c"));
        }

        @Test
        void insertDoesNotMutateOriginal() {
            JulcMap<String, Integer> original = JulcAssocMap.of("a", 1);
            original.insert("b", 2);
            assertEquals(1, original.size());
            assertFalse(original.containsKey("b"));
        }

        @Test
        void insertDuplicateKeyAddsNewEntry() {
            // Matches on-chain semantics: MkCons prepends, doesn't replace
            JulcMap<String, Integer> map = JulcAssocMap.of("a", 1).insert("a", 99);
            assertEquals(2, map.size());
            // get returns first match (the newly inserted one at front)
            assertEquals(99, map.get("a"));
        }

        @Test
        void deleteRemovesEntry() {
            JulcMap<String, Integer> map = JulcAssocMap.<String, Integer>empty()
                    .insert("a", 1).insert("b", 2).insert("c", 3);
            JulcMap<String, Integer> result = map.delete("b");
            assertEquals(2, result.size());
            assertFalse(result.containsKey("b"));
            assertTrue(result.containsKey("a"));
            assertTrue(result.containsKey("c"));
        }

        @Test
        void deleteNonExistentKeyReturnsUnchanged() {
            JulcMap<String, Integer> map = JulcAssocMap.of("a", 1);
            JulcMap<String, Integer> result = map.delete("z");
            assertEquals(1, result.size());
            assertTrue(result.containsKey("a"));
        }

        @Test
        void deleteDoesNotMutateOriginal() {
            JulcMap<String, Integer> original = JulcAssocMap.<String, Integer>empty()
                    .insert("a", 1).insert("b", 2);
            original.delete("a");
            assertEquals(2, original.size());
            assertTrue(original.containsKey("a"));
        }

        @Test
        void deleteFromEmptyMap() {
            JulcMap<String, Integer> map = JulcAssocMap.empty();
            JulcMap<String, Integer> result = map.delete("a");
            assertTrue(result.isEmpty());
        }
    }

    // --- Extraction ---

    @Nested
    class Extraction {
        @Test
        void keysReturnsAllKeys() {
            JulcMap<String, Integer> map = JulcAssocMap.<String, Integer>empty()
                    .insert("a", 1).insert("b", 2);
            JulcList<String> keys = map.keys();
            assertEquals(2, keys.size());
            // insert prepends, so order is b, a
            assertTrue(keys.contains("a"));
            assertTrue(keys.contains("b"));
        }

        @Test
        void valuesReturnsAllValues() {
            JulcMap<String, Integer> map = JulcAssocMap.<String, Integer>empty()
                    .insert("a", 1).insert("b", 2);
            JulcList<Integer> values = map.values();
            assertEquals(2, values.size());
            assertTrue(values.contains(1));
            assertTrue(values.contains(2));
        }

        @Test
        void keysOnEmptyMapReturnsEmptyList() {
            JulcMap<String, Integer> map = JulcAssocMap.empty();
            assertTrue(map.keys().isEmpty());
        }

        @Test
        void valuesOnEmptyMapReturnsEmptyList() {
            JulcMap<String, Integer> map = JulcAssocMap.empty();
            assertTrue(map.values().isEmpty());
        }
    }

    // --- Query ---

    @Nested
    class Query {
        @Test
        void sizeReturnsEntryCount() {
            assertEquals(0, JulcAssocMap.empty().size());
            assertEquals(1, JulcAssocMap.of("a", 1).size());
        }

        @Test
        void isEmptyOnEmptyMap() {
            assertTrue(JulcAssocMap.empty().isEmpty());
        }

        @Test
        void isEmptyOnNonEmptyMap() {
            assertFalse(JulcAssocMap.of("a", 1).isEmpty());
        }
    }

    // --- Equality ---

    @Nested
    class EqualityTests {
        @Test
        void equalMapsAreEqual() {
            JulcMap<String, Integer> a = JulcAssocMap.of("x", 1);
            JulcMap<String, Integer> b = JulcAssocMap.of("x", 1);
            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
        }

        @Test
        void differentMapsAreNotEqual() {
            JulcMap<String, Integer> a = JulcAssocMap.of("x", 1);
            JulcMap<String, Integer> b = JulcAssocMap.of("x", 2);
            assertNotEquals(a, b);
        }

        @Test
        void toStringShowsEntries() {
            JulcMap<String, Integer> map = JulcAssocMap.of("a", 1);
            assertEquals("JulcMap{a=1}", map.toString());
        }
    }
}
