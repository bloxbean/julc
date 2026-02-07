package com.bloxbean.cardano.plutus.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NamedDeBruijnTest {

    @Test
    void createWithNameAndIndex() {
        var ndb = new NamedDeBruijn("x", 1);
        assertEquals("x", ndb.name());
        assertEquals(1, ndb.index());
    }

    @Test
    void createWithIndexOnly() {
        var ndb = new NamedDeBruijn(5);
        assertEquals("i5", ndb.name());
        assertEquals(5, ndb.index());
    }

    @Test
    void createWithZeroIndex() {
        var ndb = new NamedDeBruijn(0);
        assertEquals("i0", ndb.name());
        assertEquals(0, ndb.index());
    }

    @Test
    void toStringFormat() {
        assertEquals("x_1", new NamedDeBruijn("x", 1).toString());
        assertEquals("i0_0", new NamedDeBruijn(0).toString());
    }

    @Test
    void equality() {
        var a = new NamedDeBruijn("x", 1);
        var b = new NamedDeBruijn("x", 1);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void inequalityByIndex() {
        assertNotEquals(new NamedDeBruijn("x", 1), new NamedDeBruijn("x", 2));
    }

    @Test
    void inequalityByName() {
        assertNotEquals(new NamedDeBruijn("x", 1), new NamedDeBruijn("y", 1));
    }

    @Test
    void negativeIndexRejected() {
        assertThrows(IllegalArgumentException.class, () -> new NamedDeBruijn("x", -1));
        assertThrows(IllegalArgumentException.class, () -> new NamedDeBruijn(-1));
    }
}
