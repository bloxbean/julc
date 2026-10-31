package org.julclang.difftest;

import org.julclang.compiler.OptimizationLevel;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What each class of the I1 tool means, on one small program each: the gate itself needs the recorded corpora. */
class Adr068LoweringToolTest {

    private static final Path REPO = Path.of(System.getProperty("adr068.repo", "..")).toAbsolutePath().normalize();

    private static Adr068Corpus.Case method(String source) {
        return new Adr068Corpus(REPO).addMethod("tool", source, "m");
    }

    private static Adr068Lowering.Row row(String source) {
        return new Adr068Lowering(Map.of()).classify(method(source));
    }

    private static void assertRow(String kind, int covered, int total, String construct, Adr068Lowering.Row row) {
        assertEquals(kind, row.kind(), row::line);
        assertEquals(covered, row.covered(), row::line);
        assertEquals(total, row.total(), row::line);
        assertEquals(construct, row.construct(), row::line);
    }

    @Test
    void aProgramFullyCovered() {
        var row = row("class P { static long m(long a) { return a; } }");
        assertRow("i1a-identical", 1, 1, "-", row);
        assertTrue(row.fullyCovered());
        assertEquals("fragment", row.provenance(), "the same positions; the reference prints its tree, javac keeps the source");
    }

    /** I1b: a method is compared though the program stops at another. */
    @Test
    void aProgramPartlyCovered() {
        assertRow("not-ported", 1, 2, "PLUS", row("""
                class P {
                    static long helper(long a) { return a; }
                    static long m(long a) { return a + a; }
                }
                """));
    }

    @Test
    void javacRejectsWhatTheReferenceAccepted() {
        assertRow("javac-rejects", 0, 1, "-", row("""
                import java.math.BigInteger;
                class P { static BigInteger m(BigInteger a) { return a + a; } }
                """));
    }

    @Test
    void aJulcRejectionOfWhatTheReferenceAcceptedIsADifference() {
        assertRow("DIFFERENT-javac-rejects", 0, 1, "-", row("""
                import org.julclang.stdlib.annotation.Param;
                class P {
                    @Param static final long K = 5;
                    static long m() { return 1; }
                }
                """));
    }

    @Test
    void aRejectionOfWhatTheReferenceRejectsIsNotAPass() {
        assertRow("reference-rejects-not-ported", 0, 0, "NULL_LITERAL", row("class P { static String m() { return null; } }"));
    }

    @Test
    void aRejectionAlike() {
        assertRow("both-reject", 0, 0, "-", row("interface P { static long m() { return 1; } }"));
    }

    @Test
    void aSourceBothRejectJavacFirst() {
        assertRow("both-reject-javac-first", 0, 0, "-", row("class P { static long m() { return } }"));
    }

    /** A source the reference cannot read and the javac frontend compiles: the row that must be classified by hand. */
    @Test
    void acceptingWhatTheReferenceRejectsIsADifference() {
        assertEquals("DIFFERENT-javac-accepts", row("class P { static long m() { return 1; } } // \\uD800").kind());
    }

    /** The reference is checked against B1 as it runs: a frozen frontend that changed fails, rather than moving a baseline. */
    @Test
    void aReferenceThatIsNoLongerB1IsReported() {
        var c = method("class P { static long m() { return 1; } }");
        var wrong = Map.of(c.key, Map.of(OptimizationLevel.DEFAULT.name(), "A:not-the-frozen-digest:0"));
        assertEquals("REFERENCE-DRIFT", new Adr068Lowering(wrong).classify(c).kind());
    }

    @Test
    void theLedgersEmptyProviderIsCompared() {
        var corpus = new Adr068Corpus(REPO);
        var c = corpus.add("tool", "JavaLibraryProvider", null, "none", null, java.util.List.of());
        var row = new Adr068Lowering(Map.of()).classify(c);
        assertEquals("i1a-identical", row.kind(), row::line);
    }
}
