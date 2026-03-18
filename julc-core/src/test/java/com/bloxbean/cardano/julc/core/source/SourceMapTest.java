package com.bloxbean.cardano.julc.core.source;

import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.Term;
import org.junit.jupiter.api.Test;

import java.util.IdentityHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SourceLocation and SourceMap.
 */
class SourceMapTest {

    @Test
    void sourceLocation_toString_withAllFields() {
        var loc = new SourceLocation("MyValidator.java", 42, 5, "amount < 0");
        assertEquals("MyValidator.java:42 (amount < 0)", loc.toString());
    }

    @Test
    void sourceLocation_toString_withNullFragment() {
        var loc = new SourceLocation("MyValidator.java", 42, 5, null);
        assertEquals("MyValidator.java:42", loc.toString());
    }

    @Test
    void sourceLocation_toString_truncatesLongFragment() {
        var longFragment = "a".repeat(100);
        var loc = new SourceLocation("Test.java", 1, 1, longFragment);
        assertTrue(loc.toString().contains("..."));
        assertTrue(loc.toString().length() < 100);
    }

    @Test
    void sourceMap_empty_alwaysReturnsNull() {
        var empty = SourceMap.EMPTY;
        assertTrue(empty.isEmpty());
        assertEquals(0, empty.size());
        assertNull(empty.lookup(Term.error()));
        assertNull(empty.lookup(null));
    }

    @Test
    void sourceMap_lookup_byIdentity() {
        var term1 = Term.error();
        var term2 = Term.error(); // structurally equal but different object
        var loc = new SourceLocation("Test.java", 10, 1, "error()");

        var map = new IdentityHashMap<Term, SourceLocation>();
        map.put(term1, loc);
        var sourceMap = SourceMap.of(map);

        assertEquals(loc, sourceMap.lookup(term1));
        assertNull(sourceMap.lookup(term2)); // different identity
        assertEquals(1, sourceMap.size());
    }

    @Test
    void sourceMap_lookup_nullTerm_returnsNull() {
        var map = new IdentityHashMap<Term, SourceLocation>();
        map.put(Term.error(), new SourceLocation("Test.java", 1, 1, "x"));
        var sourceMap = SourceMap.of(map);

        assertNull(sourceMap.lookup(null));
    }

    @Test
    void sourceMap_of_defensivelyCopies() {
        var map = new IdentityHashMap<Term, SourceLocation>();
        var term = Term.error();
        map.put(term, new SourceLocation("Test.java", 1, 1, "x"));
        var sourceMap = SourceMap.of(map);

        // Modify the original map
        map.clear();

        // Source map should still have the entry
        assertNotNull(sourceMap.lookup(term));
        assertEquals(1, sourceMap.size());
    }

    @Test
    void sourceMap_multipleEntries() {
        var term1 = Term.const_(Constant.integer(1));
        var term2 = Term.const_(Constant.integer(2));
        var loc1 = new SourceLocation("A.java", 10, 1, "expr1");
        var loc2 = new SourceLocation("B.java", 20, 5, "expr2");

        var sourceMap = SourceMap.of(Map.of(term1, loc1, term2, loc2));

        assertEquals(loc1, sourceMap.lookup(term1));
        assertEquals(loc2, sourceMap.lookup(term2));
        assertEquals(2, sourceMap.size());
        assertFalse(sourceMap.isEmpty());
    }

    @Test
    void sourceMap_toString_showsSize() {
        var sourceMap = SourceMap.of(Map.of(Term.error(), new SourceLocation("T.java", 1, 1, "x")));
        assertEquals("SourceMap{size=1}", sourceMap.toString());
    }
}
