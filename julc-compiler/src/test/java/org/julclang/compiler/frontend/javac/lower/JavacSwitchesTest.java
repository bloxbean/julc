package org.julclang.compiler.frontend.javac.lower;

import org.julclang.stdlib.StdlibRegistry;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@code switch} expressions: each program compiles to the reference's bytes at every level, with and without a source map,
 * and a rejection is the reference's, code, message and position. Evaluation against the JVM is
 * {@code Adr068SwitchHarnessTest}'s; this is I1 and I2a on one screen.
 */
class JavacSwitchesTest {

    private static final String IMPORTS = """
            import java.math.BigInteger;
            import java.util.Optional;
            import org.julclang.core.PlutusData;
            import org.julclang.core.types.JulcList;
            import org.julclang.core.types.JulcMap;
            import org.julclang.core.types.Tuple2;
            import org.julclang.ledger.Credential;
            import org.julclang.ledger.OutputDatum;
            import org.julclang.ledger.TxOut;
            import org.julclang.stdlib.Builtins;
            """;

    private static final String TYPES = """
            sealed interface S permits A, B, D { }
            record A(BigInteger v) implements S { }
            record B(BigInteger v, BigInteger w) implements S { }
            record D() implements S { }
            sealed interface O permits P { }
            record P(BigInteger x) implements O { }
            sealed interface T { record X(boolean b) implements T { } record Y(S s) implements T { } }
            record R(S s, BigInteger n) { }
            static final boolean ON = true;
            static final int K = 2147483647;
            static final long W = 1;
            static BigInteger h(BigInteger a) { return a; }
            static boolean ok(BigInteger a) { return a.signum() > 0; }
            static Boolean box(BigInteger a) { return a.signum() > 0; }
            """;

    private static String source(String members) {
        return IMPORTS + "class C {\n" + TYPES + members + "\n}\n";
    }

    private static void same(String members) {
        LoweringFixture.assertSameMethodAsReference(source(members), "m", StdlibRegistry.defaultRegistry());
    }

    private static void rejected(String members) {
        LoweringFixture.assertSameMethodRejectionAsReference(source(members), "m", StdlibRegistry.defaultRegistry());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // Arms and defaults, in every place the reference reads them.
            "static BigInteger m(S s) { return switch (s) { case A a -> a.v(); case B b -> b.w(); case D d -> BigInteger.ONE; }; }",
            "static BigInteger m(S s) { return switch (s) { case A a -> a.v(); default -> BigInteger.ONE; }; }",
            "static BigInteger m(S s) { return switch (s) { default -> BigInteger.ONE; }; }",
            "static BigInteger m(S s) { return switch (s) { case B b -> b.v().add(b.w()); case A a -> a.v(); default -> BigInteger.TWO; }; }",
            "static long m(S s) { return switch (s) { case A a -> 1; case B b -> 2; case D d -> 3; }; }",
            "static boolean m(S s) { return switch (s) { case A a -> true; default -> false; }; }",
            "static String m(S s) { return switch (s) { case A a -> \"a\"; default -> \"o\"; }; }",
            "static byte[] m(S s) { return switch (s) { case A a -> new byte[]{1}; default -> new byte[]{2}; }; }",
            "static S m(S s) { return switch (s) { case A a -> a; default -> s; }; }",
            "static A m(S s, BigInteger n) { return switch (s) { case A a -> a; default -> new A(n); }; }",
            "static S m(S s, BigInteger n) { return switch (s) { case A a -> new B(a.v(), n); case B b -> new A(b.w()); case D d -> d; }; }",
            // Blocks, and yield in each.
            "static long m(S s) { return switch (s) { case A a -> { long x = 1; yield x + 1; } default -> 2; }; }",
            "static long m(S s, boolean c) { return switch (s) { case A a -> { if (c) { yield 1; } yield 2; } default -> 3; }; }",
            "static long m(S s, boolean c) { return switch (s) { case A a -> { if (c) { yield 1; } else { yield 2; } } default -> 3; }; }",
            "static long m(S s, boolean c, boolean d) { return switch (s) { case A a -> { if (c) { if (d) { yield 1; } yield 2; } long y = 4; yield y; } default -> 3; }; }",
            "static long m(S s, boolean c) { return switch (s) { case A a -> { if (c) yield 1; yield 2; } default -> 3; }; }",
            "static void n(long a) { } static long m(S s, boolean c) { return switch (s) { case A a -> { n(1); if (c) { n(2); } yield 2; } default -> 3; }; }",
            "static long m(S s) { return switch (s) { case A a -> { yield 1; } default -> { yield 2; } }; }",
            // The colon form.
            "static long m(S s) { return switch (s) { case A a: yield 1; case B b: yield 2; default: yield 3; }; }",
            "static long m(S s) { return switch (s) { case A a: long x = 1; yield x; default: yield 3; }; }",
            "static long m(S s) { return switch (s) { case A a: { yield 1; } default: { yield 3; } }; }",
            "static long m(S s, boolean c) { return switch (s) { case A a: if (c) { yield 1; } else { yield 2; } default: yield 3; }; }",
            "static long m(S s, boolean c) { return switch (s) { case A a: if (c) { yield 1; } yield 2; default: yield 3; }; }",
            // Where a switch stands.
            "static long m(S s) { long x = switch (s) { case A a -> 1; default -> 2; }; return x + 1; }",
            "static long m(S s) { var x = switch (s) { case A a -> 1; default -> 2; }; return x + 1; }",
            "static BigInteger m(S s) { var x = switch (s) { case A a -> a.v(); default -> BigInteger.ONE; }; return x.add(x); }",
            "static BigInteger m(S s) { return h(switch (s) { case A a -> a.v(); default -> BigInteger.ONE; }); }",
            "static BigInteger m(S s) { return (switch (s) { case A a -> a.v(); default -> BigInteger.ONE; }).add(BigInteger.ONE); }",
            "static BigInteger m(S s) { return BigInteger.ONE.add(switch (s) { case A a -> a.v(); default -> BigInteger.ONE; }); }",
            "static boolean m(S s) { if (switch (s) { case A a -> true; default -> false; }) { return false; } return true; }",
            "static long m(S s, boolean c) { return c ? switch (s) { case A a -> 1; default -> 2; } : 3; }",
            "static R m(S s) { return new R(s, switch (s) { case A a -> a.v(); default -> BigInteger.ONE; }); }",
            "static boolean m(S s, S t) { return switch (s) { case A a -> true; default -> false; } && switch (t) { case A a -> false; default -> true; }; }",
            // Selectors.
            "static S g(S s) { return s; } static long m(S s) { return switch (g(s)) { case A a -> 1; default -> 2; }; }",
            "static long m(R r) { return switch (r.s()) { case A a -> 1; default -> 2; }; }",
            "static long m(S s) { return switch ((s)) { case A a -> 1; default -> 2; }; }",
            "static long m(JulcList<S> l) { return switch (l.head()) { case A a -> 1; default -> 2; }; }",
            "static long m(PlutusData d) { return switch (PlutusData.cast(d, S.class)) { case A a -> 1; default -> 2; }; }",
            "static long m(Credential c) { return switch (c) { case Credential.PubKeyCredential p -> 1; case Credential.ScriptCredential q -> 2; }; }",
            "static long m(TxOut t) { return switch (t.datum()) { case OutputDatum.NoOutputDatum n -> 1; default -> 2; }; }",
            "static long m(O o) { return switch (o) { case P p -> 1; }; }",
            "static BigInteger m(O o) { return switch (o) { case P p -> p.x(); }; }",
            "static BigInteger m(T t) { return switch (t) { case T.X x -> BigInteger.ONE; case T.Y y -> switch (y.s()) { case A a -> a.v(); default -> BigInteger.TWO; }; }; }",
            "static boolean m(BigInteger a, S s) { return a.equals(switch (s) { case A x -> x.v(); default -> BigInteger.ONE; }); }",
            // Nested arms, field reads and shadowing.
            "static BigInteger m(S s, S t) { return switch (s) { case A a -> switch (t) { case A b -> a.v().add(b.v()); default -> a.v(); }; default -> BigInteger.ONE; }; }",
            "static BigInteger m(S s, S t) { return switch (s) { case A a -> switch (t) { case B b -> a.v().add(b.w()); default -> a.v(); }; case B c -> switch (t) { case A a -> c.w().add(a.v()); default -> c.v(); }; default -> BigInteger.ONE; }; }",
            "static final long a = 3; static long m(S s) { return switch (s) { case A a -> 1; default -> a; }; }",
            "static final long a = 3; static long m(S s) { return switch (s) { case A a when a.v().signum() > 0 -> 1; default -> a; }; }",
            "static final long a = 3; static long m(S s) { return switch (s) { case A a when a.v().signum() > 0 -> 1; case A b -> a; default -> a + 1; }; }",
            "static long m(S s) { var y = s; return switch (y) { case A a -> { var z = a; yield z.v().signum(); } default -> 2; }; }",
            "static BigInteger m(S s) { return switch (s) { case A a -> { long x = 1; if (x > 0) { var a2 = a; yield a2.v(); } yield a.v(); } default -> BigInteger.ONE; }; }",
            "static long m(S s) { long x = switch (s) { case A a -> { long y = 1; yield y; } default -> { long y = 2; yield y; } }; long y = x + 1; return y; }",
            // A guard, one arm at a time.
            "static long m(S s) { return switch (s) { case A a when a.v().signum() > 0 -> 1; case A a -> 2; default -> 3; }; }",
            "static long m(S s) { return switch (s) { case A a when ok(a.v()) -> 1; case B b when ok(b.w()) -> 2; default -> 3; }; }",
            "static long m(S s, boolean c) { return switch (s) { case A a when c -> 1; case A a -> 2; case B b -> 3; case D d -> 4; }; }",
            "static long m(S s) { return switch (s) { case A a when true -> 1; default -> 2; }; }",
            "static long m(S s) { return switch (s) { case A a when ON -> 1; case B b -> 2; case D d -> 3; }; }",
            "static long m(S s) { return switch (s) { case A a when 1 == 1 -> 1; case B b -> 2; case D d -> 3; }; }",
            "static long m(S s) { return switch (s) { case A a when box(a.v()) -> 1; default -> 3; }; }",
            "static long m(S s, Boolean c) { return switch (s) { case A a when c -> 1; default -> 3; }; }",
            "static long m(O o, boolean c) { return switch (o) { case P p when c -> 1; case P p -> 2; }; }",
            "static long m(O o, boolean c) { return switch (o) { case P p when c -> 1; default -> 2; }; }",
            "static long m(S s, boolean c, boolean d) { return switch (s) { case A a when c -> 1; case A a when d -> 2; case B b when c -> 3; default -> 4; }; }",
            "static BigInteger m(S s, boolean c) { return switch (s) { case A a when c -> a.v(); case A b -> b.v(); default -> BigInteger.ONE; }; }",
            "static BigInteger m(S s, boolean c) { return switch (s) { case B a when a.v().signum() > 0 -> a.w(); case B b when c -> b.v(); default -> BigInteger.ONE; }; }",
            "static long m(S s, S t) { return switch (s) { case A a when t instanceof A -> 1; case A a when !(t instanceof B) -> 2; default -> 3; }; }",
            "static long m(S s) { return switch (s) { case A a when a instanceof A -> 1; default -> 3; }; }",
            "static long m(S s, O o) { return switch (s) { case A a when o instanceof P -> 1; default -> 3; }; }",
            "static long m(S s, R r) { return switch (s) { case A a when r.s() instanceof B -> 1; default -> 3; }; }",
            "static long m(S s, S t) { return switch (s) { case A a when !(t instanceof B q) -> 1; default -> 3; }; }",
            "static long m(S s, S t) { return switch (s) { case A a when switch (t) { case A b -> true; default -> false; } -> 1; default -> 3; }; }",
            "static long m(S s, S t, boolean c) { return switch (s) { case A a when switch (t) { case A b when c -> true; default -> false; } -> 1; default -> 3; }; }",
            "static long m(S s, S t) { return switch (s) { case A a when a.v().signum() > 0 -> switch (t) { case A b when b.v().signum() > 0 -> 1; default -> 2; }; default -> 3; }; }",
            "static long m(S s, S t) { return switch (s) { case A a when a.v().signum() > 0 -> switch (t) { case A b when a.v().signum() > 1 -> b.v().signum(); default -> 2; }; default -> 3; }; }",
            "static long m(S s, T t) { return switch (s) { case A a when t instanceof T.X -> 1; default -> 3; }; }",
            "static long m(S s, JulcList<BigInteger> l) { return switch (s) { case A a when !l.isEmpty() -> 1; default -> 3; }; }",
            // Constants in a guard have Java's width.
            "static long m(S s) { return switch (s) { case A a when (2147483647 + 1) < a.v().signum() -> 1; default -> 3; }; }",
            "static long m(S s) { return switch (s) { case A a when K + 1 < a.v().signum() -> 1; default -> 3; }; }",
            "static long m(S s) { return switch (s) { case A a when ((int) 2147483648L) < a.v().signum() -> 1; default -> 3; }; }",
            "static long m(S s) { return switch (s) { case A a when 0xFFFFFFFF < a.v().signum() -> 1; default -> 3; }; }",
            "static long m(S s) { return switch (s) { case A a when (W << 32) > a.v().signum() -> 1; default -> 3; }; }",
            "static long m(S s) { return switch (s) { case A a when ~1 < a.v().signum() -> 1; default -> 3; }; }",
            "static long m(S s) { return switch (s) { case A a when (5 > 4 ? 1 : 2) > a.v().signum() -> 1; default -> 3; }; }",
            "static long m(S s) { return switch (s) { case A a when -2147483648 < a.v().signum() -> 1; default -> 3; }; }",
            "static long m(S s) { return switch (s) { case A a when -2_147_483_648 < a.v().signum() -> 1; default -> 3; }; }",
            "static long m(S s) { return switch (s) { case A a when -9223372036854775808L < a.v().signum() -> 1; default -> 3; }; }",
            "static long m(S s) { final int N = 5; return switch (s) { case A a when N > a.v().signum() -> 1; default -> 3; }; }",
            "static long m(S s) { final long N = 2147483647; return switch (s) { case A a when N + 1 > 0 -> 1; case B b -> 2; case D d -> 3; }; }",
            "static long m(S s) { return switch (s) { case A a when (long) a.v().signum() > 0 -> 1; default -> 3; }; }",
            "static long m(S s, boolean c) { return switch (s) { case A a when (boolean) c -> 1; default -> 3; }; }",
            "static long m(S s, boolean c) { return switch (s) { case A a when (Boolean) c -> 1; default -> 3; }; }",
            "static long m(S s, String t) { return switch (s) { case A a when t.equals(\"x\") -> 1; default -> 3; }; }",
            "static long m(S s) { return switch (s) { case A a when \"a\" == \"a\" -> 1; case B b -> 2; case D d -> 3; }; }",
            "static long m(S s, boolean c) { return switch (s) { case A a when c && \"a\" + \"b\" == \"ab\" -> 1; default -> 3; }; }",
            "static long m(S s) { return switch (s) { case A a when \"a\" + (2147483647 + 1) == \"a-2147483648\" -> 1; case B b -> 2; case D d -> 3; }; }",
            "static long m(S s, JulcList<Boolean> l) { return switch (s) { case A a when l.head() -> 1; default -> 3; }; }",
            "static long m(S s, JulcList<BigInteger> l) { return switch (s) { case A a when l.isEmpty() -> 1; default -> 3; }; }",
            "static long m(S s, JulcList<BigInteger> l) { return switch (s) { case A a when l.size() > 1 -> 1; default -> 3; }; }",
            "static long m(S s, BigInteger n) { return switch (s) { case A a when n.compareTo(a.v()) > 0 -> 1; default -> 3; }; }",
            "static long m(S s, BigInteger n) { return switch (s) { case A a when a.v().equals(n) -> 1; default -> 3; }; }",
            "static long m(S s, PlutusData d, PlutusData e) { return switch (s) { case A a when Builtins.equalsData(d, e) -> 1; default -> 3; }; }",
    })
    void lowersAsTheReference(String members) {
        same(members);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // Selectors of no sealed interface.
            "static long m(int a) { return switch (a) { case 1 -> 1; default -> 2; }; }",
            "static long m(String a) { return switch (a) { case \"x\" -> 1; default -> 2; }; }",
            "static long m(BigInteger a) { return switch (a) { default -> 2; }; }",
            "static long m(byte[] a) { return switch (a) { default -> 2; }; }",
            "static long m(R r) { return switch (r) { case R x -> 2; }; }",
            "static long m(A a) { return switch (a) { case A x -> 2; }; }",
            "static long m(PlutusData d) { return switch (d) { default -> 2; }; }",
            "sealed interface G<X> permits H { } record H() implements G<BigInteger> { } static long m(G<BigInteger> g) { return switch (g) { case H h -> 2; }; }",
            "static long m(boolean c) { return switch (c ? new A(BigInteger.ONE) : new D()) { case A a -> 1; default -> 2; }; }",
            "static long m(BigInteger n) { return switch (new A(n)) { case A a -> 1; }; }",
            "static boolean m(BigInteger a, boolean c) { return a.equals(switch (c ? 1 : 0) { case 1 -> a; default -> BigInteger.ONE; }); }",
            // Labels.
            "static long m(S s) { return switch (s) { case S t -> 2; }; }",
            "static long m(S s) { return switch (s) { case A(BigInteger v) -> 2; default -> 3; }; }",
            "static long m(S s) { return switch (s) { case A(var v) -> 2; default -> 3; }; }",
            // Colon groups that need fall-through.
            "static long m(S s) { return switch (s) { case A a: a.v(); default: yield 2; }; }",
            "static long m(S s, boolean c) { return switch (s) { case A a: if (c) { yield 1; } default: yield 2; }; }",
            // A case block that yields on no path the reference reads.
            "static long m(S s) { return switch (s) { case A a -> { { yield 1; } } default -> 2; }; }",
            "static long m(S s, boolean c) { return switch (s) { case A a -> { if (c) { yield 1; } else { { yield 2; } } } default -> 2; }; }",
            // Updates in an arm.
            "static long m(S s) { long x = 1; var y = switch (s) { case A a -> { x = 2; yield x; } default -> 2; }; return x; }",
            "static long m(S s) { var y = switch (s) { case A a -> { a = new A(BigInteger.ONE); yield 1; } default -> 2; }; return y; }",
            "static long m(S s) { var y = switch (s) { case A a -> { long z = 1; z = 2; yield z; } default -> 2; }; return y; }",
            // Guards the subset does not take.
            "static long m(S s, long n) { return switch (s) { case A a when n + 1 > 0 -> 1; default -> 3; }; }",
            "static long m(S s, long n) { return switch (s) { case A a when -n > 0 -> 1; default -> 3; }; }",
            "static long m(S s, long n) { return switch (s) { case A a when ~n > 0 -> 1; default -> 3; }; }",
            "static long m(S s, long n) { return switch (s) { case A a when (int) n > 0 -> 1; default -> 3; }; }",
            "static long m(S s, BigInteger n) { return switch (s) { case A a when n.intValue() > 0 -> 1; default -> 3; }; }",
            "static long m(S s, BigInteger n) { return switch (s) { case A a when n.longValue() > 0 -> 1; default -> 3; }; }",
            "static long m(S s) { return switch (s) { case A a when 'a' > 0 -> 1; default -> 3; }; }",
            "static long m(S s) { return switch (s) { case A a when (byte) 1 > 0 -> 1; default -> 3; }; }",
            "static long m(S s) { return switch (s) { case A a when (short) 1 > 0 -> 1; default -> 3; }; }",
            "static long m(S s, String t) { return switch (s) { case A a when t == \"x\" -> 1; default -> 3; }; }",
            "static long m(S s, BigInteger n) { return switch (s) { case A a when a.v() == n -> 1; default -> 3; }; }",
            "static long m(S s, String t) { return switch (s) { case A a when t.equals(a.v()) -> 1; default -> 3; }; }",
            "static S g(S s) { return s; } static long m(S s) { return switch (s) { case A a when g(s) instanceof A -> 1; default -> 3; }; }",
            "static long m(S s, S t) { return switch (s) { case A a when t instanceof A b -> 1; default -> 3; }; }",
            "static long m(S s, S t) { return switch (s) { case A a when (t instanceof A b) && b.v().signum() > 0 -> 1; default -> 3; }; }",
            "static long m(S s, S t) { return switch (s) { case A a when !(t instanceof A b) && t == s -> 1; default -> 3; }; }",
            "static long m(S s, S t) { return switch (s) { case A a when !(t instanceof A b) || b.v().signum() > 0 -> 1; default -> 3; }; }",
            "static boolean f(boolean a, boolean b) { return a; } static long m(S s, S t) { return switch (s) { case A a when f(t instanceof A q, t instanceof A q) -> 1; default -> 3; }; }",
            "static long m(S s) { return switch (s) { case A a when false -> 1; default -> 3; }; }",
            "static long m(S s) { return switch (s) { case A a when 1 == 2 -> 1; default -> 3; }; }",
            "static final boolean OFF = false; static long m(S s) { return switch (s) { case A a when OFF -> 1; default -> 3; }; }",
            "static long m(S s) { return switch (s) { case A a when 1 / 0 > 0 -> 1; default -> 3; }; }",
            "static long m(S s) { return switch (s) { case A a when 1.5 > 0 -> 1; default -> 3; }; }",
            "static final int Z = 1 / 0; static long m(S s) { return switch (s) { case A a when Z > a.v().signum() -> 1; default -> 3; }; }",
            "static long m(S s, boolean c, boolean d) { return switch (s) { case A a when c & d -> 1; default -> 3; }; }",
            "static long m(S s, boolean c, boolean d) { return switch (s) { case A a when c | d -> 1; default -> 3; }; }",
            "static long m(S s, boolean c, boolean d) { return switch (s) { case A a when c ^ d -> 1; default -> 3; }; }",
            "static long m(S s, PlutusData d, PlutusData e) { return switch (s) { case A a when !Builtins.equalsData(d, e) -> 1; default -> 3; }; }",
            "static long m(S s, long n) { return switch (s) { case A a when Math.abs(n) > 0 -> 1; default -> 3; }; }",
            // Statements.
            "static long m(S s) { switch (s) { case A a -> { return 1; } default -> { return 2; } } }",
            "static long m(S s) { switch (s) { default -> { } } return 2; }",
    })
    void rejectedAsTheReference(String members) {
        rejected(members);
    }
}
