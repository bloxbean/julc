package org.julclang.compiler.frontend.javac.lower;

import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerFrontend;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.OptimizationLevel;
import org.julclang.compiler.frontend.javac.NotPorted;
import org.julclang.stdlib.StdlibRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * For-each, {@code while}, {@code break} and accumulators: each program compiles to the reference's bytes at every level,
 * with and without a source map, and a rejection is the reference's. Evaluation against the JVM is
 * {@code Adr068LoopsHarnessTest}'s; this is I1 and I2a on one screen.
 */
class JavacLoopsTest {

    private static final String IMPORTS = """
            import java.math.BigInteger;
            import org.julclang.core.PlutusData;
            import org.julclang.core.types.JulcArray;
            import org.julclang.core.types.JulcG1;
            import org.julclang.core.types.JulcList;
            import org.julclang.core.types.Tuple2;
            import org.julclang.ledger.PubKeyHash;
            import org.julclang.stdlib.Builtins;
            import org.julclang.stdlib.annotation.Param;
            """;

    private static String source(String members) {
        return IMPORTS + "class C {\n    " + members + "\n}\n";
    }

    private static void same(String members) {
        LoweringFixture.assertSameMethodAsReference(source(members), "m", StdlibRegistry.defaultRegistry());
    }

    private static void rejected(String members) {
        LoweringFixture.assertSameMethodRejectionAsReference(source(members), "m", StdlibRegistry.defaultRegistry());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // One accumulator
            "static long m(JulcList<BigInteger> xs) { long s = 0; for (var x : xs) { s = s + 1; } return s; }",
            "static BigInteger m(JulcList<BigInteger> xs) { BigInteger s = BigInteger.ZERO; for (BigInteger x : xs) { s = s.add(x); } return s; }",
            "static BigInteger m(JulcList<BigInteger> xs) { var s = BigInteger.ZERO; for (var x : xs) s = s.add(x); return s; }",
            "static long m(long n) { long i = 0; while (i < n) { i = i + 1; } return i; }",
            "static long m(long n) { long i = 0; while (i < n) i = i + 1; return i; }",
            "static long m(long n) { long i = 0; while (i < n) { i += 2; } return i; }",
            "static long m(long n) { long i = n; while (i > 0) { i -= 1; } return i; }",
            "static long m(long n) { long i = 1; while (i < n) { i *= 2; } return i; }",
            "static long m(long n) { long i = n; while (i > 3) { i /= 2; } return i; }",
            "static long m(long n) { long i = n; while (i > 3) { i %= 3; } return i; }",
            "static String m(JulcList<BigInteger> xs) { String s = \"\"; for (var x : xs) { s += \"a\"; } return s; }",
            "static boolean m(JulcList<BigInteger> xs, BigInteger k) { boolean f = false; for (var x : xs) { if (x.equals(k)) { f = true; } } return f; }",
            "static long m(JulcList<BigInteger> xs) { long s = 0; for (var x : xs) { if (x.signum() > 0) { s = s + 1; } else { s = s - 1; } } return s; }",
            "static long m(JulcList<BigInteger> xs) { long s = 0; for (var x : xs) { if (x.signum() > 0) { s = s + 1; } s = s * 2; } return s; }",
            "static long m(JulcList<BigInteger> xs) { long s = 0; for (var x : xs) { long t = s + 1; s = t * 2; } return s; }",
            "static long m(JulcList<BigInteger> xs) { long s = 0; for (var x : xs) { long t = 1; t = t + 1; s = s + t; } return s; }",
            "static long h(long a) { return a; } static long m(JulcList<BigInteger> xs) { long s = 0; for (var x : xs) { h(s); s = s + 1; } return s; }",
            // A loop as the method's end, as a statement among others, and with nothing updated
            "static void m(JulcList<BigInteger> xs) { long s = 0; for (var x : xs) { s = s + 1; } }",
            "static long h(long a) { return a; } static void m(JulcList<BigInteger> xs) { for (var x : xs) { h(1); } }",
            "static long h(long a) { return a; } static long m(JulcList<BigInteger> xs) { for (var x : xs) { h(1); } return 2; }",
            "static long h(long a) { return a; } static long m(long n) { while (h(n) > 5) { h(1); } return 2; }",
            "static void m(JulcList<BigInteger> xs) { for (var x : xs) { } }",
            "static long m(long n) { while (true) { n = n + 1; } }",
            "static long m(long n) { while (n > 0) { } return n; }",
            // Pre-loop variables read after it
            "static long m(JulcList<BigInteger> xs, long k) { long s = 0; long t = k + 1; for (var x : xs) { s = s + 1; } return s + t + k; }",
            "static final long K = 7; static long m(JulcList<BigInteger> xs) { long s = 0; for (var x : xs) { s = s + K; } return s + K; }",
            "static long h(long a) { return a; } static long m(JulcList<BigInteger> xs) { long s = 0; for (var x : xs) { s = s + 1; } return h(s); }",
            "static long m(JulcList<BigInteger> xs) { long s = h(1); for (var x : xs) { s = s + 1; } return h(s); } static long h(long a) { return a; }",
            // Several accumulators
            "static long m(JulcList<BigInteger> xs) { long a = 0; long b = 1; for (var x : xs) { a = a + 1; b = b * 2; } return a + b; }",
            "static long m(long n) { long a = 0; long b = 1; while (a < n) { a = a + 1; b = b * 2; } return b; }",
            "static long m(long n) { long a = 0; long b = 1; while (a < n) { a = a + 1; if (a > 2) { b = b + a; } } return b; }",
            "static long m(long n) { long a = 0; long b = 1; while (a < n) { a = a + 1; if (a > 2) { b = b + a; } else { b = b - 1; } b = b * 2; } return b; }",
            "static String m(JulcList<BigInteger> xs) { long a = 0; String s = \"\"; boolean f = false; byte[] b = new byte[]{1}; for (var x : xs) { a = a + 1; s = s + \"k\"; f = !f; b = Builtins.appendByteString(b, b); } return s; }",
            "static long m(JulcList<BigInteger> xs) { BigInteger a = BigInteger.ZERO; long b = 0; for (var x : xs) { long t = 1; a = a.add(x); b = b + t; } return b; }",
            // Break
            "static boolean m(JulcList<BigInteger> xs, BigInteger k) { boolean f = false; for (var x : xs) { if (x.equals(k)) { f = true; break; } } return f; }",
            "static long m(JulcList<BigInteger> xs) { long s = 0; for (var x : xs) { if (s > 2) { break; } s = s + 1; } return s; }",
            "static long m(JulcList<BigInteger> xs) { long s = 0; for (var x : xs) { s = s + 1; if (s > 2) { break; } s = s * 2; } return s; }",
            "static long m(long n) { long i = 0; while (i < n) { i = i + 1; if (i > 2) { break; } } return i; }",
            "static long m(long n) { long i = 0; while (true) { i = i + 1; if (i > n) { break; } } return i; }",
            "static long m(long n) { long i = 0; while (i < n) { i = i + 1; break; } return i; }",
            "static long m(long n) { long i = 0; while (i < n) { if (i > 2) { i = 9; break; } else { i = i + 1; } i = i + 2; } return i; }",
            "static long m(long n) { long i = 0; while (i < n) { if (i > 2) { i = i + 3; } else { i = i + 1; if (i == 2) { break; } } i = i + 2; } return i; }",
            "static long m(long n) { long i = 0; while (i < n) { if (i > 2) { i = i + 3; } i = i + 2; } return i; }",
            "static long m(long n) { long i = 0; while (i < n) { i = i + 1; if (i > 2) { i = i + 3; } else { i = i - 1; } if (i > 7) { break; } } return i; }",
            "static long m(long n) { long i = 0; while (i < n) { i = i + 1; if (i > 3) { if (i > 7) { break; } else { break; } } i = i + 2; } return i; }",
            "static long m(long n) { long i = 0; while (i < n) { { i = i + 1; if (i > 3) { break; } } i = i + 2; } return i; }",
            "static long m(long n) { long i = 0; while (i < n) { long t = i; if (t > 3) { break; } i = t + 2; } return i; }",
            "static long m(long n) { long i = 0; while (i < n) { i = i + 1; { long t = i; i = t + 1; } { long t = i; i = t + 1; } } return i; }",
            "static long m(long n) { long i = 0; while (i < n) { if (i > 1) { i = 5; { break; } } i = i + 1; } return i; }",
            // Break with several
            "static long m(long n) { long a = 0; long b = 1; while (a < n) { a = a + 1; if (a > 2) { b = 9; break; } b = b * 2; } return a + b; }",
            "static long m(JulcList<BigInteger> xs) { long a = 0; long b = 1; for (var x : xs) { a = a + 1; if (a > 2) { b = 9; break; } b = b * 2; } return a + b; }",
            "static long m(JulcList<BigInteger> xs) { long a = 0; long b = 1; for (var x : xs) { if (a > 2) { a = 1; } else { b = 2; if (b > a) { break; } } a = a + 1; } return a + b; }",
            "static long m(JulcList<BigInteger> xs) { long a = 0; long b = 1; for (var x : xs) { if (a > 2) { a = 1; } else { b = 2; } if (b > 7) { break; } } return a + b; }",
            "static long m(JulcList<BigInteger> xs) { long a = 0; long b = 1; for (var x : xs) { if (a > 2) { a = 1; } else { b = 2; } } return a + b; }",
            "static long m(JulcList<BigInteger> xs) { long a = 0; long b = 1; for (var x : xs) { if (a > 2) { a = 1; b = 2; break; } else { b = 2; } } return a + b; }",
            "static long m(JulcList<BigInteger> xs) { long a = 0; long b = 1; for (var x : xs) { if (a > 2) { break; } if (b > 2) { a = 1; } else { b = 2; } } return a + b; }",
            "static long m(JulcList<BigInteger> xs) { long a = 0; long b = 1; for (var x : xs) { a = a + 1; if (a > 2) { b = 2; } else { a = 3; break; } a = 3; } return a + b; }",
            "static long m(JulcList<BigInteger> xs, long n) { long a = 0; long b = 1; for (var x : xs) { long i = 0; while (i < n) { i = i + 1; a = a + 1; } if (a > 2) { b = 2; break; } } return a + b; }",
            // Nested
            "static long m(long n) { long s = 0; long i = 0; while (i < n) { long j = 0; while (j < i) { j = j + 1; s = s + j; } i = i + 1; } return s; }",
            "static long m(JulcList<BigInteger> xs, JulcList<BigInteger> ys) { long s = 0; for (var x : xs) { for (var y : ys) { s = s + 1; } } return s; }",
            "static long m(JulcList<BigInteger> xs, JulcList<BigInteger> ys) { long s = 0; long t = 0; for (var x : xs) { for (var y : ys) { s = s + 1; t = t + s; } t = t * 2; } return s + t; }",
            "static long h(long a) { return a; } static long m(JulcList<BigInteger> xs) { for (var x : xs) { for (var y : xs) { h(1); } } return 1; }",
            "static long h(long a) { return a; } static long m(long n) { long s = 0; while (n > 0) { long j = 0; while (j < n) { j = j + 1; h(j); } n = n - 1; } return s; }",
            "static long m(long n) { long s = 0; while (n > 0) { long t = 0; while (t < 2) { if (t > 0) { s = 1; } t = t + 1; } n = n - 1; } return s; }",
            "static long m(long n, JulcList<BigInteger> xs) { long s = 0; for (var x : xs) { while (s < n) { s = s + 1; break; } } return s; }",
            "static boolean m(JulcList<BigInteger> xs) { return xs.any(x -> { var y = x; while (y.signum() > 0) { y = y.subtract(BigInteger.ONE); } return true; }); }",
            "static boolean m(JulcList<BigInteger> xs) { return xs.any(x -> { long s = 0; for (var y : xs) { if (y.equals(x)) { s = s + 1; break; } } return s > 0; }); }",
            "static long m(long n) { while (n > 0) { long t = 0; long i = 0; while (i < 2) { i = i + 1; if (i > 1) { t = t + 1; } } n = n - t - 1; } return n; }",
            "static long m(long n) { long i = 0; while (i < n) { i = i + 1; long t = i; t = 5; break; } return i; }",
            "static long m(long n) { long i = 0; while (i < n) { i = i + 1; if (i > 5) { break; } if (i > 2) { i = i + 1; } } return i; }",
            "sealed interface S { record A(BigInteger v) implements S { } record B() implements S { } } static long m(JulcList<BigInteger> xs, S s) { return switch (s) { case S.A a -> { long t = 0; for (var x : xs) { t = t + 1; } yield t + a.v().longValue(); } default -> 0; }; }",
            // A loop in an if
            "static long m(JulcList<BigInteger> xs, boolean c) { long s = 0; if (c) { for (var x : xs) { s = s + 1; } } return s; }",
            "static long m(JulcList<BigInteger> xs, boolean c) { long s = 0; long t = 0; if (c) { for (var x : xs) { s = s + 1; t = t + 1; } } else { for (var x : xs) { t = t + 2; } } return s + t; }",
            "static long m(JulcList<BigInteger> xs, boolean c) { if (c) { for (var x : xs) { } } return 1; }",
            "static long m(JulcList<BigInteger> xs, boolean c) { long s = 0; if (c) { } else { for (var x : xs) { s = s + 1; } } return s; }",
            "static long m(JulcList<BigInteger> xs, boolean c) { if (c) { for (var x : xs) { } return 2; } return 1; }",
            "static long m(JulcList<BigInteger> xs, boolean c, boolean d) { long s = 0; if (c) { if (d) { for (var x : xs) { s = s + 1; } } } return s; }",
            "static long m(JulcList<BigInteger> xs, boolean c, boolean d) { long s = 0; if (c) { for (var x : xs) { s = s + 1; } } if (d) { for (var x : xs) { s = s + 2; } } return s; }",
            "static long m(JulcList<BigInteger> xs, boolean c) { long s = 0; if (c) for (var x : xs) s = s + 1; return s; }",
            "static long m(JulcList<BigInteger> xs, boolean c) { long s = 0; for (var x : xs) { if (c) { long i = 0; while (i < 2) { i = i + 1; s = s + i; } } s = s + 1; } return s; }",
            "static long m(long n, boolean c) { long s = 0; while (n > 0) { if (c) { s = 1; } n = n - 1; } return s; }",
            // Bare blocks
            "static long m(long n) { long i = 0; while (i < n) { { long t = i + 1; i = t; } } return i; }",
            "static long m(long n) { long i = 0; while (i < n) { { i = i + 1; } i = i + 1; } return i; }",
            "static long m(long n) { long a = 0; long b = 0; while (a < n) { { long t = a + 1; a = t; } { long t = b + 1; b = t; } } return a + b; }",
            "static final long K = 3; static long m(long n) { long i = 0; while (i < n) { { long K = i + 1; i = K; } i = i + K; } return i; }",
    })
    void loops(String members) {
        same(members);
    }

    /**
     * A local after a loop that shadows a field has its own name here, where it has the field's on the reference, so the
     * identity {@code let} that spelling gives it is not written. It is the one difference, and it makes no script larger.
     */
    @Test
    void aLocalThatShadowsAFieldAfterALoop() {
        var source = source("static final long K = 7; static long m(JulcList<BigInteger> xs) { long s = 0; for (var x : xs) { s = s + 1; } long K = s; return K; }");
        var stdlib = StdlibRegistry.defaultRegistry();
        for (var level : OptimizationLevel.values()) {
            assertTrue(LoweringFixture.methodSize(source, "m", level, CompilerFrontend.JAVAC, stdlib)
                    <= LoweringFixture.methodSize(source, "m", level, CompilerFrontend.JAVAPARSER, stdlib), level.toString());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "static long m(long n) { long i = 0; while (i < n) { i++; } return i; }",
            "static BigInteger m(BigInteger a) { switch (a.intValue()) { case 1: break; default: break; } return a; }",
            "static long m(long n) { for (long i = 0; i < n; i = i + 1) { } return n; }",
            "static long m() { return 1; } long instance() { for (;;) { } }",
            "static long m() { return 1; } long instance() { do { } while (true); }",
            "static long m(long n) { do { n = n - 1; } while (n > 0); return n; }",
            "static long m(JulcList<BigInteger> xs) { for (var x : xs) { return 1; } return 2; }",
            "static long m(long n) { while (n > 0) { return 1; } return 2; }",
            "static long m(long n) { while (n > 0) { if (n > 1) { return 1; } } return 2; }",
            "static long m(JulcList<BigInteger> xs) { long s = 0; for (var x : xs) { if (s > 0) { continue; } s = s + 1; } return s; }",
            "static long m(long n) { while (n > 0) { n = n - 1; continue; } return n; }",
            "static long m(long n) { outer: while (n > 0) { n = n - 1; } return n; }",
            "static long m(long n) { outer: while (n > 0) { while (n > 1) { n = n - 1; break outer; } n = 0; } return n; }",
            "static long m(long n) { outer: while (n > 0) { n = n - 1; continue outer; } return n; }",
            "static long m(JulcList<BigInteger> xs) { for (var x : xs) { BigInteger y = (x = BigInteger.ONE); break; } return 1; }",
            "static long m(JulcList<BigInteger> xs) { for (var x : xs) { long t = 0; long u = (t = 1); break; } return 1; }",
            "static long m(long z) { while (z > 0) { long t = 0; long u = (t = 1); break; } return 1; }",
            "static long m(JulcList<BigInteger> xs, long z) { for (var x : xs) { for (var y : xs) { long u = (z = 1); } break; } return z; }",
            "static long m(JulcList<BigInteger> xs, long z) { for (var x : xs) { long u = (z = 1); break; } return z; }",
            "static long K = 0; static long m(JulcList<BigInteger> xs) { for (var x : xs) { boolean b = xs.any(y -> { K = 1; return true; }); break; } return 1; }",
            "sealed interface S permits A, B { } record A() implements S { } record B() implements S { } static long m(JulcList<BigInteger> xs, S s, long z) { for (var x : xs) { long u = switch (s) { case A a -> { z = 1; yield 1; } default -> 2; }; break; } return z; }",
            "static long m(long n, JulcList<BigInteger> xs) { while (n > 0) { long t = 0; if (n > 1) { for (var x : xs) { t = t + 1; } } n = n - t - 1; } return n; }",
            "static long m(long n) { while (n > 0) { long t = 0; if (n > 1) { long i = 0; while (i < 2) { i = i + 1; t = t + 1; } } n = n - t - 1; } return n; }",
            "static long m(long n) { while (n > 0) { long t = 0; if (n > 1) { } else { t = 1; } n = n - t - 1; } return n; }",
            "static long m(long n) { while (n > 0) { long t = 0; if (n > 1) { long u = (t = 1) + 1; n = n - u; } n = n - 1; } return n; }",
            "static long m(long n, JulcList<BigInteger> xs) { while (n > 0) { n = n - 1; if (n > 1) { boolean b = xs.any(x -> { long w = 0; if (x.signum() > 0) { w = 1; } return w > 0; }); } } return n; }",
            "sealed interface S permits A, B { } record A() implements S { } record B() implements S { } static long m(long n, S s) { long r = 0; while (n > 0) { n = n - 1; r = r + switch (s) { case A a -> { long w = 0; if (n > 1) { w = 1; } yield w; } default -> 0; }; } return r; }",
            "sealed interface S permits A, B { } record A() implements S { } record B() implements S { } static long m(JulcList<BigInteger> xs, S s) { return switch (s) { case A a -> { for (var x : xs) { yield 1; } yield 2; } default -> 3; }; }",
            "sealed interface S permits A, B { } record A() implements S { } record B() implements S { } static long m(long n, S s) { return switch (s) { case A a -> { while (n > 0) { yield 1; } yield 2; } default -> 3; }; }",
            "static long m(JulcList<BigInteger> xs, boolean c) { for (var x : xs) { if (c) { } else { long t = 0; t = 1; } } return 1; }",
            "static long m(JulcList<BigInteger> xs) { for (var x : xs) { { long t = 0; t = 1; } } return 1; }",
            "static long m(long n) { while ((n = n - 1) > 0) { break; } return n; }",
            "static long m(JulcList<BigInteger> xs) { JulcList<BigInteger> y = xs; for (var x : (y = xs.tail())) { if (x.signum() > 0) { break; } } return 1; }",
            "static long m(JulcList<BigInteger> xs) { JulcList<BigInteger> y = xs; for (var x : (y = xs.tail())) { } return 1; }",
            "static long m(JulcList<BigInteger> xs) { JulcList<BigInteger> y = xs; long s = 0; for (var x : (y = xs.tail())) { s = s + 1; if (s > 1) { break; } } return s; }",
            "static long m() { return 1; } long f() { long s = 0; for (int i = 0; i < 2; i = i + 1) { s = s + 1; } return s; }",
            "static long m() { return 1; } long f() { long s = 0; do { s = s + 1; } while (s < 2); return s; }",
            "static long m() { return 1; } long f(int a) { long s = 0; switch (a) { case 1: break; default: break; } return s; }",
            "static long m() { return 1; } long f() { long s = 0; if (s > 0) { do { s = s + 1; } while (s < 2); } return s; }",
            "sealed interface S permits A, B { } record A() implements S { } record B() implements S { } static long m(S s) { return switch (s) { case A a -> { long i = 0; while (true) { i = i + 1; } } default -> 1; }; }",
            "static long h(JulcList b, JulcList<JulcG1> x) { while (x.isEmpty()) { x = b; } return 1; } static long m() { return 1; }",
            "static long m(long n) { while (n > 0) { n = n - 1; } { return n; } }",
            "static long m(JulcList<BigInteger> xs, long s) { for (var x : xs) { s = s + 1; boolean b = (s = 2) > 1; } return s; }",
            "static long m(JulcList<BigInteger> xs, long s, long t) { for (var x : xs) { s = s + 1; t = t + 1; boolean b = (s = 2) > 1; } return s; }",
            "static long m(long s, long t) { while (s > 0) { s = s - 1; t = t + 1; boolean b = (s = 2) > 1; } return s; }",
            "static long m(long s, long t) { while (s > 0) { s = s - 1; t = t + 1; if (t > 3) { break; } boolean b = (s = 2) > 1; } return s; }",
            "static long m(long s) { while (s > 0) { s = s - 1; if (s > 3) { break; } boolean b = (s = 2) > 1; } return s; }",
            "static long m(long z) { for (var x : JulcList.of(z)) { z = z + 1; x += 1; } return z; }",
            "static long m(int n, long y, JulcList<BigInteger> xs) { for (var x : xs) { switch (n) { default: y = 1; } break; } return y; }",
            "static long m(long n) { while (n > 0) { inner: while (n > 1) { n = n - 1; } n = n - 1; } return n; }",
            "static long m(JulcList<BigInteger> xs) { long s = 0; for (var x : xs) { s = s + 1; l: { break l; } } return s; }",
            "static long m(JulcList<BigInteger> xs) { for (var x : xs) { boolean b = xs.any(y -> { l: while (y.signum() > 0) { break l; } return true; }); } return 1; }",
            "static long m(JulcList<BigInteger> xs) { for (var x : xs) { x = BigInteger.ONE; } return 1; }",
            "static long m(JulcList<BigInteger> xs) { for (var x : xs) { long t = 0; t = t + 1; } return 1; }",
            "static long m(JulcList<BigInteger> xs) { for (var x : xs) { if (x.signum() > 0) { break; } } return 1; }",
            "static long m(long n) { while (n > 0) { break; } return 1; }",
            "static long m(long n) { long s = 0; while (n > 0) { long t = 0; if (n > 1) { t = 1; } s = s + t; n = n - 1; } return s; }",
            "static long m(JulcList<BigInteger> xs) { long s = 0; for (var x : xs) { if (s > 0) { x = BigInteger.ONE; } s = s + 1; } return s; }",
            "static long m(JulcList<BigInteger> xs) { long s = 0; for (var x : xs) { for (var y : xs) { s = s + 1; if (s > 2) { break; } } if (s > 3) { break; } } return s; }",
            "static long m(long n) { long i = 0; while (i < n) { i = i + 1; if (i > 2) { break; } while (i < 5) { i = i + 1; } } return i; }",
            "static long m(JulcList<BigInteger> xs) { long s = 0; for (var x : xs) { boolean b = (s = s + 1) > 2; } return s; }",
            "static long m(long n) { long s = 0; while ((s = s + 1) < n) { } return s; }",
            "static long m(long n) { long s = 0; while ((s = s + 1) < n) { s = s + 1; } return s; }",
            "static long m(long n) { long s = 0; while ((s = s + 1) < n) { s = s + 1; if (s > 2) { break; } } return s; }",
            "static long m(long n) { long s = 0; long t = 0; while ((s = s + 1) < n) { s = s + 1; t = 1; } return s; }",
            "static long m(long n) { long t = 0; while (n > 0) { long s = 0; s = s + 1; } return t; }",
            "static long y; static long m(long n) { while (n > 0) { y = 1; n = n - 1; } return n; }",
            "static long m(long n) { while (n > 0) { ; n = n - 1; } return n; }",
            "static long m(long n) { long a = 0; long b = 0; while (a < n) { a = 1; b = 1; ; } return a; }",
            "static long m(long n) { long a = 0; long b = 0; while (a < n) { a = 1; b = 1; if (a > 2) { break; } ; } return a; }",
            "static long m(long n) { long a = 0; while (a < n) { a = 1; if (a > 2) { break; } ; } return a; }",
            "static long m(long n) { while (n > 0) ; return n; }",
            "static long m(String s) { long i = 0; while (i < 2) { i += s; } return i; }",
            "static String m(String s, long n) { long i = 0; while (i < n) { s += n; i = i + 1; } return s; }",
            "static String m(String s, long n) { while (n > 0) { s += 1; n = n - 1; } return s; }",
    })
    void rejections(String members) {
        rejected(members);
    }

    /** What is read off a for-each's variable is what the reference sees of it, whatever javac knows of the element (I2d). */
    @ParameterizedTest
    @ValueSource(strings = {
            "record R(BigInteger a) { } static BigInteger m(R r) { BigInteger s = BigInteger.ZERO; for (var o : JulcList.of(r)) { s = o.a(); } return s; }",
            "static byte[] m(PubKeyHash p) { byte[] s = new byte[]{}; for (var o : JulcList.of(p)) { s = o.hash(); } return s; }",
            "static long m(BigInteger a) { long s = 0; for (var x : JulcList.of(a)) { s = x.longValue(); } return s; }",
            "static long m(JulcList<BigInteger> xs) { long s = 0; for (var o : xs.map(x -> x)) { s = o.longValue(); } return s; }",
    })
    void aVariableReadAsTheReferenceSeesIt(String members) {
        rejected(members);
    }

    /** An iterable in parentheses, a conditional or a {@code switch} has no element type to the reference, so its variable reads as Data. */
    @ParameterizedTest
    @ValueSource(strings = {
            "static long m(JulcList<BigInteger> xs) { long s = 0; for (var x : (xs)) { s = s + x.longValue(); } return s; }",
            "static long m(JulcList<BigInteger> xs) { long s = 0; for (BigInteger x : (xs)) { s = s + x.longValue(); } return s; }",
            "static long m(JulcList<BigInteger> xs, JulcList<BigInteger> ys, boolean c) { long s = 0; for (var x : c ? xs : ys) { s = s + x.signum(); } return s; }",
            "sealed interface S permits A, B { } record A() implements S { } record B() implements S { } static long m(JulcList<BigInteger> xs, S e) { long s = 0; for (var x : switch (e) { case A a -> xs; default -> xs; }) { s = s + x.longValue(); } return s; }",
            "record R(BigInteger a) { } static BigInteger m(JulcList<R> xs) { BigInteger s = BigInteger.ZERO; for (var x : (xs)) { s = s.add(x.a()); } return s; }",
            "sealed interface S permits A, B { } record A() implements S { } record B() implements S { } static long m(JulcList<S> xs) { long s = 0; for (var e : (xs)) { s = s + switch (e) { case A a -> 1; default -> 2; }; } return s; }",
            "static long m(JulcList<BigInteger> xs) { long s = 0; for (var x : (xs)) { var y = x; s = s + y.longValue(); } return s; }",
            "static long m(JulcList<BigInteger> xs, boolean c) { long s = 0; for (var x : (xs)) { var y = c ? x : x; s = s + y.longValue(); } return s; }",
    })
    void anIterableTheReferenceDoesNotRead(String members) {
        rejected(members);
    }

    /** What the reference runs on such a variable stays so, and an element that stops being one of these reads as before. */
    @ParameterizedTest
    @ValueSource(strings = {
            "static long m(JulcList<PlutusData> xs) { long s = 0; for (var x : (xs)) { s = s + Builtins.unIData(x).longValue(); } return s; }",
            "static long m(JulcList<PlutusData> xs) { long s = 0; long t = 0; for (var x : (xs)) { s = s + 1; t = t + Builtins.unIData(x).longValue(); } return s + t; }",
    })
    void anIterableTheReferenceDoesNotReadStillRuns(String members) {
        same(members);
    }

    /** The hash of an element the reference knows to be decoded, a pattern variable as an accumulator, and an update's tuple. */
    @ParameterizedTest
    @ValueSource(strings = {
            "static byte[] m(JulcList<PubKeyHash> xs) { byte[] s = new byte[]{}; for (var o : xs) { s = o.hash(); } return s; }",
            "static byte[] m(JulcList<PubKeyHash> xs) { byte[] s = new byte[]{}; for (PubKeyHash o : xs) { var h = o; s = Builtins.appendByteString(s, o.hash()); } return s; }",
            "record R(BigInteger a) { } static long m(JulcList<R> xs) { long s = 0; for (var o : xs) { s = s + o.a().longValue(); } return s; }",
            "record R(JulcList<BigInteger> ys) { } static long m(R r) { long s = 0; for (var y : r.ys()) { s = s + 1; } return s; }",
            "static long m(JulcList<JulcList<BigInteger>> xss) { long s = 0; for (var xs : xss) { for (var x : xs) { s = s + 1; } } return s; }",
            "static long m(JulcList<BigInteger> xs) { long s = 0; for (var x : xs.tail()) { s = s + 1; } return s; }",
            "sealed interface S { record A(BigInteger v) implements S { } record B() implements S { } } static long m(S s, long n) { if (s instanceof S.A a) { while (n > 0) { a = new S.A(BigInteger.ONE); n = n - 1; } return a.v().longValue(); } return n; }",
            "static BigInteger m(JulcList<BigInteger> xs) { Tuple2<BigInteger, BigInteger> t = new Tuple2<>(BigInteger.ZERO, BigInteger.ONE); for (var x : xs) { t = new Tuple2<>(x, t.first()); } return t.second(); }",
            "static boolean m(JulcList<BigInteger> xs) { boolean a = true; for (BigInteger x : xs) { if (a) { boolean b = !a; a = b; } } return a; }",
    })
    void whatAReadOfALoopMeans(String members) {
        same(members);
    }

    /** What a Data accumulator of a {@code while} travels as, by the builtins assigned to it and what stands before the loop. */
    @ParameterizedTest
    @ValueSource(strings = {
            "static long m(PlutusData d) { PlutusData c = Builtins.unMapData(d); long i = 0; while (i < 2) { c = Builtins.tailList(c); i = i + 1; } return i; }",
            "static long m(PlutusData d) { PlutusData b = Builtins.unMapData(d); PlutusData c = b; long i = 0; while (i < 2) { c = Builtins.tailList(c); i = i + 1; } return i; }",
            "static long m(PlutusData.MapData d) { PlutusData c = d; long i = 0; while (i < 2) { c = Builtins.tailList(c); i = i + 1; } return i; }",
            "static long m(PlutusData d) { PlutusData.MapData b = Builtins.unMapData(d); PlutusData c = b; long i = 0; while (i < 2) { c = Builtins.tailList(c); i = i + 1; } return i; }",
            "static long m(PlutusData d) { PlutusData c = d; long i = 0; while (i < 2) { c = Builtins.mkNilPairData(); i = i + 1; } return i; }",
            "static long m(PlutusData d) { PlutusData c = d; long i = 0; while (i < 2) { if (i > 0) { c = Builtins.tailList(c); } else { c = Builtins.mkNilData(); } i = i + 1; } return i; }",
            "static long m(PlutusData d) { PlutusData c = d; long i = 0; while (i < 2) { var h = Builtins.headList(c); Builtins.fstPair(h); c = Builtins.tailList(c); i = i + 1; } return i; }",
            "static long m(PlutusData d) { PlutusData c = d; long i = 0; while (i < 2) { var h = Builtins.headList(c); Builtins.sndPair(h); c = Builtins.tailList(c); i = i + 1; } return i; }",
            "static long m(PlutusData d, boolean b) { PlutusData c = d; long i = 0; while (i < 2) { if (b) { i = i + 1; } else { var h = Builtins.headList(c); Builtins.fstPair(h); i = i + 1; } c = Builtins.tailList(c); } return i; }",
            "static long m(PlutusData d, PlutusData e) { PlutusData c = Builtins.unMapData(d); PlutusData l = Builtins.mkNilData(); PlutusData t = e; long i = 0; while (i < 2) { c = Builtins.tailList(c); t = Builtins.tailList(t); l = Builtins.mkCons(Builtins.iData(i), l); l = Builtins.tailList(l); i = i + 1; } return i; }",
            "static long m(PlutusData d) { PlutusData l = Builtins.mkNilData(); long i = 0; while (i < 2) { PlutusData p = Builtins.mkPairData(d, d); l = Builtins.mkCons(p, l); i = i + 1; } return i; }",
            "static long m(PlutusData d) { PlutusData l = Builtins.mkNilData(); long i = 0; while (i < 2) { l = Builtins.mkCons(d, l); i = i + 1; } return i; }",
            "static long m(PlutusData d) { PlutusData c = d; long i = 0; while (i < 2) { if (i > 0) { i = i + 1; } else { c = Builtins.mkNilPairData(); i = i + 2; } } return i; }",
            "static long m(PlutusData d) { var l = Builtins.unListData(d); long i = 0; while (i < 2) { PlutusData p = Builtins.mkPairData(d, d); l = Builtins.mkCons(p, l); i = i + 1; } return i; }",
            "static long m(PlutusData d) { PlutusData.ListData l = Builtins.mkNilData(); long i = 0; while (i < 2) { PlutusData p = Builtins.mkPairData(d, d); l = Builtins.mkCons(p, l); i = i + 1; } return i; }",
            "static PlutusData.MapData h(PlutusData d) { return Builtins.unMapData(d); } static long m(PlutusData d) { PlutusData.MapData b = h(d); PlutusData c = b; long i = 0; while (i < 2) { c = Builtins.tailList(c); i = i + 1; } return i; }",
    })
    void whatATupleOfAWhileHolds(String members) {
        same(members);
    }

    /** A native element has no decoding from Data, so the rejection stands at the iterable, not nowhere. */
    @Test
    void aNativeElementHasAPosition() {
        var source = source("static long m(long n) { JulcList<JulcG1> g = JulcList.empty(); long s = 0;\nfor (var x : g) { s = s + 1; } return s; }");
        var failure = assertThrows(CompilerException.class, () -> new JulcCompiler(StdlibRegistry.defaultRegistry(),
                new CompilerOptions().setFrontend(CompilerFrontend.JAVAC)).compileMethod(source, "m", List.of()));
        assertEquals("JULC0041", failure.diagnostics().getFirst().code());
        assertEquals(12, failure.diagnostics().getFirst().line());
        assertEquals(14, failure.diagnostics().getFirst().column());
    }

    /** A native value in a tuple is rejected at the loop, where the reference has no position. */
    @Test
    void aNativeAccumulatorHasAPosition() {
        var source = source("static long h(JulcG1 g, long n) { long s = 0;\nwhile (s < n) { s = s + 1; g = g; } return s; } static long m() { return 1; }");
        var failure = assertThrows(CompilerException.class, () -> new JulcCompiler(StdlibRegistry.defaultRegistry(),
                new CompilerOptions().setFrontend(CompilerFrontend.JAVAC)).compileMethod(source, "m", List.of()));
        assertEquals("JULC0041", failure.diagnostics().getFirst().code());
        assertEquals(12, failure.diagnostics().getFirst().line());
    }

    /** Reaching a list and Data in one variable has no one type for the tuple, so it stops. */
    @ParameterizedTest
    @ValueSource(strings = {
            "static long m(PlutusData d) { PlutusData cur = d; long i = 0; while (i < 1) { i = i + 1; cur = Builtins.tailList(Builtins.unListData(cur)); } return i; }",
            "static long m(PlutusData d) { PlutusData cur = Builtins.unListData(d); long i = 0; while (i < 2) { i = i + 1; if (i == 1) { cur = Builtins.tailList(cur); } else { cur = Builtins.iData(i); } } return i; }",
            "static long m(long n) { PlutusData cur = Builtins.iData(0); long i = 0; while (i < n) { i = i + 1; if (i == 5) { cur = Builtins.mkNilData(); } } return Builtins.unIData(cur).longValue(); }",
    })
    void anAccumulatorOfTwoRepresentationsStops(String members) {
        var failure = assertThrows(NotPorted.class, () -> new JulcCompiler(StdlibRegistry.defaultRegistry(),
                new CompilerOptions().setFrontend(CompilerFrontend.JAVAC)).compileMethod(source(members), "m", List.of()));
        assertEquals("an accumulator shown to hold both a list and Data", failure.construct());
    }

    /** A library compiled as a root finds its own method in the reference's pool, which is the library slice's to read. */
    @Test
    void aLibraryAsARootStopsAtItsOwnMethod() {
        LoweringFixture.assertMethodNotPorted("""
                import org.julclang.stdlib.annotation.OnchainLibrary;
                @OnchainLibrary
                class L {
                    static long h(long a) { return a; }
                    static long m(long a) { return L.h(a); }
                }
                """, "m", "a call of a library unit's own method", 5);
    }

    /** A value that a tuple cannot encode. With no type to encode by, the rejection stands at the loop: the reference has no position for it. */
    @Test
    void whatATupleCannotHold() {
        rejected("static long m(long n) { JulcArray<BigInteger> a = JulcArray.of(BigInteger.ONE); long i = 0; while (i < n) { i = i + 1; a = JulcArray.of(BigInteger.TWO); } return i; }");
        var source = source("sealed interface S { record A(BigInteger v) implements S { } record B() implements S { } }\n"
                + "static long m(S s, long n) { var x = switch (s) { case S.A a -> a; case S.B b -> BigInteger.ONE; }; long i = 0;\n"
                + "while (i < n) { i = i + 1; x = x; } return i; }");
        var failure = assertThrows(CompilerException.class, () -> new JulcCompiler(StdlibRegistry.defaultRegistry(),
                new CompilerOptions().setFrontend(CompilerFrontend.JAVAC)).compileMethod(source, "m", List.of()));
        assertEquals("JULC0012", failure.diagnostics().getFirst().code());
        assertEquals(13, failure.diagnostics().getFirst().line());
    }

    /** A tuple's diamond takes its target's components, so a raw target takes only Data. */
    @ParameterizedTest
    @ValueSource(strings = {
            "static boolean m(JulcList<BigInteger> xs, PlutusData d) { Tuple2 t = new Tuple2(d, d); for (var x : xs) { t = new Tuple2<>(x, x); } return true; }",
    })
    void aDiamondAssignedInALoop(String members) {
        rejected(members);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "static long K = 0; static long m(long n) { long i = 0; while (i < n) { K = K + 1; i = i + 1; } return K; }",
            "static long K = 0; static long m(JulcList<BigInteger> xs) { for (var x : xs) { K = K + 1; } return K; }",
            "static long K = 0; static long m(JulcList<BigInteger> xs) { long K = 5; for (var x : xs) { K = K + 1; } return K; }",
            "static long K = 0; static long m(JulcList<BigInteger> xs) { long s = 0; for (var x : xs) { long K = s; s = K + 1; } return s + K; }",
            "static long K = 0; static long h(long K) { return K; } static long m(JulcList<BigInteger> xs) { for (var x : xs) { K = K + 1; } return h(K); }",
    })
    void fieldsUpdatedInLoops(String members) {
        same(members);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "static long F = 0; static JulcList<BigInteger> L = JulcList.of(BigInteger.ONE); static boolean B = L.any(x -> { long i = 0; while (i < 1) { F = F + 1; i = i + 1; } return true; }); static boolean m() { return B; }",
            "static long K = 0; static long h() { return K; } static long m(long n) { while (n > 0) { K = K + 1; n = n - 1; } return K + h(); }",
            "static long K = 0; static long h(long n) { while (n > 0) { K = K + 1; n = n - 1; } return K; } static long m(long n) { return h(n); }",
            "static long K = 0; static long m(long n) { while (n > 0) { K = K + 1; n = n - 1; } return n > 5 ? m(n - 1) : K; }",
            "static long K = 0; static boolean m(JulcList<BigInteger> xs, long n) { return xs.any(x -> { long i = 0; while (i < n) { K = K + 1; i = i + 1; } return K > n; }); }",
    })
    void fieldUpdatesRejected(String members) {
        rejected(members);
    }
}
