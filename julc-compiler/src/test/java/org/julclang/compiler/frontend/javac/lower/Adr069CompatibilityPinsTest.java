package org.julclang.compiler.frontend.javac.lower;

import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.OptimizationLevel;
import org.julclang.stdlib.StdlibRegistry;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

/**
 * ADR-069 I4: what compiles before the change compiles to the same thing after it. Each program here declares an
 * {@code instanceof} pattern variable; its bytes, diagnostics and source positions at every level, with and without a
 * source map, are frozen ({@link FrozenReference}), and so is its PIR text. Unlike the table's other rows, these are not the
 * JavaParser reference's answers: they are the javac frontend's at ADR-069's M0, recorded before the change, as I4's pins.
 * A milestone that moves one of the compiled rows, or one of the build-order rows, has broken I4. The rows of
 * {@link #anAssignmentRejectedToday} are the exception: D8 changes their first diagnostic on purpose.
 */
class Adr069CompatibilityPinsTest {

    private static final String SOURCE = """
            import java.math.BigInteger;
            import org.julclang.core.types.JulcList;
            import org.julclang.stdlib.Builtins;
            class C {
                sealed interface S { record A(BigInteger v) implements S { } record B(BigInteger v, BigInteger w) implements S { } record D() implements S { } }
                static JulcList<S> xs() { return JulcList.of(new S.A(BigInteger.TWO), new S.D()); }
                static BigInteger said(String m, BigInteger v) { return Builtins.unIData(Builtins.trace(m, Builtins.iData(v))); }
                %s
            }
            """;

    private static String source(String members) {
        return SOURCE.formatted(members);
    }

    /** A variable that is never used: the test is a bare tag test today, at every hook D2 names. */
    @ParameterizedTest
    @ValueSource(strings = {
            "static boolean m(S s, long n) { return !(s instanceof S.A a); }",
            "static boolean m(S s, long n) { return s instanceof S.A a && n > 0; }",
            "static long m(S s, long n) { if (s instanceof S.A a && n > 0) { return 1; } return 0; }",
            "static long m(S s, long n) { if ((s instanceof S.A a)) { return 1; } return 0; }",
            "static long m(S s, long n) { return s instanceof S.A a ? 1 : 0; }",
            "static boolean m(S s, long n) { return xs().any(s instanceof S.A a ? x -> n > 0 : x -> false); }",
            "static long m(S s, long n) { long i = 0; while (s instanceof S.A a && i < n) { i = i + 1; } return i; }",
            "static long m(S s, long n) { long t = 0; for (S x : xs()) { if (x instanceof S.A a) { t = t + 1; } } return t; }",
            "static long m(S s, long n) { long t = 0; for (S x : xs()) { if (x instanceof S.A a && n > 0) { t = t + 1; } } return t; }",
            "static boolean m(S s, long n) { return s instanceof S.A a || n > 0; }",
            "static long m(S s, long n) { if (n > 0 ? s instanceof S.A a : s instanceof S.B b) { return 1; } return 0; }",
            "static boolean m(S s, long n) { return xs().any(x -> x instanceof S.A a && n > 0); }",
            // LoopBody's other routes: breakingIf's fold branch, its in-place branch, joined, and two accumulators.
            "static long m(S s, long n) { long t = 0; for (S x : xs()) { if (x instanceof S.A a && n > 0) { t = t + 1; } if (t > 5) { break; } } return t; }",
            "static long m(S s, long n) { long t = 0; for (S x : xs()) { if (x instanceof S.A a && n > 0) { break; } t = t + 1; } return t; }",
            "static long m(S s, long n) { long t = 0; for (S x : xs()) { if (x instanceof S.A a && n > 0) { t = t + 1; } else { break; } t = t + 2; } return t; }",
            "static long m(S s, long n) { long t = 0; long u = 0; for (S x : xs()) { if (x instanceof S.A a && n > 0) { t = t + 1; u = u + 2; } } return t + u; }",
            "static long m(S s, long n) { long t = 0; long u = 0; for (S x : xs()) { if (x instanceof S.A a && n > 0) { t = t + 1; u = u + 2; } if (t > 5) { break; } } return t + u; }",
    })
    void anUnusedVariable(String members) {
        pinned(members);
    }

    /** The whole-condition form (D2 case 2), in method bodies, loops that update nothing, and with its variable assigned. */
    @ParameterizedTest
    @ValueSource(strings = {
            "static BigInteger m(S s) { if (s instanceof S.A a) { return a.v(); } return BigInteger.ZERO; }",
            "static BigInteger m(S s) { if (s instanceof S.B b) { return b.w(); } else if (s instanceof S.A a) { return a.v(); } return BigInteger.ZERO; }",
            "static long m(S s) { for (S x : xs()) { if (x instanceof S.A a) { said(\"v\", a.v()); } } return 1; }",
            "static BigInteger m(S s) { if (s instanceof S.A a) { for (S x : xs()) { a = new S.A(a.v().add(BigInteger.ONE)); } return a.v(); } return BigInteger.ZERO; }",
            "static long m(S s, long n) { for (S y : xs()) { if (y instanceof S.A a) { if (n > 0) { for (S x : xs()) { a = new S.A(a.v().add(BigInteger.ONE)); } } "
                    + "said(\"r\", a.v()); } } return 1; }",
            // Unread, where patternIf binds it: a method body, a loop that updates nothing, a lambda block.
            "static long m(S s) { if (s instanceof S.A a) { return 1; } return 0; }",
            "static long m(S s) { for (S x : xs()) { if (x instanceof S.A a) { said(\"x\", BigInteger.ONE); } } return 1; }",
            "static boolean m(S s) { return xs().any(x -> { if (x instanceof S.A a) { return true; } return false; }); }",
            // In a switch arm's block.
            "static BigInteger m(S s, S t) { return switch (t) { case S.B b -> { if (s instanceof S.A a) { yield a.v(); } yield b.w(); } "
                    + "default -> BigInteger.ONE; }; }",
    })
    void theWholeConditionForm(String members) {
        pinned(members);
    }

    /** Rejected today, with the first diagnostic frozen: D8 rule 4 changes the first one's code, deliberately. */
    @ParameterizedTest
    @ValueSource(strings = {
            "static BigInteger m(S s) { if (s instanceof S.A a) { a = new S.A(BigInteger.ONE); return a.v(); } return BigInteger.ZERO; }",
            "static long m(S s) { long r = 0; for (S y : xs()) { if (y instanceof S.A a) { if (r > 0) { for (S x : xs()) { a = new S.A(a.v()); } } "
                    + "r = r + a.v().longValue(); } } return r; }",
    })
    void anAssignmentRejectedToday(String members) {
        LoweringFixture.assertSameMethodRejectionAsReference(source(members), "m", StdlibRegistry.defaultRegistry());
    }

    /**
     * The order the whole-condition form builds its parts in, which decides the first of two errors (ADR-069 C8): what
     * follows the if, then the else branch, then the operand, then the then-branch. Each program has two errors, and the
     * frozen first one is not the first in source order.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "static BigInteger m(S s) { if (s instanceof S.A a) { return ((S.B) s).w(); } else { return ((S.A) s).v(); } }",
            "static BigInteger m(S s) { if (s instanceof S.A a) { return ((S.B) s).w(); } return ((S.A) s).v(); }",
            "static S pick(BigInteger v) { return new S.A(v); } "
                    + "static BigInteger m(S s) { if (pick(((S.B) s).w()) instanceof S.A a) { return ((S.A) s).v(); } return BigInteger.ZERO; }",
            "static BigInteger m(S s) { if (s instanceof S.B b) { return ((S.A) s).v(); } else if (s instanceof S.A a) { return ((S.B) s).w(); } "
                    + "return BigInteger.ZERO; }",
            "static BigInteger m(S s) { if (s instanceof S.A a) { long t = 0; for (S x : xs()) { t = t + 1; } return ((S.B) s).w(); } "
                    + "return ((S.A) s).v(); }",
    })
    void theBuildOrder(String members) {
        LoweringFixture.assertSameMethodRejectionAsReference(source(members), "m", StdlibRegistry.defaultRegistry());
    }

    private static void pinned(String members) {
        var source = source(members);
        LoweringFixture.assertSameMethodAsReference(source, "m", StdlibRegistry.defaultRegistry());
        for (var level : OptimizationLevel.values()) {
            var result = new JulcCompiler(StdlibRegistry.defaultRegistry(), new CompilerOptions().setOptimizationLevel(level))
                    .compileMethod(source, "m", List.of());
            FrozenReference.assertSame("adr069 pir|" + source + "|" + level, result.pirFormatted());
        }
    }
}
