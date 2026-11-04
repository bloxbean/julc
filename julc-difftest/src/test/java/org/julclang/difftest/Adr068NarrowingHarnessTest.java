package org.julclang.difftest;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.ByteRange;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import org.julclang.compiler.CompilerFrontend;
import org.julclang.core.PlutusData;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.julclang.difftest.Adr068CallsHarnessTest.assertAgree;
import static org.julclang.difftest.Adr068CallsHarnessTest.bytes;
import static org.julclang.difftest.Adr068CallsHarnessTest.integer;
import static org.julclang.difftest.Adr068CallsHarnessTest.list;
import static org.julclang.difftest.Adr068CallsHarnessTest.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-068 r18: a {@code (int)} of a {@code long} against the JVM. Where the wrap is removed, each frontend gives the JVM's
 * value, and the same bytes and cost as the other ({@link Adr068CallsHarnessTest#assertAgree}). Where it stays, the javac
 * frontend gives the JVM's value, and the reference's erasure may not.
 */
class Adr068NarrowingHarnessTest {

    private static final String LIBRARY = "import org.julclang.stdlib.lib.ByteStringLib;";
    private static final String NIBBLE = """
            static int nibble(byte[] self, int index) {
                int byteVal = (int) ByteStringLib.at(self, index / 2);
                if (index % 2 == 0) {
                    return byteVal / 16;
                } else {
                    return byteVal % 16;
                }
            }
            public static long m(byte[] self, int index) { return nibble(self, index); }
            """;

    private static void assertRemoved(String members, PlutusData... args) {
        assertAgree(run(LIBRARY, members, List.of(args)));
    }

    /** The javac frontend agrees with the JVM at every level, whatever the reference's erasure does. */
    private static void assertJavacAgrees(String members, PlutusData... args) {
        var report = run(LIBRARY, members, List.of(args));
        var javac = report.results().entrySet().stream().filter(e -> e.getKey().frontend() == CompilerFrontend.JAVAC).toList();
        assertEquals(4, javac.size());
        javac.forEach(e -> assertEquals(Verdict.Kind.AGREE, e.getValue().verdict().kind(), report::describe));
    }

    /** {@code MerklePatriciaForestry.nibble}, as written in the external examples, on each byte's two nibbles. */
    @Property(tries = 100)
    void aNibbleIsJavas(@ForAll @Size(min = 1, max = 4) List<@ByteRange(min = -128, max = 127) Byte> path, @ForAll @IntRange(min = 0, max = 7) int index) {
        var values = new int[path.size()];
        for (int i = 0; i < values.length; i++) values[i] = path.get(i);
        // Past the end the operand fails, as ByteStringLib.at does on the JVM.
        assertRemoved(NIBBLE, bytes(values), integer(index));
    }

    @Test
    void aReadByteIsUnsigned() {
        for (int value : new int[]{0, 1, 127, 128, 255}) {
            assertRemoved("public static long m(byte[] b) { return (int) ByteStringLib.at(b, 0); }", bytes(value));
            assertRemoved("public static long m(byte[] b) { return (int) Builtins.indexByteString(b, 0); }", bytes(value));
            assertRemoved("public static long m(byte[] b) { long v = ByteStringLib.at(b, 0); int x = (int) v; return x - 256; }", bytes(value));
        }
    }

    /** The operand runs once and first, with its trace, and fails where it fails. */
    @Test
    void theOperandRuns() {
        assertRemoved("static long h(byte[] b) { saidL(\"H\", 1); return ByteStringLib.at(b, 0); } public static long m(byte[] b) { return saidL(\"L\", 1) + (int) h(b) + saidL(\"R\", 1); }", bytes(7));
        assertRemoved("public static long m(byte[] b) { return (int) ByteStringLib.at(b, 1); }", bytes(7));
        assertRemoved("public static long m(byte[] b) { return (int) Builtins.indexByteString(b, -1); }", bytes(7));
    }

    @Test
    void aRemainderInRangeIsJavas() {
        for (long x : new long[]{0, 1, -1, 15, 16, -16, -17, 2147483647L, -2147483648L, 4294967296L, -4294967297L}) {
            assertRemoved("public static long m(long x) { return (int) (x % 16); }", integer(x));
            assertRemoved("public static long m(long x) { return (int) (x % 2147483648L); }", integer(x));
        }
    }

    /** Out of range the wrap keeps Java's value, where the reference's erasure gives another. */
    @Test
    void aWrapThatStaysIsJavas() {
        assertJavacAgrees("public static long m(long x) { return (int) (x % 4294967296L); }", integer(4294967295L));
        assertJavacAgrees("public static long m(byte[] b) { long v = ByteStringLib.at(b, 0); for (long y : JulcList.of(4294967296L)) { v = v * y; } return (int) v; }", bytes(3));
        assertJavacAgrees("public static long m(boolean c, byte[] b, long x) { long v = c ? ByteStringLib.at(b, 0) : x; return (int) v; }",
                new PlutusData.ConstrData(0, List.of()), bytes(3), integer(4294967301L));
        assertJavacAgrees("static long id(long x) { return x; } public static long m(long x) { return (int) id(x); }", integer(4294967301L));
        assertJavacAgrees("static long r(long n) { if (n == 0) { return 4294967301L; } else { return r(n - 1); } } public static long m(long n) { return (int) r(n); }", integer(2));

        var erased = run(LIBRARY, "public static long m(long x) { return (int) (x % 4294967296L); }", List.of(integer(4294967295L)));
        assertTrue(erased.results().entrySet().stream().anyMatch(e -> e.getKey().frontend() == CompilerFrontend.JAVAPARSER
                && e.getValue().verdict().kind() != Verdict.Kind.AGREE), erased::describe);
    }
}
