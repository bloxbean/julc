package org.julclang.difftest;

import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.frontend.javac.JavacEvidence;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a line of the I1 table reads, on one small program: the gate itself needs the recorded corpora. Each difference is
 * injected, so that each comparison is shown to fail on its own: a frontend that moves a byte, a term, a rejection's words or
 * crashes moves the row or stops the run, and a case that leaves the corpus shows as moved.
 */
class Adr068LoweringToolTest {

    private static final Path REPO = Path.of(System.getProperty("adr068.repo", "..")).toAbsolutePath().normalize();
    private static final String SOURCE = "class P { static boolean h(long a) { return a > 0; } static boolean m(long a) { return h(a); } }";

    private static Adr068Corpus.Case method(String source) {
        return new Adr068Corpus(REPO).addMethod("tool", source, "m");
    }

    /** The real frontend, with what a test changes of what it answers. */
    private static Adr068Lowering.Javac injected(Function<Adr068Lowering.Compilation, Adr068Lowering.Compilation> compilation,
                                                 Function<List<JavacEvidence.Attempt>, List<JavacEvidence.Attempt>> attempts) {
        var real = Adr068Lowering.frontend();
        return new Adr068Lowering.Javac() {
            @Override
            public Adr068Lowering.Compilation compile(Adr068Corpus.Case c, CompilerOptions options) {
                return compilation.apply(real.compile(c, options));
            }

            @Override
            public List<JavacEvidence.Attempt> attempts(Adr068Corpus.Case c, CompilerOptions options) {
                return attempts.apply(real.attempts(c, options));
            }
        };
    }

    private static String line(String source) {
        return new Adr068Lowering().observation(method(source));
    }

    @Test
    void aLineIsDeterministic() {
        assertEquals(line(SOURCE), line(SOURCE));
    }

    @Test
    void aMovedByteMovesTheRow() {
        var moved = injected(c -> new Adr068Lowering.Compilation(Adr068Ledger.Outcome.accepted("0".repeat(32), c.outcome().size(), List.of()),
                c.result(), null), a -> a);
        assertNotEquals(line(SOURCE), new Adr068Lowering(moved).observation(method(SOURCE)));
    }

    @Test
    void aMovedTermMovesTheRowThoughTheBytesAreEqual() {
        var moved = injected(c -> c, attempts -> {
            var swapped = new ArrayList<>(attempts);
            java.util.Collections.reverse(swapped);
            return swapped;
        });
        assertNotEquals(line(SOURCE), new Adr068Lowering(moved).observation(method(SOURCE)));
    }

    @Test
    void anotherSourceIsAnotherRow() {
        String other = SOURCE.replace("a > 0", "a > 1");
        assertNotEquals(line(SOURCE).substring(line(SOURCE).indexOf('\t')), line(other).substring(line(other).indexOf('\t')));
    }

    @Test
    void aRejectionsWordsAreInTheRow() {
        String a = line("class P { static long m(long a) { return \"s\".equals(a) ? null : 1; } }");
        assertTrue(a.contains("R:"), a);
        String b = line("class P { static long m(long a) { return Unknown.value; } }");
        assertNotEquals(a.substring(a.indexOf('\t')), b.substring(b.indexOf('\t')));
    }

    @Test
    void aCrashIsNoRow() {
        var crash = injected(c -> new Adr068Lowering.Compilation(c.outcome(), c.result(), new IllegalStateException("broken")), a -> a);
        assertThrows(AssertionError.class, () -> new Adr068Lowering(crash).observation(method(SOURCE)));
    }

    @Test
    void movedFindsBothDirections() {
        assertEquals(List.of(), Adr068Lowering.moved(Map.of("a", "a\t1"), Map.of("a", "a\t1")));
        assertEquals(List.of("a\t1  ->  a\t2"), Adr068Lowering.moved(Map.of("a", "a\t1"), Map.of("a", "a\t2")));
        assertEquals(List.of("a\t1  ->  (not observed)"), Adr068Lowering.moved(Map.of("a", "a\t1"), Map.of()));
        assertEquals(List.of("(new)  ->  a\t1"), Adr068Lowering.moved(Map.of(), Map.of("a", "a\t1")));
        assertFalse(Adr068Lowering.moved(Map.of("a", "a\t1"), Map.of("b", "b\t1")).isEmpty());
    }
}
