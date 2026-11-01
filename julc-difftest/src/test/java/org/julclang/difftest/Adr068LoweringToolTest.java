package org.julclang.difftest;

import org.julclang.compiler.CompileResult;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.frontend.javac.JavacEvidence;
import org.julclang.compiler.pir.PirType;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What each class of the I1 tool means, on one small program each: the gate itself needs the recorded corpora. A class
 * that needs the frontends to differ gets the difference injected: the tool is run against a javac side that returns
 * something else, so each comparison is shown to fail on its own.
 */
class Adr068LoweringToolTest {

    private static final Path REPO = Path.of(System.getProperty("adr068.repo", "..")).toAbsolutePath().normalize();

    private static Adr068Corpus.Case method(String source) {
        return new Adr068Corpus(REPO).addMethod("tool", source, "m");
    }

    private static Adr068Lowering.Row row(String source) {
        return new Adr068Lowering(null).classify(method(source)).row();
    }

    /** The real frontend on a case, with what a test changes of it. */
    private static final class Injected implements Adr068Lowering.Javac {
        private final Adr068Lowering.Javac real;
        Function<Adr068Corpus.Case, Adr068Corpus.Case> source = c -> c;
        Function<Adr068Lowering.Compilation, Adr068Lowering.Compilation> compilation = c -> c;
        Function<List<JavacEvidence.Attempt>, List<JavacEvidence.Attempt>> attempts = a -> a;

        Injected() {
            this.real = Adr068Lowering.frontend();
        }

        @Override
        public Adr068Lowering.Compilation compile(Adr068Corpus.Case c, CompilerOptions options) {
            return compilation.apply(real.compile(source.apply(c), options));
        }

        @Override
        public List<JavacEvidence.Attempt> attempts(Adr068Corpus.Case c, CompilerOptions options) {
            return attempts.apply(real.attempts(source.apply(c), options));
        }

        Adr068Lowering.Row row(Adr068Corpus.Case c) {
            return new Adr068Lowering(null, this).classify(c).row();
        }
    }

    private static final String VALIDATOR = """
            import org.julclang.core.PlutusData;
            import org.julclang.ledger.ScriptContext;
            import org.julclang.stdlib.annotation.*;

            @SpendingValidator
            class V {
                @Param static byte[] owner;
                @Entrypoint
                static boolean validate(PlutusData redeemer, ScriptContext ctx) { return true; }
            }
            """;

    private static Adr068Corpus.Case validator(String source) {
        return new Adr068Corpus(REPO).add("tool", "compile", null, "none", Adr068Corpus.Source.of(source, null), List.of());
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

    // --- An injected difference, one per comparison ---

    @Test
    void aDifferenceInBytesIsReported() {
        var injected = new Injected();
        var other = method("class P { static long m() { return 2; } }");
        injected.source = c -> other;
        var row = injected.row(method("class P { static long m() { return 1; } }"));
        assertEquals("DIFFERENT-bytes", row.kind(), row::line);
    }

    /** At one level only, and only with a source map: every cell is compared. */
    @Test
    void aDifferenceAtOneLevelWithASourceMapIsReported() {
        var other = method("class P { static long m() { return 2; } }");
        var tested = method("class P { static long m() { return 1; } }");
        var level = Adr068Ledger.LEVELS.getLast();
        var real = Adr068Lowering.frontend();
        var row = new Adr068Lowering(null, new Adr068Lowering.Javac() {
            @Override
            public Adr068Lowering.Compilation compile(Adr068Corpus.Case c, CompilerOptions options) {
                boolean atThatCell = options.getOptimizationLevel() == level && options.isSourceMapEnabled();
                return real.compile(atThatCell ? other : c, options);
            }

            @Override
            public List<JavacEvidence.Attempt> attempts(Adr068Corpus.Case c, CompilerOptions options) {
                return real.attempts(c, options);
            }
        }).classify(tested).row();
        assertEquals("DIFFERENT-bytes", row.kind(), row::line);
        assertTrue(row.detail().contains(level + " with a map"), row.detail());
    }

    @Test
    void aDifferenceInDiagnosticsIsReported() {
        var injected = new Injected();
        injected.compilation = c -> new Adr068Lowering.Compilation(Adr068Ledger.Outcome.accepted(c.outcome().digest(), c.outcome().size(),
                List.of(new Adr068Ledger.Diagnostic("WARNING", "JULC9999", "injected", "", 1, 1))), c.result(), null);
        var row = injected.row(method("class P { static long m() { return 1; } }"));
        assertEquals("DIFFERENT-bytes", row.kind(), row::line);
        assertTrue(row.detail().startsWith("diagnostics"), row.detail());
    }

    @Test
    void aDifferenceInParamInfosIsReported() {
        var injected = new Injected();
        injected.compilation = c -> new Adr068Lowering.Compilation(c.outcome(), withoutParams(c.result()), null);
        var row = injected.row(validator(VALIDATOR));
        assertEquals("DIFFERENT-bytes", row.kind(), row::line);
        assertTrue(row.detail().startsWith("params"), row.detail());
    }

    private static CompileResult withoutParams(CompileResult result) {
        return new CompileResult(result.program(), result.diagnostics(), List.of(), result.pirTerm(), result.uplcTerm(),
                result.sourceMap(), result.target(), result.optimizationReport(), result.javacProvenance());
    }

    @Test
    void aMethodLoweredToAnotherTermIsReported() {
        var injected = new Injected();
        var other = method("class P { static long m(long a, long b) { return b; } }");
        injected.attempts = attempts -> Adr068Lowering.frontend().attempts(other, new CompilerOptions());
        var row = injected.row(method("class P { static long m(long a, long b) { return a; } }"));
        assertEquals("DIFFERENT-pir", row.kind(), row::line);
    }

    @Test
    void aMethodLoweredToAnotherTypeIsReported() {
        var injected = new Injected();
        injected.attempts = attempts -> attempts.stream().map(attempt -> new JavacEvidence.Attempt(attempt.binder(),
                new PirType.BoolType(), attempt.term(), attempt.construct())).toList();
        var row = injected.row(method("class P { static long m() { return 1; } }"));
        assertEquals("DIFFERENT-pir", row.kind(), row::line);
    }

    @Test
    void aProgramAcceptedWithMethodsNotLoweredIsReported() {
        var injected = new Injected();
        injected.attempts = attempts -> List.of();
        var row = injected.row(method("class P { static long m() { return 1; } }"));
        assertEquals("DIFFERENT-coverage", row.kind(), row::line);
    }

    /** A line more moves every position and no byte. */
    @Test
    void aMovedPositionIsReported() {
        var injected = new Injected();
        var other = method("\n\nclass P { static long m() { return 1; } }");
        injected.source = c -> other;
        var row = injected.row(method("class P { static long m() { return 1; } }"));
        assertEquals("DIFFERENT-provenance", row.kind(), row::line);
        assertTrue(row.detail().contains(":"), row.detail());
    }

    /** Every moved term is in the row's digest, not only the first. */
    @Test
    void everyMovedPositionIsReported() {
        var injected = new Injected();
        var other = method("\n\nclass P { static long m() { return 1; } }");
        injected.source = c -> other;
        var row = injected.row(method("class P { static long m() { return 1; } }"));
        for (var level : Adr068Ledger.LEVELS) assertTrue(row.detail().contains(level.name()), row.detail());
    }

    /** What javac produced of a row only it accepts is in the row: another program moves it. */
    @Test
    void javacsCellsOfARowOnlyItAcceptsArePinned() {
        var source = "class P { static long m() { return 1; } } // \\uD800";
        var first = row(source);
        var injected = new Injected();
        var other = method("class P { static long m() { return 2; } } // \\uD800");
        injected.source = c -> other;
        var second = injected.row(method(source));
        assertEquals("DIFFERENT-javac-accepts", first.kind());
        assertEquals("DIFFERENT-javac-accepts", second.kind());
        assertNotEquals(first.line(), second.line());
        for (var level : Adr068Ledger.LEVELS) assertTrue(first.detail().contains(level.name()), first.detail());
    }

    /** D8's oracle: where a pool unit is all the reference objects to, javac's program is the root alone's. */
    @Test
    void aProgramWithALibraryIsTheRootsAlone() {
        var root = Adr068Corpus.Source.of("record Bid(long amount) { }\nclass P { static long m() { return 1; } }", null);
        var library = Adr068Corpus.Source.of("record Bid(byte[] amount) { }", null);
        var c = new Adr068Corpus(REPO).add("tool", "compileMethod", "m", "none", root, List.of(library));
        assertEquals("DIFFERENT-javac-accepts", new Adr068Lowering(null).classify(c).row().kind());

        var injected = new Injected();
        var other = method("class P { static long m() { return 2; } }");
        injected.source = ignored -> other;
        var row = injected.row(c);
        assertEquals("DIFFERENT-root-alone", row.kind(), row::line);
    }

    /** A rejection of javac's own is pinned by what it says and where, not only by its code. */
    @Test
    void javacsOwnRejectionIsPinnedInFull() {
        var first = row("class P { static long m() { return } }");
        var second = row("class P { static long m() { return; } long n = ; }");
        assertEquals("both-reject-javac-first", first.kind());
        assertEquals("both-reject-javac-first", second.kind());
        assertNotEquals(first.line().split("\t")[5], second.line().split("\t")[5]);
    }

    /** A row's digest covers all of its detail, however little of it is shown. */
    @Test
    void aDifferenceBeyondWhatIsShownMovesTheRow() {
        String shared = "x".repeat(1000);
        var one = new Adr068Lowering.Row("k", "DIFFERENT-bytes", 0, 0, "-", "-", shared + "a", List.of(), "-");
        var other = new Adr068Lowering.Row("k", "DIFFERENT-bytes", 0, 0, "-", "-", shared + "b", List.of(), "-");
        assertNotEquals(one.line(), other.line());
        assertTrue(one.line().length() < 400, one.line());
    }

    // --- The tables ---

    private static Map<String, String> committed(Adr068Corpus.Case c) {
        return new TreeMap<>(Map.of(c.key, new Adr068Lowering(null).classify(c).reference().line()));
    }

    private static String tampered(String line, int column) {
        var cells = line.split("\t", -1);
        cells[column] = "tampered";
        return String.join("\t", cells);
    }

    /** The reference is checked against the table in every cell of every row, so a frozen frontend that changed fails. */
    @Test
    void aReferenceThatMovedIsReportedInEveryColumn() {
        var c = method("class P { static long m() { return 1; } }");
        var frozen = committed(c);
        assertEquals("i1a-identical", new Adr068Lowering(frozen).classify(c).row().kind());
        int columns = Adr068Lowering.Reference.HEADER.split("\t").length;
        for (int column = 1; column < columns; column++) {
            var changed = Map.of(c.key, tampered(frozen.get(c.key), column));
            var row = new Adr068Lowering(changed).classify(c).row();
            assertEquals("REFERENCE-DRIFT", row.kind(), "column " + column);
        }
    }

    @Test
    void aCaseTheReferenceTableDoesNotHoldIsReported() {
        var c = method("class P { static long m() { return 1; } }");
        assertEquals("REFERENCE-DRIFT", new Adr068Lowering(Map.of()).classify(c).row().kind());
    }

    /** A row the javac frontend does not reach is held still all the same. */
    @Test
    void aReferenceThatMovedIsReportedOnARowJavacDoesNotReach() {
        var c = method("class P { static long m(long a) { return a + a; } }");
        var frozen = committed(c);
        assertEquals("not-ported", new Adr068Lowering(frozen).classify(c).row().kind());
        var changed = Map.of(c.key, tampered(frozen.get(c.key), 2));
        assertEquals("REFERENCE-DRIFT", new Adr068Lowering(changed).classify(c).row().kind());
    }

    @Test
    void aMethodTheReferenceLowersDifferentlyMovesTheTable() {
        var one = new Adr068Lowering(null).classify(method("class P { static long m(long a, long b) { return a; } }")).reference();
        var other = new Adr068Lowering(null).classify(method("class P { static long m(long a, long b) { return b; } }")).reference();
        assertNotEquals(one.methods(), other.methods());
    }

    private static String row(String key, String kind, String covered) {
        return key + "\t" + kind + "\t" + covered + "\t-\t-\t-\t";
    }

    /** F926: a row that moves to a class that compares less has shrunk, though it stays in the table. */
    @Test
    void aMoveDownIsAShrink() {
        var frozen = Map.of("k", row("k", "both-reject", "0/0"));
        var moved = Map.of("k", row("k", "reference-rejects-not-ported", "0/0"));
        assertEquals(1, Adr068LoweringTest.shrunk(frozen, moved).size());
        assertEquals(0, Adr068LoweringTest.shrunk(moved, frozen).size(), "the other way is a gain");
        assertEquals(1, Adr068LoweringTest.shrunk(Map.of("k", row("k", "i1a-identical", "1/1")),
                Map.of("k", row("k", "DIFFERENT-bytes", "1/1"))).size());
        assertEquals(1, Adr068LoweringTest.shrunk(Map.of("k", row("k", "not-ported", "1/2")),
                Map.of("k", row("k", "javac-rejects", "1/2"))).size());
        assertEquals(1, Adr068LoweringTest.shrunk(Map.of("k", row("k", "not-ported", "1/2")),
                Map.of("k", row("k", "not-ported", "0/2"))).size());
        assertEquals(1, Adr068LoweringTest.shrunk(Map.of("k", row("k", "not-ported", "1/2")), Map.of()).size());
    }

    @Test
    void everyClassTheToolWritesHasARank() {
        for (String kind : List.of("i1a-identical", "both-reject", "both-reject-javac-first", "not-ported",
                "reference-rejects-not-ported", "javac-rejects", "ledger-source", "unreplayable", "REFERENCE-DRIFT",
                "DIFFERENT-bytes", "DIFFERENT-pir", "DIFFERENT-coverage", "DIFFERENT-provenance", "DIFFERENT-rejection",
                "DIFFERENT-javac-rejects", "DIFFERENT-javac-accepts", "DIFFERENT-root-alone")) {
            assertTrue(Adr068Lowering.rank(kind) >= 0, kind);
        }
        assertThrows(IllegalArgumentException.class, () -> Adr068Lowering.rank("no such class"));
    }

    private static final String ENTRY = "k\tDIFFERENT-bytes\tCost change (D8): the bytes differ, and no budget grows";

    @Test
    void anAcceptedEntryNamesADifferenceAndItsClass() {
        assertEquals(Map.of("k", "DIFFERENT-bytes"), Adr068Lowering.acceptedDifferences(List.of("# a comment", "", ENTRY)));
        assertEquals(Map.of("k", "DIFFERENT-javac-accepts"), Adr068Lowering.acceptedDifferences(
                List.of("k\tDIFFERENT-javac-accepts\tAccepted by decision (ADR-065 I2c, scheduled at M0): an escape shape")));
    }

    @Test
    void anEntryWithoutAD13ClassIsRefused() {
        for (String line : List.of("k\tDIFFERENT-bytes\tno idea, wave it through",
                "k\tDIFFERENT-bytes\tno idea: wave it through",
                "k\tDIFFERENT-javac-accepts\tNEEDS A DECISION: D8",
                "k\tDIFFERENT-bytes\t",
                "k\tDIFFERENT-bytes")) {
            assertThrows(IllegalArgumentException.class, () -> Adr068Lowering.acceptedDifferences(List.of(line)), line);
        }
    }

    /** A class that cannot explain the row is not an explanation: bytes that differ are not a diagnostic change. */
    @Test
    void anEntryWhoseClassCannotExplainItsRowIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> Adr068Lowering.acceptedDifferences(
                List.of("k\tDIFFERENT-bytes\tDiagnostic change: not this")));
        assertThrows(IllegalArgumentException.class, () -> Adr068Lowering.acceptedDifferences(
                List.of("k\tDIFFERENT-root-alone\tAccepted by decision (D8): not this")));
    }

    @Test
    void aCaseListedTwiceIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> Adr068Lowering.acceptedDifferences(List.of(ENTRY, ENTRY)));
    }

    /** A crash or a failed I13 check is the gate's failure, not a rejection that reads as no methods attempted. */
    @Test
    void aFailedBindingCheckFailsTheTool() {
        var failing = new Adr068Lowering.Javac() {
            @Override
            public Adr068Lowering.Compilation compile(Adr068Corpus.Case c, CompilerOptions options) {
                return Adr068Lowering.frontend().compile(c, options);
            }

            @Override
            public List<JavacEvidence.Attempt> attempts(Adr068Corpus.Case c, CompilerOptions options) {
                throw new IllegalStateException("I13: 'x', built for x, resolves to a binder of y");
            }
        };
        var thrown = assertThrows(IllegalStateException.class,
                () -> new Adr068Lowering(null, failing).classify(method("class P { static long m() { return 1; } }")));
        assertTrue(thrown.getMessage().startsWith("I13"), thrown.getMessage());
    }

    /** One method's rejection leaves the others compared. */
    @Test
    void aMethodIsComparedThoughAnotherIsRejected() {
        assertRow("both-reject", 1, 1, "-", row("""
                class P {
                    static long keep(long z) { return z; }
                    static long other;
                    static long a() { return other; }
                    static long m() { return 1; }
                }
                """));
    }

    @Test
    void theLedgersEmptyProviderIsCompared() {
        var corpus = new Adr068Corpus(REPO);
        var c = corpus.add("tool", "JavaLibraryProvider", null, "none", null, java.util.List.of());
        var row = new Adr068Lowering(null).classify(c).row();
        assertEquals("i1a-identical", row.kind(), row::line);
    }
}
