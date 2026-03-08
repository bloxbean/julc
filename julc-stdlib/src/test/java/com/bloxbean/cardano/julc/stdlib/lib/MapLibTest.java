package com.bloxbean.cardano.julc.stdlib.lib;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.testkit.JulcEval;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MapLib using JulcEval — compiles actual Java source to UPLC
 * and evaluates through Scalus VM for real on-chain behavior testing.
 */
class MapLibTest {

    static JulcEval eval;

    @BeforeAll
    static void setUp() {
        eval = JulcEval.forClass(MapLib.class);
    }

    // --- Helpers ---

    static PlutusData emptyMap() {
        return PlutusData.map();
    }

    static PlutusData simpleMap(long k1, long v1, long k2, long v2) {
        return PlutusData.map(
                new PlutusData.Pair(PlutusData.integer(k1), PlutusData.integer(v1)),
                new PlutusData.Pair(PlutusData.integer(k2), PlutusData.integer(v2)));
    }

    static PlutusData singleMap(long k, long v) {
        return PlutusData.map(
                new PlutusData.Pair(PlutusData.integer(k), PlutusData.integer(v)));
    }

    // =========================================================================
    // lookup
    // =========================================================================

    @Nested
    class Lookup {

        @Test
        void lookupPresent() {
            Optional<PlutusData> result = eval.call("lookup", simpleMap(1, 100, 2, 200),
                    PlutusData.integer(1)).asOptional();
            assertTrue(result.isPresent());
            assertEquals(PlutusData.integer(100), result.get());
        }

        @Test
        void lookupAbsent() {
            Optional<PlutusData> result = eval.call("lookup", simpleMap(1, 100, 2, 200),
                    PlutusData.integer(99)).asOptional();
            assertTrue(result.isEmpty());
        }

        @Test
        void lookupEmptyMap() {
            Optional<PlutusData> result = eval.call("lookup", emptyMap(),
                    PlutusData.integer(1)).asOptional();
            assertTrue(result.isEmpty());
        }
    }

    // =========================================================================
    // member
    // =========================================================================

    @Nested
    class Member {

        @Test
        void memberPresent() {
            assertTrue(eval.call("member", simpleMap(1, 100, 2, 200),
                    PlutusData.integer(2)).asBoolean());
        }

        @Test
        void memberAbsent() {
            assertFalse(eval.call("member", simpleMap(1, 100, 2, 200),
                    PlutusData.integer(99)).asBoolean());
        }

        @Test
        void memberEmptyMap() {
            assertFalse(eval.call("member", emptyMap(),
                    PlutusData.integer(1)).asBoolean());
        }
    }

    // =========================================================================
    // insert — tested via composite operations (insert returns pair list at UPLC
    // boundary, so we use forSource wrappers that chain insert + verification)
    // =========================================================================

    @Nested
    class Insert {

        static final JulcEval insertOps = JulcEval.forSource("""
                import com.bloxbean.cardano.julc.core.PlutusData;
                import com.bloxbean.cardano.julc.core.types.JulcMap;
                import com.bloxbean.cardano.julc.stdlib.lib.MapLib;
                import com.bloxbean.cardano.julc.stdlib.Builtins;
                import java.util.Optional;

                class InsertOps {
                    static long insertAndSize(PlutusData map, PlutusData key, PlutusData value) {
                        JulcMap<PlutusData, PlutusData> m = (JulcMap)(Object) map;
                        JulcMap<PlutusData, PlutusData> updated = MapLib.insert(m, key, value);
                        return MapLib.size(updated);
                    }

                    static boolean insertAndLookupPresent(PlutusData map, PlutusData key, PlutusData value) {
                        JulcMap<PlutusData, PlutusData> m = (JulcMap)(Object) map;
                        JulcMap<PlutusData, PlutusData> updated = MapLib.insert(m, key, value);
                        Optional<PlutusData> result = MapLib.lookup(updated, key);
                        return result.isPresent();
                    }

                    static boolean insertAndLookupValue(PlutusData map, PlutusData key, PlutusData value) {
                        JulcMap<PlutusData, PlutusData> m = (JulcMap)(Object) map;
                        JulcMap<PlutusData, PlutusData> updated = MapLib.insert(m, key, value);
                        Optional<PlutusData> result = MapLib.lookup(updated, key);
                        if (result.isPresent()) {
                            return Builtins.equalsData(result.get(), value);
                        }
                        return false;
                    }
                }
                """);

        @Test
        void insertIntoEmptyIncreasesSize() {
            long size = insertOps.call("insertAndSize", emptyMap(),
                    PlutusData.integer(1), PlutusData.integer(100)).asLong();
            assertEquals(1, size);
        }

        @Test
        void insertNewKeyIncreasesSize() {
            long size = insertOps.call("insertAndSize", simpleMap(1, 100, 2, 200),
                    PlutusData.integer(3), PlutusData.integer(300)).asLong();
            assertEquals(3, size);
        }

        @Test
        void insertedKeyIsFoundByLookup() {
            assertTrue(insertOps.call("insertAndLookupPresent", emptyMap(),
                    PlutusData.integer(5), PlutusData.integer(500)).asBoolean());
        }

        @Test
        void insertedValueMatchesLookup() {
            assertTrue(insertOps.call("insertAndLookupValue", emptyMap(),
                    PlutusData.integer(5), PlutusData.integer(500)).asBoolean());
        }
    }

    // =========================================================================
    // delete — tested via composite operations (same boundary issue as insert)
    // =========================================================================

    @Nested
    class Delete {

        static final JulcEval deleteOps = JulcEval.forSource("""
                import com.bloxbean.cardano.julc.core.PlutusData;
                import com.bloxbean.cardano.julc.core.types.JulcMap;
                import com.bloxbean.cardano.julc.stdlib.lib.MapLib;

                class DeleteOps {
                    static long deleteAndSize(PlutusData map, PlutusData key) {
                        JulcMap<PlutusData, PlutusData> m = (JulcMap)(Object) map;
                        JulcMap<PlutusData, PlutusData> updated = MapLib.delete(m, key);
                        return MapLib.size(updated);
                    }

                    static boolean deleteAndMember(PlutusData map, PlutusData key) {
                        JulcMap<PlutusData, PlutusData> m = (JulcMap)(Object) map;
                        JulcMap<PlutusData, PlutusData> updated = MapLib.delete(m, key);
                        return MapLib.member(updated, key);
                    }
                }
                """);

        @Test
        void deleteExistingKeyDecreasesSize() {
            long size = deleteOps.call("deleteAndSize", simpleMap(1, 100, 2, 200),
                    PlutusData.integer(1)).asLong();
            assertEquals(1, size);
        }

        @Test
        void deleteNonExistentKeyKeepsSize() {
            long size = deleteOps.call("deleteAndSize", simpleMap(1, 100, 2, 200),
                    PlutusData.integer(99)).asLong();
            assertEquals(2, size);
        }

        @Test
        void deleteFromEmptyMapKeepsEmpty() {
            long size = deleteOps.call("deleteAndSize", emptyMap(),
                    PlutusData.integer(1)).asLong();
            assertEquals(0, size);
        }

        @Test
        void deletedKeyNotFound() {
            assertFalse(deleteOps.call("deleteAndMember", simpleMap(1, 100, 2, 200),
                    PlutusData.integer(1)).asBoolean());
        }
    }

    // =========================================================================
    // keys / values
    // =========================================================================

    @Nested
    class KeysValues {

        @Test
        void keysEmpty() {
            List<PlutusData> result = eval.call("keys", emptyMap()).asList();
            assertTrue(result.isEmpty());
        }

        @Test
        void keysNonEmpty() {
            List<PlutusData> result = eval.call("keys", simpleMap(1, 100, 2, 200)).asList();
            assertEquals(2, result.size());
            assertTrue(result.contains(PlutusData.integer(1)));
            assertTrue(result.contains(PlutusData.integer(2)));
        }

        @Test
        void valuesEmpty() {
            List<PlutusData> result = eval.call("values", emptyMap()).asList();
            assertTrue(result.isEmpty());
        }

        @Test
        void valuesNonEmpty() {
            List<PlutusData> result = eval.call("values", simpleMap(1, 100, 2, 200)).asList();
            assertEquals(2, result.size());
            assertTrue(result.contains(PlutusData.integer(100)));
            assertTrue(result.contains(PlutusData.integer(200)));
        }
    }

    // =========================================================================
    // size
    // =========================================================================

    @Nested
    class Size {

        @Test
        void sizeEmpty() {
            assertEquals(0, eval.call("size", emptyMap()).asLong());
        }

        @Test
        void sizeNonEmpty() {
            assertEquals(2, eval.call("size", simpleMap(1, 100, 2, 200)).asLong());
        }
    }
}
