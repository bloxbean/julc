package org.julclang.compiler.frontend.javac.lower;

import org.julclang.stdlib.StdlibRegistry;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
            "static final long K = 7; static long m(JulcList<BigInteger> xs) { long s = 0; for (var x : xs) { s = s + 1; } long K = s; return K; }",
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
            // A loop in an if
            "static long m(JulcList<BigInteger> xs, boolean c) { long s = 0; if (c) { for (var x : xs) { s = s + 1; } } return s; }",
            "static long m(JulcList<BigInteger> xs, boolean c) { long s = 0; long t = 0; if (c) { for (var x : xs) { s = s + 1; t = t + 1; } } else { for (var x : xs) { t = t + 2; } } return s + t; }",
            "static long m(JulcList<BigInteger> xs, boolean c) { if (c) { for (var x : xs) { } } return 1; }",
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

    @ParameterizedTest
    @ValueSource(strings = {
            "static long m(long n) { long i = 0; while (i < n) { i++; } return i; }",
            "static long m(long n) { for (long i = 0; i < n; i = i + 1) { } return n; }",
            "static long m(long n) { do { n = n - 1; } while (n > 0); return n; }",
            "static long m(JulcList<BigInteger> xs) { for (var x : xs) { return 1; } return 2; }",
            "static long m(long n) { while (n > 0) { return 1; } return 2; }",
            "static long m(long n) { while (n > 0) { if (n > 1) { return 1; } } return 2; }",
            "static long m(JulcList<BigInteger> xs) { long s = 0; for (var x : xs) { if (s > 0) { continue; } s = s + 1; } return s; }",
            "static long m(long n) { while (n > 0) { n = n - 1; continue; } return n; }",
            "static long m(long n) { outer: while (n > 0) { n = n - 1; } return n; }",
            "static long m(long n) { outer: while (n > 0) { while (n > 1) { n = n - 1; break outer; } n = 0; } return n; }",
            "static long m(long n) { outer: while (n > 0) { n = n - 1; continue outer; } return n; }",
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
            "static long K = 0; static long h() { return K; } static long m(long n) { while (n > 0) { K = K + 1; n = n - 1; } return K + h(); }",
            "static long K = 0; static long h(long n) { while (n > 0) { K = K + 1; n = n - 1; } return K; } static long m(long n) { return h(n); }",
            "static long K = 0; static long m(long n) { while (n > 0) { K = K + 1; n = n - 1; } return n > 5 ? m(n - 1) : K; }",
            "static long K = 0; static boolean m(JulcList<BigInteger> xs, long n) { return xs.any(x -> { long i = 0; while (i < n) { K = K + 1; i = i + 1; } return K > n; }); }",
    })
    void fieldUpdatesRejected(String members) {
        rejected(members);
    }
}
