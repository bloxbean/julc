package org.julclang.difftest;

import org.julclang.compiler.CompileResult;
import org.julclang.compiler.CompilerFrontend;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.OptimizationLevel;
import org.julclang.compiler.frontend.javac.JavacEvidence;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;
import org.julclang.core.Constant;
import org.julclang.core.PlutusData;
import org.julclang.core.source.SourceMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
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
        Function<Adr068Corpus.Case, Adr068Corpus.Case> program = c -> c;
        Function<Adr068Lowering.Compilation, Adr068Lowering.Compilation> compilation = c -> c;
        Function<List<JavacEvidence.Attempt>, List<JavacEvidence.Attempt>> attempts = a -> a;

        Injected() {
            this.real = Adr068Lowering.frontend();
        }

        @Override
        public Adr068Lowering.Compilation compile(Adr068Corpus.Case c, CompilerOptions options) {
            return compilation.apply(real.compile(program.apply(source.apply(c)), options));
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
        assertRow("not-ported", 1, 2, "VARIABLE", row("""
                class P {
                    static long helper(long a) { return a; }
                    static long m(long a) { long b = a; return b; }
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

    /** Every cell is compared before anything else, so a row that differs in diagnostics still pins javac's bytes. */
    @Test
    void aRowWithADiagnosticDifferencePinsTheBytes() {
        var warning = new Adr068Ledger.Diagnostic("WARNING", "JULC9999", "injected", "", 1, 1);
        var tested = method("class P { static long m() { return 1; } }");
        var other = method("class P { static long m() { return 2; } }");
        var same = new Injected();
        same.compilation = c -> new Adr068Lowering.Compilation(Adr068Ledger.Outcome.accepted(c.outcome().digest(), c.outcome().size(),
                List.of(warning)), c.result(), null);
        var moved = new Injected();
        moved.compilation = same.compilation;
        moved.program = c -> other;
        var one = same.row(tested);
        var two = moved.row(tested);
        assertEquals("DIFFERENT-bytes", two.kind(), two::line);
        assertEquals(one.covered(), two.covered());
        assertNotEquals(one.line(), two.line());
        assertTrue(two.detail().contains("diagnostics"), two.detail());
    }

    @Test
    void aRowWithAParamDifferencePinsTheBytes() {
        var same = new Injected();
        same.compilation = c -> new Adr068Lowering.Compilation(c.outcome(), withoutParams(c.result()), null);
        var moved = new Injected();
        moved.compilation = same.compilation;
        var other = validator(VALIDATOR.replace("return true", "return false"));
        moved.program = c -> other;
        var one = same.row(validator(VALIDATOR));
        var two = moved.row(validator(VALIDATOR));
        assertEquals("DIFFERENT-bytes", two.kind(), two::line);
        assertEquals(one.covered(), two.covered());
        assertNotEquals(one.line(), two.line());
        assertTrue(two.detail().contains("params"), two.detail());
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

    /** A javac side that compiles {@code other} with a source map, and the real case without one. */
    private static Adr068Lowering.Javac otherWithAMap(Adr068Corpus.Case other) {
        var real = Adr068Lowering.frontend();
        return new Adr068Lowering.Javac() {
            @Override
            public Adr068Lowering.Compilation compile(Adr068Corpus.Case c, CompilerOptions options) {
                return real.compile(options.isSourceMapEnabled() ? other : c, options);
            }

            @Override
            public List<JavacEvidence.Attempt> attempts(Adr068Corpus.Case c, CompilerOptions options) {
                return real.attempts(c, options);
            }
        };
    }

    /** With a source map too, though a row only javac accepts is recorded without one. */
    @Test
    void javacsCellsOfARowOnlyItAcceptsArePinnedWithASourceMap() {
        var source = "class P { static long m() { return 1; } } // \\uD800";
        var tested = method(source);
        var other = method("class P { static long m() { return 2; } } // \\uD800");
        var first = new Adr068Lowering(null).classify(tested).row();
        var second = new Adr068Lowering(null, otherWithAMap(other)).classify(tested).row();
        assertEquals("DIFFERENT-javac-accepts", first.kind(), first::line);
        assertEquals("DIFFERENT-javac-accepts", second.kind(), second::line);
        assertNotEquals(first.line(), second.line());
        for (var level : Adr068Ledger.LEVELS) assertTrue(first.detail().contains(level + " with a map"), first.detail());
    }

    @Test
    void aProgramWithALibraryIsTheRootsAloneWithASourceMap() {
        var root = Adr068Corpus.Source.of("record Bid(long amount) { }\nclass P { static long m() { return 1; } }", null);
        var library = Adr068Corpus.Source.of("record Bid(byte[] amount) { }", null);
        var c = new Adr068Corpus(REPO).add("tool", "compileMethod", "m", "none", root, List.of(library));
        var other = method("class P { static long m() { return 2; } }");
        var row = new Adr068Lowering(null, otherWithAMap(other)).classify(c).row();
        assertEquals("DIFFERENT-root-alone", row.kind(), row::line);
        assertTrue(row.detail().contains("with a map"), row.detail());
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

    /** One code and one message are not one rejection, where the reference gave a position. */
    @Test
    void aRejectionAtAnotherPositionIsReported() {
        String source = """
                import org.julclang.stdlib.annotation.NewType;
                class P {
                    @NewType record Bad(long a, long b) { }
                    static long m() { return 1; }
                }
                """;
        assertEquals("both-reject", row(source).kind(), () -> row(source).line());
        var injected = new Injected();
        var other = method("\n" + source);
        injected.source = c -> other;
        var row = injected.row(method(source));
        assertEquals("DIFFERENT-rejection", row.kind(), row::line);
    }

    /** Only the reference says what a program's methods are, so one it did not lower has no place in the row. */
    @Test
    void aMethodLoweredOnlyByJavacIsReported() {
        var injected = new Injected();
        injected.attempts = attempts -> {
            var more = new ArrayList<>(attempts);
            more.add(new JavacEvidence.Attempt("P.extra", attempts.getFirst().type(), attempts.getFirst().term(), null));
            return more;
        };
        var row = injected.row(method("class P { static long m() { return 1; } }"));
        assertEquals("DIFFERENT-pir", row.kind(), row::line);
        assertTrue(row.detail().contains("P.extra"), row.detail());
    }

    /** The loop over positions skips a term one side lost, so only the sets show it. */
    @Test
    void aLostPositionIsReported() {
        var injected = new Injected();
        injected.compilation = c -> new Adr068Lowering.Compilation(c.outcome(), withoutALocation(c.result()), null);
        var row = injected.row(method("class P { static long m() { return 1; } }"));
        assertEquals("DIFFERENT-provenance", row.kind(), row::line);
        assertTrue(row.detail().contains("located terms"), row.detail());
    }

    private static CompileResult withoutALocation(CompileResult result) {
        if (result == null || result.sourceMap() == null || result.sourceMap().isEmpty()) return result;
        var located = new TreeMap<>(result.sourceMap().toIndexed(result.program().term()));
        located.pollLastEntry();
        return new CompileResult(result.program(), result.diagnostics(), result.params(), result.pirTerm(), result.uplcTerm(),
                SourceMap.reconstruct(located, result.program().term()), result.target(), result.optimizationReport(),
                result.javacProvenance());
    }

    /**
     * On a whole row {@code byteDifference} meets this first, as a cell that differs, so the comparison is asked directly: a
     * program one side rejects with a map has no positions to compare, and that is no agreement.
     */
    @Test
    void onlyOneSideCompilingWithAMapIsReported() {
        var real = Adr068Lowering.frontend();
        var tested = method("class P { static long m() { return 1; } }");
        var rejecting = method("interface P { static long m() { return 1; } }");
        var difference = new Adr068Lowering(null, otherWithAMap(rejecting)).sourceMapDifference(tested);
        assertTrue(difference.contains("only one compiles with a source map"), difference);
        assertEquals("fragment", new Adr068Lowering(null, real).sourceMapDifference(tested));
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

    private static String pinned(String kind, String provenance, String digest) {
        return "k\t" + kind + "\t1/1\t-\t" + provenance + "\t" + digest + "\t";
    }

    private static int shrunk(String frozen, String now) {
        return Adr068LoweringTest.shrunk(Map.of("k", frozen), Map.of("k", now)).size();
    }

    /** F981: javac stopped rejecting a source. No class above gains from that, whatever a rank would say of it. */
    @Test
    void aMoveOutOfJavacRejectsIsAShrink() {
        String rejects = row("k", "javac-rejects", "0/2");
        for (String kind : List.of("not-ported", "i1a-identical", "DIFFERENT-javac-rejects", "both-reject")) {
            assertEquals(1, shrunk(rejects, row("k", kind, "0/2")), kind);
        }
        assertEquals(0, shrunk(rejects, rejects));
        assertEquals(0, shrunk(row("k", "not-ported", "0/2"), row("k", "i1a-identical", "2/2")), "a port is a gain");
    }

    /** F1006: a gate rule's pin is a rejection: losing the rule reads as more coverage, and no rank can say otherwise. */
    @Test
    void aMoveOutOfAListedRejectionIsAShrink() {
        String rejects = row("k", "DIFFERENT-javac-rejects", "1/1");
        for (String kind : List.of("i1a-identical", "not-ported", "javac-rejects", "DIFFERENT-bytes", "both-reject")) {
            assertEquals(1, shrunk(rejects, row("k", kind, "1/1")), kind);
        }
        assertEquals(0, shrunk(rejects, rejects));
    }

    @Test
    void aMoveAmongListedClassesIsAShrink() {
        for (String[] move : List.of(new String[]{"DIFFERENT-bytes", "DIFFERENT-provenance"},
                new String[]{"DIFFERENT-bytes", "DIFFERENT-javac-rejects"},
                new String[]{"DIFFERENT-javac-accepts", "DIFFERENT-rejection"},
                new String[]{"both-reject", "DIFFERENT-javac-accepts"},
                new String[]{"both-reject", "DIFFERENT-rejection"},
                new String[]{"both-reject-javac-first", "DIFFERENT-javac-accepts"})) {
            assertEquals(1, shrunk(row("k", move[0], "1/1"), row("k", move[1], "1/1")), move[0] + " to " + move[1]);
        }
    }

    /** What a slice does: a port, a new rule, and a listed difference that goes. */
    @Test
    void aGainIsNoShrink() {
        for (String[] move : List.of(new String[]{"not-ported", "i1a-identical"},
                new String[]{"not-ported", "DIFFERENT-javac-rejects"},
                new String[]{"reference-rejects-not-ported", "both-reject"},
                new String[]{"reference-rejects-not-ported", "DIFFERENT-javac-accepts"},
                new String[]{"DIFFERENT-bytes", "i1a-identical"},
                new String[]{"DIFFERENT-rejection", "both-reject"})) {
            assertEquals(0, shrunk(row("k", move[0], "1/1"), row("k", move[1], "1/1")), move[0] + " to " + move[1]);
        }
    }

    /** F1009: a case newer than the frozen table has the reference this run computed, and is compared with it. */
    @Test
    void aRowNewerThanTheTableIsComparedWithTheRunsReference() {
        var totals = new TreeMap<String, Integer>();
        var differences = new ArrayList<String>();
        Adr068EncodingsTest.compare("blueprint", Map.of("k", "aa"), Map.of(), Map.of("k", "aa"), totals, differences);
        assertEquals(List.of(), differences);
        assertEquals(Map.of("blueprint.equal, newer than the table", 1), totals);
        Adr068EncodingsTest.compare("blueprint", Map.of("k", "aa"), Map.of(), Map.of("k", "bb"), totals, differences);
        assertEquals(List.of("blueprint\tk\tbb\taa"), differences);
        differences.clear();
        Adr068EncodingsTest.compare("blueprint", Map.of("k", "aa"), Map.of(), Map.of(), totals, differences);
        assertEquals(List.of("blueprint\tk\tno such layout in the reference\taa"), differences);
    }

    /** F982: a listed row is pinned by its digest, and a miscompile of it changes nothing else. */
    @Test
    void aChangedDigestOfARowIsAShrink() {
        for (String kind : List.of("DIFFERENT-bytes", "DIFFERENT-javac-accepts", "DIFFERENT-rejection", "both-reject", "javac-rejects")) {
            assertEquals(1, shrunk(pinned(kind, "-", "aaaa"), pinned(kind, "-", "bbbb")), kind);
            assertEquals(0, shrunk(pinned(kind, "-", "aaaa"), pinned(kind, "-", "aaaa")), kind);
        }
    }

    @Test
    void aChangedProvenanceOfARowIsAShrink() {
        assertEquals(1, shrunk(pinned("DIFFERENT-provenance", "same", "aaaa"), pinned("DIFFERENT-provenance", "fragment", "aaaa")));
        assertEquals(1, shrunk(pinned("i1a-identical", "same", "-"), pinned("i1a-identical", "fragment", "-")));
        assertEquals(0, shrunk(pinned("i1a-identical", "fragment", "-"), pinned("i1a-identical", "same", "-")), "a gain");
    }

    /** F992: a table compared with itself says nothing. */
    @Test
    void theEvidenceDirectoryIsNoOutputDirectory(@TempDir Path evidence) throws IOException {
        assertThrows(IllegalArgumentException.class, () -> Adr068LoweringTest.refuseTheEvidenceDirectory(evidence, evidence));
        assertThrows(IllegalArgumentException.class,
                () -> Adr068LoweringTest.refuseTheEvidenceDirectory(evidence.resolve("."), evidence));
        Adr068LoweringTest.refuseTheEvidenceDirectory(evidence.resolve("out"), evidence);
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

    /** Spelled to look like the one that is a rejection. */
    private static final class CompilerException extends RuntimeException {
    }

    /** A javac side that fails on every compilation with {@code thrown}. */
    private static Adr068Lowering.Javac throwing(Throwable thrown) {
        var real = Adr068Lowering.frontend();
        return new Adr068Lowering.Javac() {
            @Override
            public Adr068Lowering.Compilation compile(Adr068Corpus.Case c, CompilerOptions options) {
                return new Adr068Lowering.Compilation(Adr068Ledger.rejection(thrown), null, null, thrown);
            }

            @Override
            public List<JavacEvidence.Attempt> attempts(Adr068Corpus.Case c, CompilerOptions options) {
                return real.attempts(c, options);
            }
        };
    }

    /** F991: on the whole-program path too, only a {@code CompilerException} is a rejection, by type and not by name. */
    @Test
    void aCrashOfAWholeProgramFailsTheTool() {
        var c = method("class P { static long m() { return 1; } }");
        for (var crash : List.of(new NullPointerException("crash"), new IllegalStateException("I13: crash"),
                new StackOverflowError(), new CompilerException())) {
            var failure = assertThrows(AssertionError.class, () -> new Adr068Lowering(null, throwing(crash)).classify(c), crash::toString);
            assertEquals(crash, failure.getCause());
        }
    }

    @Test
    void aRejectionOfAWholeProgramIsARow() {
        var c = method("class P { static long m() { return 1; } }");
        var rejection = new org.julclang.compiler.CompilerException("a rejection of the frontend's own");
        assertEquals("DIFFERENT-javac-rejects", new Adr068Lowering(null, throwing(rejection)).classify(c).row().kind());
    }

    /** A provider refuses what it cannot export with this type, on both frontends. A program has no such rejection. */
    @Test
    void anIllegalArgumentIsARejectionOfAProviderAlone() {
        var refused = new IllegalArgumentException("Only @OnchainLibrary sources can be exported: Ordinary");
        var source = Adr068Corpus.Source.of("class Ordinary { }", null);
        var provider = new Adr068Corpus(REPO).add("tool", "JavaLibraryProvider", null, "none", null, List.of(source));
        assertEquals("both-reject", new Adr068Lowering(null).classify(provider).row().kind());
        new Adr068Lowering(null, throwing(refused)).classify(provider);
        var c = method("class P { static long m() { return 1; } }");
        assertThrows(AssertionError.class, () -> new Adr068Lowering(null, throwing(refused)).classify(c));
        assertThrows(AssertionError.class, () -> new Adr068Lowering(null, throwing(new NullPointerException("crash"))).classify(provider));
    }

    @Test
    void theFrontendReportsWhatItThrew() {
        var c = new Adr068Corpus(REPO).add("tool", "no such entry", "m", "none",
                Adr068Corpus.Source.of("class P { static long m() { return 1; } }", null), List.of());
        var thrown = Adr068Lowering.frontend().compile(c, new CompilerOptions()).thrown();
        assertTrue(thrown != null && !(thrown instanceof org.julclang.compiler.CompilerException), () -> String.valueOf(thrown));
    }

    @Test
    void aCrashOfOneMethodFailsTheTool() {
        var failing = new Adr068Lowering.Javac() {
            @Override
            public Adr068Lowering.Compilation compile(Adr068Corpus.Case c, CompilerOptions options) {
                return Adr068Lowering.frontend().compile(c, options);
            }

            @Override
            public List<JavacEvidence.Attempt> attempts(Adr068Corpus.Case c, CompilerOptions options) {
                throw new NullPointerException("crash");
            }
        };
        assertThrows(NullPointerException.class,
                () -> new Adr068Lowering(null, failing).classify(method("class P { static long m() { return 1; } }")));
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

    private static String library(String body) {
        return """
                import java.math.BigInteger;
                import org.julclang.core.types.JulcScalars;
                import org.julclang.stdlib.Builtins;
                import org.julclang.stdlib.annotation.OnchainLibrary;

                @OnchainLibrary
                public class Lib {
                    public static BigInteger n() { %s return BigInteger.ONE; }
                }
                """.formatted(body);
    }

    private static String providerCell(String text) {
        var sources = List.of(Adr068Corpus.Source.of(text, null));
        var c = new Adr068Corpus(REPO).add("tool", "JavaLibraryProvider", null, Adr068Corpus.STDLIB_LOOKUP, null, sources);
        return new Adr068Lowering(null).classify(c).reference().cells().getFirst();
    }

    /** F983: a provider's cell holds every constant of what it describes, where a printer for people elides a list. */
    @Test
    void aProviderCellSpellsOutItsConstants() {
        assertNotEquals(providerCell(library("var s = Builtins.scalars(BigInteger.ONE, BigInteger.TWO);")),
                providerCell(library("var s = Builtins.scalars(BigInteger.TWO, BigInteger.TEN);")));
    }

    @Test
    void aSpecializationSpellsOutItsConstants() {
        var one = new PirTerm.Const(new Constant.DataConst(PlutusData.integer(1)));
        var other = new PirTerm.Const(new Constant.DataConst(PlutusData.integer(2)));
        assertNotEquals(Adr068Ledger.specialization("n", "type", Map.of("d", one)),
                Adr068Ledger.specialization("n", "type", Map.of("d", other)));
    }

    @Test
    void theLedgersEmptyProviderIsCompared() {
        var corpus = new Adr068Corpus(REPO);
        var c = corpus.add("tool", "JavaLibraryProvider", null, "none", null, java.util.List.of());
        var row = new Adr068Lowering(null).classify(c).row();
        assertEquals("i1a-identical", row.kind(), row::line);
    }

    /** A program with a library stops at the unit, so what a call site does shows only in the method's own attempt (I1b). */
    private static String construct(String library, String caller) {
        var root = "import lib.L;\nclass P {\n    " + caller + "\n}\n";
        var unit = "package lib;\nimport org.julclang.stdlib.annotation.OnchainLibrary;\n@OnchainLibrary\npublic class L {\n    " + library + "\n}\n";
        return JavacEvidence.attempts(root, List.of(unit), "m", null, new CompilerOptions().setFrontend(CompilerFrontend.JAVAC))
                .getFirst().construct();
    }

    @Test
    void aVarargsLibraryMethodDoesNotLower() {
        assertEquals("a call of a varargs method", construct("public static long f(long a, byte... rest) { return a; }",
                "static long m() { return L.f(1); }"));
    }

    @Test
    void aFixedLibraryMethodLowersAtItsCallSite() {
        assertEquals(null, construct("public static long f(long a) { return a; }", "static long m() { return L.f(1); }"));
    }
}
