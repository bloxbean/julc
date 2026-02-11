package com.bloxbean.cardano.julc.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProgramTest {

    @Test
    void plutusV1() {
        var p = Program.plutusV1(Term.error());
        assertEquals(1, p.major());
        assertEquals(0, p.minor());
        assertEquals(0, p.patch());
        assertEquals("1.0.0", p.versionString());
    }

    @Test
    void plutusV2() {
        var p = Program.plutusV2(Term.error());
        assertEquals(1, p.major());
        assertEquals(0, p.minor());
        assertEquals(0, p.patch());
    }

    @Test
    void plutusV3() {
        var p = Program.plutusV3(Term.error());
        assertEquals(1, p.major());
        assertEquals(1, p.minor());
        assertEquals(0, p.patch());
        assertEquals("1.1.0", p.versionString());
    }

    @Test
    void customVersion() {
        var p = new Program(2, 3, 4, Term.error());
        assertEquals(2, p.major());
        assertEquals(3, p.minor());
        assertEquals(4, p.patch());
        assertEquals("2.3.4", p.versionString());
    }

    @Test
    void nullTermRejected() {
        assertThrows(NullPointerException.class, () -> new Program(1, 0, 0, null));
    }

    @Test
    void negativeVersionRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Program(-1, 0, 0, Term.error()));
        assertThrows(IllegalArgumentException.class, () -> new Program(1, -1, 0, Term.error()));
        assertThrows(IllegalArgumentException.class, () -> new Program(1, 0, -1, Term.error()));
    }

    @Test
    void toStringFormat() {
        var p = Program.plutusV3(Term.const_(Constant.integer(42)));
        var s = p.toString();
        assertTrue(s.startsWith("(program 1.1.0 "));
    }

    @Test
    void equality() {
        var p1 = Program.plutusV3(Term.error());
        var p2 = Program.plutusV3(Term.error());
        assertEquals(p1, p2);
        assertEquals(p1.hashCode(), p2.hashCode());
    }

    @Test
    void inequality() {
        var p1 = Program.plutusV1(Term.error());
        var p3 = Program.plutusV3(Term.error());
        assertNotEquals(p1, p3);
    }

    @Test
    void withRealProgram() {
        // (program 1.1.0 (\x -> x))
        var identity = Term.lam("x", Term.var(new NamedDeBruijn("x", 0)));
        var p = Program.plutusV3(identity);
        assertEquals(1, p.major());
        assertEquals(1, p.minor());
        assertInstanceOf(Term.Lam.class, p.term());
    }
}
