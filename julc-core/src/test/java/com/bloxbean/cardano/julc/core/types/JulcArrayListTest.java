package com.bloxbean.cardano.julc.core.types;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JulcArrayListTest {

    // --- Factory methods ---

    @Test
    void emptyList() {
        JulcList<String> list = JulcList.empty();
        assertTrue(list.isEmpty());
        assertEquals(0, list.size());
    }

    @Test
    void ofCreatesListWithElements() {
        JulcList<String> list = JulcList.of("a", "b", "c");
        assertEquals(3, list.size());
        assertEquals("a", list.head());
    }

    @Test
    void ofSingleElement() {
        JulcList<Integer> list = JulcList.of(42);
        assertEquals(1, list.size());
        assertEquals(42, list.head());
    }

    // --- Element access ---

    @Nested
    class ElementAccess {
        @Test
        void headReturnsFirstElement() {
            JulcList<String> list = JulcList.of("a", "b", "c");
            assertEquals("a", list.head());
        }

        @Test
        void headOnEmptyThrows() {
            JulcList<String> list = JulcList.empty();
            assertThrows(RuntimeException.class, list::head);
        }

        @Test
        void getByIndex() {
            JulcList<String> list = JulcList.of("a", "b", "c");
            assertEquals("a", list.get(0));
            assertEquals("b", list.get(1));
            assertEquals("c", list.get(2));
        }

        @Test
        void getOutOfBoundsThrows() {
            JulcList<String> list = JulcList.of("a");
            assertThrows(IndexOutOfBoundsException.class, () -> list.get(5));
        }
    }

    // --- Sublisting ---

    @Nested
    class Sublisting {
        @Test
        void tailReturnsAllButFirst() {
            JulcList<String> list = JulcList.of("a", "b", "c");
            JulcList<String> tail = list.tail();
            assertEquals(2, tail.size());
            assertEquals("b", tail.head());
        }

        @Test
        void tailOnEmptyThrows() {
            JulcList<String> list = JulcList.empty();
            assertThrows(RuntimeException.class, list::tail);
        }

        @Test
        void tailOnSingleElementReturnsEmpty() {
            JulcList<String> list = JulcList.of("a");
            assertTrue(list.tail().isEmpty());
        }

        @Test
        void takeReturnsFirstNElements() {
            JulcList<String> list = JulcList.of("a", "b", "c", "d");
            JulcList<String> taken = list.take(2);
            assertEquals(2, taken.size());
            assertEquals("a", taken.get(0));
            assertEquals("b", taken.get(1));
        }

        @Test
        void takeBeyondSizeReturnsAll() {
            JulcList<String> list = JulcList.of("a", "b");
            JulcList<String> taken = list.take(10);
            assertEquals(2, taken.size());
        }

        @Test
        void takeZeroReturnsEmpty() {
            JulcList<String> list = JulcList.of("a", "b");
            assertTrue(list.take(0).isEmpty());
        }

        @Test
        void dropSkipsFirstNElements() {
            JulcList<String> list = JulcList.of("a", "b", "c", "d");
            JulcList<String> dropped = list.drop(2);
            assertEquals(2, dropped.size());
            assertEquals("c", dropped.get(0));
            assertEquals("d", dropped.get(1));
        }

        @Test
        void dropBeyondSizeReturnsEmpty() {
            JulcList<String> list = JulcList.of("a", "b");
            assertTrue(list.drop(10).isEmpty());
        }

        @Test
        void dropZeroReturnsAll() {
            JulcList<String> list = JulcList.of("a", "b");
            assertEquals(2, list.drop(0).size());
        }
    }

    // --- Construction ---

    @Nested
    class Construction {
        @Test
        void prependAddsToFront() {
            JulcList<String> list = JulcList.of("b", "c");
            JulcList<String> result = list.prepend("a");
            assertEquals(3, result.size());
            assertEquals("a", result.head());
            assertEquals("b", result.get(1));
        }

        @Test
        void prependOnEmptyList() {
            JulcList<String> list = JulcList.empty();
            JulcList<String> result = list.prepend("a");
            assertEquals(1, result.size());
            assertEquals("a", result.head());
        }

        @Test
        void prependDoesNotMutateOriginal() {
            JulcList<String> original = JulcList.of("b", "c");
            original.prepend("a");
            assertEquals(2, original.size());
            assertEquals("b", original.head());
        }

        @Test
        void concatJoinsTwoLists() {
            JulcList<String> a = JulcList.of("a", "b");
            JulcList<String> b = JulcList.of("c", "d");
            JulcList<String> result = a.concat(b);
            assertEquals(4, result.size());
            assertEquals("a", result.get(0));
            assertEquals("b", result.get(1));
            assertEquals("c", result.get(2));
            assertEquals("d", result.get(3));
        }

        @Test
        void concatWithEmptyReturnsOriginal() {
            JulcList<String> list = JulcList.of("a", "b");
            JulcList<String> result = list.concat(JulcList.empty());
            assertEquals(2, result.size());
        }

        @Test
        void concatDoesNotMutateOriginal() {
            JulcList<String> original = JulcList.of("a");
            original.concat(JulcList.of("b"));
            assertEquals(1, original.size());
        }

        @Test
        void reverseReversesOrder() {
            JulcList<String> list = JulcList.of("a", "b", "c");
            JulcList<String> reversed = list.reverse();
            assertEquals(3, reversed.size());
            assertEquals("c", reversed.get(0));
            assertEquals("b", reversed.get(1));
            assertEquals("a", reversed.get(2));
        }

        @Test
        void reverseEmptyReturnsEmpty() {
            JulcList<String> list = JulcList.empty();
            assertTrue(list.reverse().isEmpty());
        }

        @Test
        void reverseSingleElement() {
            JulcList<String> list = JulcList.of("a");
            JulcList<String> reversed = list.reverse();
            assertEquals(1, reversed.size());
            assertEquals("a", reversed.head());
        }
    }

    // --- Query ---

    @Nested
    class Query {
        @Test
        void sizeReturnsElementCount() {
            assertEquals(0, JulcList.empty().size());
            assertEquals(1, JulcList.of("a").size());
            assertEquals(3, JulcList.of("a", "b", "c").size());
        }

        @Test
        void isEmptyOnEmptyList() {
            assertTrue(JulcList.empty().isEmpty());
        }

        @Test
        void isEmptyOnNonEmptyList() {
            assertFalse(JulcList.of("a").isEmpty());
        }

        @Test
        void containsFindsElement() {
            JulcList<String> list = JulcList.of("a", "b", "c");
            assertTrue(list.contains("b"));
        }

        @Test
        void containsReturnsFalseForMissing() {
            JulcList<String> list = JulcList.of("a", "b", "c");
            assertFalse(list.contains("z"));
        }

        @Test
        void containsOnEmptyList() {
            JulcList<String> list = JulcList.empty();
            assertFalse(list.contains("a"));
        }
    }

    // --- Iterable ---

    @Nested
    class Iteration {
        @Test
        void forEachIteratesAllElements() {
            JulcList<String> list = JulcList.of("a", "b", "c");
            var collected = new ArrayList<String>();
            for (String s : list) {
                collected.add(s);
            }
            assertEquals(List.of("a", "b", "c"), collected);
        }

        @Test
        void forEachOnEmptyList() {
            JulcList<String> list = JulcList.empty();
            var collected = new ArrayList<String>();
            for (String s : list) {
                collected.add(s);
            }
            assertTrue(collected.isEmpty());
        }
    }

    // --- Equality ---

    @Nested
    class Equality {
        @Test
        void equalListsAreEqual() {
            JulcList<String> a = JulcList.of("a", "b");
            JulcList<String> b = JulcList.of("a", "b");
            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
        }

        @Test
        void differentListsAreNotEqual() {
            JulcList<String> a = JulcList.of("a", "b");
            JulcList<String> b = JulcList.of("a", "c");
            assertNotEquals(a, b);
        }

        @Test
        void toStringShowsElements() {
            JulcList<String> list = JulcList.of("a", "b");
            assertEquals("JulcList[a, b]", list.toString());
        }
    }
}
