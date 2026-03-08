package com.bloxbean.cardano.julc.stdlib.lib;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.testkit.JulcEval;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ListsLib using JulcEval — compiles actual Java source to UPLC
 * and evaluates through Scalus VM for real on-chain behavior testing.
 */
class ListsLibTest {

    static JulcEval eval;

    @BeforeAll
    static void setUp() {
        eval = JulcEval.forClass(ListsLib.class);
    }

    // --- Helpers ---

    static PlutusData intList(long... values) {
        var items = new PlutusData[values.length];
        for (int i = 0; i < values.length; i++) {
            items[i] = PlutusData.integer(values[i]);
        }
        return PlutusData.list(items);
    }

    static PlutusData bytesList(byte[]... values) {
        var items = new PlutusData[values.length];
        for (int i = 0; i < values.length; i++) {
            items[i] = PlutusData.bytes(values[i]);
        }
        return PlutusData.list(items);
    }

    static PlutusData emptyList() {
        return PlutusData.list();
    }

    // =========================================================================
    // empty
    // =========================================================================

    @Nested
    class Empty {

        @Test
        void returnsEmptyList() {
            List<PlutusData> result = eval.call("empty").asList();
            assertTrue(result.isEmpty());
        }
    }

    // =========================================================================
    // prepend
    // =========================================================================

    @Nested
    class Prepend {

        @Test
        void prependToEmpty() {
            List<PlutusData> result = eval.call("prepend", emptyList(), PlutusData.integer(1)).asList();
            assertEquals(1, result.size());
            assertEquals(PlutusData.integer(1), result.getFirst());
        }

        @Test
        void prependToNonEmpty() {
            List<PlutusData> result = eval.call("prepend", intList(2, 3), PlutusData.integer(1)).asList();
            assertEquals(3, result.size());
            assertEquals(PlutusData.integer(1), result.get(0));
            assertEquals(PlutusData.integer(2), result.get(1));
            assertEquals(PlutusData.integer(3), result.get(2));
        }
    }

    // =========================================================================
    // length
    // =========================================================================

    @Nested
    class Length {

        @Test
        void emptyListHasZeroLength() {
            long result = eval.call("length", emptyList()).asLong();
            assertEquals(0, result);
        }

        @Test
        void singleElementLength() {
            long result = eval.call("length", intList(42)).asLong();
            assertEquals(1, result);
        }

        @Test
        void multipleElementsLength() {
            long result = eval.call("length", intList(1, 2, 3, 4, 5)).asLong();
            assertEquals(5, result);
        }
    }

    // =========================================================================
    // isEmpty
    // =========================================================================

    @Nested
    class IsEmpty {

        @Test
        void emptyListIsEmpty() {
            assertTrue(eval.call("isEmpty", emptyList()).asBoolean());
        }

        @Test
        void nonEmptyListIsNotEmpty() {
            assertFalse(eval.call("isEmpty", intList(1)).asBoolean());
        }
    }

    // =========================================================================
    // head / tail
    // =========================================================================

    @Nested
    class HeadTail {

        @Test
        void headReturnsFirstElement() {
            PlutusData result = eval.call("head", intList(42, 99, 7)).asData();
            assertEquals(PlutusData.integer(42), result);
        }

        @Test
        void tailRemovesFirst() {
            List<PlutusData> result = eval.call("tail", intList(1, 2, 3)).asList();
            assertEquals(2, result.size());
            assertEquals(PlutusData.integer(2), result.get(0));
            assertEquals(PlutusData.integer(3), result.get(1));
        }

        @Test
        void tailOfSingleIsEmpty() {
            List<PlutusData> result = eval.call("tail", intList(1)).asList();
            assertTrue(result.isEmpty());
        }
    }

    // =========================================================================
    // reverse
    // =========================================================================

    @Nested
    class Reverse {

        @Test
        void reverseEmpty() {
            List<PlutusData> result = eval.call("reverse", emptyList()).asList();
            assertTrue(result.isEmpty());
        }

        @Test
        void reverseSingle() {
            List<PlutusData> result = eval.call("reverse", intList(42)).asList();
            assertEquals(1, result.size());
            assertEquals(PlutusData.integer(42), result.getFirst());
        }

        @Test
        void reverseMultiple() {
            List<PlutusData> result = eval.call("reverse", intList(1, 2, 3)).asList();
            assertEquals(List.of(PlutusData.integer(3), PlutusData.integer(2), PlutusData.integer(1)), result);
        }
    }

    // =========================================================================
    // concat
    // =========================================================================

    @Nested
    class Concat {

        @Test
        void concatBothEmpty() {
            List<PlutusData> result = eval.call("concat", emptyList(), emptyList()).asList();
            assertTrue(result.isEmpty());
        }

        @Test
        void concatFirstEmpty() {
            List<PlutusData> result = eval.call("concat", emptyList(), intList(1, 2)).asList();
            assertEquals(List.of(PlutusData.integer(1), PlutusData.integer(2)), result);
        }

        @Test
        void concatSecondEmpty() {
            List<PlutusData> result = eval.call("concat", intList(1, 2), emptyList()).asList();
            assertEquals(List.of(PlutusData.integer(1), PlutusData.integer(2)), result);
        }

        @Test
        void concatBothNonEmpty() {
            List<PlutusData> result = eval.call("concat", intList(1, 2), intList(3, 4)).asList();
            assertEquals(List.of(
                    PlutusData.integer(1), PlutusData.integer(2),
                    PlutusData.integer(3), PlutusData.integer(4)), result);
        }
    }

    // =========================================================================
    // nth
    // =========================================================================

    @Nested
    class Nth {

        @Test
        void nthFirstElement() {
            PlutusData result = eval.call("nth", intList(10, 20, 30), 0L).asData();
            assertEquals(PlutusData.integer(10), result);
        }

        @Test
        void nthMiddleElement() {
            PlutusData result = eval.call("nth", intList(10, 20, 30), 1L).asData();
            assertEquals(PlutusData.integer(20), result);
        }

        @Test
        void nthLastElement() {
            PlutusData result = eval.call("nth", intList(10, 20, 30), 2L).asData();
            assertEquals(PlutusData.integer(30), result);
        }
    }

    // =========================================================================
    // take / drop
    // =========================================================================

    @Nested
    class TakeDrop {

        @Test
        void takeZero() {
            List<PlutusData> result = eval.call("take", intList(1, 2, 3), 0L).asList();
            assertTrue(result.isEmpty());
        }

        @Test
        void takePartial() {
            List<PlutusData> result = eval.call("take", intList(1, 2, 3, 4), 2L).asList();
            assertEquals(List.of(PlutusData.integer(1), PlutusData.integer(2)), result);
        }

        @Test
        void takeAll() {
            List<PlutusData> result = eval.call("take", intList(1, 2), 2L).asList();
            assertEquals(List.of(PlutusData.integer(1), PlutusData.integer(2)), result);
        }

        @Test
        void dropZero() {
            List<PlutusData> result = eval.call("drop", intList(1, 2, 3), 0L).asList();
            assertEquals(List.of(PlutusData.integer(1), PlutusData.integer(2), PlutusData.integer(3)), result);
        }

        @Test
        void dropPartial() {
            List<PlutusData> result = eval.call("drop", intList(1, 2, 3, 4), 2L).asList();
            assertEquals(List.of(PlutusData.integer(3), PlutusData.integer(4)), result);
        }

        @Test
        void dropAll() {
            List<PlutusData> result = eval.call("drop", intList(1, 2), 2L).asList();
            assertTrue(result.isEmpty());
        }
    }

    // =========================================================================
    // contains
    // =========================================================================

    @Nested
    class Contains {

        @Test
        void containsPresent() {
            assertTrue(eval.call("contains", intList(1, 2, 3), PlutusData.integer(2)).asBoolean());
        }

        @Test
        void containsAbsent() {
            assertFalse(eval.call("contains", intList(1, 2, 3), PlutusData.integer(99)).asBoolean());
        }

        @Test
        void containsEmptyList() {
            assertFalse(eval.call("contains", emptyList(), PlutusData.integer(1)).asBoolean());
        }
    }

    // =========================================================================
    // containsInt
    // =========================================================================

    @Nested
    class ContainsInt {

        @Test
        void containsIntPresent() {
            assertTrue(eval.call("containsInt", intList(10, 20, 30), BigInteger.valueOf(20)).asBoolean());
        }

        @Test
        void containsIntAbsent() {
            assertFalse(eval.call("containsInt", intList(10, 20, 30), BigInteger.valueOf(99)).asBoolean());
        }

        @Test
        void containsIntEmptyList() {
            assertFalse(eval.call("containsInt", emptyList(), BigInteger.valueOf(1)).asBoolean());
        }
    }

    // =========================================================================
    // containsBytes
    // =========================================================================

    @Nested
    class ContainsBytes {

        @Test
        void containsBytesPresent() {
            var list = bytesList(new byte[]{1, 2}, new byte[]{3, 4}, new byte[]{5, 6});
            assertTrue(eval.call("containsBytes", list, new byte[]{3, 4}).asBoolean());
        }

        @Test
        void containsBytesAbsent() {
            var list = bytesList(new byte[]{1, 2}, new byte[]{3, 4});
            assertFalse(eval.call("containsBytes", list, new byte[]{9, 9}).asBoolean());
        }

        @Test
        void containsBytesEmptyList() {
            assertFalse(eval.call("containsBytes", emptyList(), new byte[]{1}).asBoolean());
        }
    }

    // =========================================================================
    // hasDuplicateInts
    // =========================================================================

    @Nested
    class HasDuplicateInts {

        @Test
        void withDuplicates() {
            assertTrue(eval.call("hasDuplicateInts", intList(1, 2, 3, 2)).asBoolean());
        }

        @Test
        void withoutDuplicates() {
            assertFalse(eval.call("hasDuplicateInts", intList(1, 2, 3, 4)).asBoolean());
        }

        @Test
        void emptyListNoDuplicates() {
            assertFalse(eval.call("hasDuplicateInts", ListsLibTest.emptyList()).asBoolean());
        }

        @Test
        void singleElement() {
            assertFalse(eval.call("hasDuplicateInts", intList(42)).asBoolean());
        }
    }

    // =========================================================================
    // hasDuplicateBytes
    // =========================================================================

    @Nested
    class HasDuplicateBytes {

        @Test
        void withDuplicates() {
            var list = bytesList(new byte[]{1}, new byte[]{2}, new byte[]{1});
            assertTrue(eval.call("hasDuplicateBytes", list).asBoolean());
        }

        @Test
        void withoutDuplicates() {
            var list = bytesList(new byte[]{1}, new byte[]{2}, new byte[]{3});
            assertFalse(eval.call("hasDuplicateBytes", list).asBoolean());
        }

        @Test
        void emptyListNoDuplicates() {
            assertFalse(eval.call("hasDuplicateBytes", ListsLibTest.emptyList()).asBoolean());
        }
    }
}
