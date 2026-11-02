package org.julclang.compiler.frontend.javac.lower;

import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerFrontend;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.CompilerTestVm;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.OptimizationLevel;
import org.julclang.core.PlutusData;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.vm.EvalResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigInteger;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
            import org.julclang.stdlib.annotation.NewType;
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
            // A lambda reads the arm and the guard around it, and a switch reads a lambda's parameter.
            "static long m(S s, JulcList<BigInteger> xs) { return switch (s) { case A a -> xs.map(x -> x.add(a.v())).size(); default -> 3; }; }",
            "static long m(S s, JulcList<BigInteger> xs) { return switch (s) { case B b -> xs.filter(x -> x.compareTo(b.w()) > 0).size(); default -> 3; }; }",
            "static long m(S s, JulcList<BigInteger> xs) { return switch (s) { case A a when xs.any(x -> x.equals(a.v())) -> 1; default -> 3; }; }",
            "static boolean m(JulcList<S> xs) { return xs.any((S v) -> switch (v) { case A a -> a.v().signum() > 0; default -> false; }); }",
            "static JulcList<BigInteger> m(JulcList<S> xs) { return xs.map((S v) -> switch (v) { case A a -> a.v(); case B b when b.w().signum() > 0 -> b.w(); default -> BigInteger.ONE; }); }",
            "static boolean m(JulcList<S> xs, S y) { return xs.any(v -> v.equals(y)); }",
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
            "static BigInteger m(S s) { var t = switch (s) { case A a -> new Tuple2<>(a.v(), BigInteger.ONE); default -> new Tuple2<>(BigInteger.ONE, BigInteger.TWO); }; return t.second(); }",
            "static long m(S s, boolean c) { return switch (s) { case A a when c: yield 1; case A a: yield 2; default: yield 3; }; }",
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
            "static long m(S s, S t, BigInteger n) { return switch (s) { case A a when switch (t) { case A c when c.v().compareTo(n) > 0 -> true; default -> false; } -> 1; default -> 3; }; }",
            "static long m(S s, BigInteger n) { return switch (s) { case A a when !a.v().equals(n) -> 1; default -> 3; }; }",
            "static long m(S s, Boolean p, boolean q) { return switch (s) { case A a when p == q -> 1; default -> 3; }; }",
            "static long m(S s, BigInteger n) { var q = new A(n); return switch (s) { case B b when q instanceof A -> 1; default -> 3; }; }",
            "static long m(S s) { long n = 5; return switch (s) { case A a when n > a.v().signum() -> 1; default -> 3; }; }",
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
            "static long m(long a) { return switch (a) { default -> 2; }; }",
            "static long m(boolean c) { return switch (c) { default -> 2; }; }",
            "enum E { X, Y } static long m(E e) { return switch (e) { case X -> 1; default -> 2; }; }",
            // What javac rejects, which comes first on both frontends.
            "static long m(S s) { return switch (s) { case A a -> 1; }; }",
            "static long m(S s) { return switch (s) { case A a when a.v().signum() > 0 -> 1; case B b -> 2; case D d -> 3; }; }",
            "static long m(S s) { return switch (s) { default -> 1; case A a -> 2; }; }",
            "static long m(S s) { return switch (s) { default: yield 1; case A a: yield 2; }; }",
            "static long m(S s) { return switch (s) { case A a -> 1; case A b -> 2; default -> 3; }; }",
            "static long m(S s) { return switch (s) { case A a -> 1; case A b when true -> 2; default -> 3; }; }",
            "static long m(S s) { return switch (s) { case A a when true -> 1; case A b -> 2; default -> 3; }; }",
            "static long m(S s) { return switch (s) { default -> 1; default -> 2; }; }",
            "static long m(S s) { return switch (s) { case A a -> 1; default: yield 2; }; }",
            "static long m(S s) { return switch (s) { case A a, B b -> 1; default -> 2; }; }",
            "static long m(S s) { return switch (s) { case A _, B _ -> 1; default -> 2; }; }",
            "static long m(S s, A a) { return switch (s) { case A a -> 1; default -> 2; }; }",
            "static long m(S s) { return switch (s) { case A a when 1 -> 1; default -> 2; }; }",
            "static long m(S s) { long x = 1; x = 2; return switch (s) { case A a when x > 0 -> 1; default -> 2; }; }",
            "static long m(S s) { return switch (s) { case 1 -> 1; default -> 2; }; }",
            "static long m(S s) { return switch (s) { case T.X x -> 1; default -> 2; }; }",
            // A null label.
            "static long m(S s) { return switch (s) { case null -> 1; case A a -> 2; default -> 3; }; }",
            "static long m(S s) { return switch (s) { case A a -> 2; case null, default -> 3; }; }",
            // Labels.
            "static long m(S s) { return switch (s) { case S t -> 2; }; }",
            "static long m(S s) { return switch (s) { case C.S t -> 2; }; }",
            "static long m(S s) { return switch (s) { case Object t -> 2; }; }",
            "sealed interface W permits V.W { } static final class V { record W() implements C.W { } } static long m(W w) { return switch (w) { case W t -> 2; }; }",
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
            "record F(boolean on) { } static long m(S s) { F f = new F(false); return switch (s) { case A a when f.on -> 1; default -> 3; }; }",
            "static long m(S s, byte[] k, byte[] n, byte[] g) { return switch (s) { case A a when Builtins.verifyEd25519Signature(k, n, g) -> 1; default -> 3; }; }",
            "static long m(S s, byte[] k) { return switch (s) { case A a when Builtins.readBit(k, 0) -> 1; default -> 3; }; }",
            "record Q(long n) { } static long m(S s, JulcList<Q> l) { return switch (s) { case A a when l.head().n() > 0 -> 1; default -> 3; }; }",
            "static long m(S s) { var xs = JulcList.of(true); return switch (s) { case A a when !xs.head() -> 1; default -> 3; }; }",
            "static long m(S s, PlutusData d) { var v = switch (s) { case A a -> Builtins.tailList(Builtins.unListData(d)); default -> d; }; return JulcList.of(v).size(); }",
            "static long m(S s) { return switch (s) { case A a when (1L << 1L) > 5 -> 1; default -> 3; }; }",
            "static long m(S s, Boolean p, Boolean q) { return switch (s) { case A a when p == q -> 1; default -> 3; }; }",
            "static long big(long a) { return a; } static long big(int a) { return a; } static long m(S s) { return switch (s) { case A a when big(1) > 0 -> 1; default -> 3; }; }",
            "static final S Z = new A(BigInteger.ONE); static long m(S s) { return switch (s) { case A a when Z instanceof A -> 1; default -> 3; }; }",
            "static S g(S s) { return s; } static long m(S s) { var q = g(s); return switch (s) { case A a when q instanceof A -> 1; default -> 3; }; }",
            // Statements.
            "static long m(S s) { switch (s) { case A a -> { return 1; } default -> { return 2; } } }",
            "static long m(S s) { switch (s) { default -> { } } return 2; }",
    })
    void rejectedAsTheReference(String members) {
        rejected(members);
    }

    private static final String VALIDATOR = """
            import java.math.BigInteger;
            import org.julclang.core.PlutusData;
            import org.julclang.stdlib.annotation.Entrypoint;
            import org.julclang.stdlib.annotation.MintingValidator;
            import org.julclang.stdlib.annotation.Param;

            @MintingValidator
            class V {
                sealed interface Action { record Mint(BigInteger n) implements Action { } record Burn() implements Action { } }
                @Param static boolean ENABLED = true;
                @Param static long LIMIT = 5;
                static final long M = LIMIT;
                @Entrypoint
                static boolean validate(Action action, PlutusData ctx) {
                    return switch (action) {
                        case Action.Mint m when ENABLED && m.n().signum() > 0 -> true;
                        case Action.Mint m when m.n().compareTo(BigInteger.valueOf(LIMIT)) < 0 -> false;
                        case Action.Mint m when LIMIT > 0 && M > 1 -> m.n().signum() == 0;
                        default -> false;
                    };
                }
            }
            """;

    /** A {@code @Param} is the applied parameter in a guard, and so is what is read through a constant of it. */
    @Test
    void aValidatorsGuardReadsItsParameters() {
        LoweringFixture.assertSameAsReference(VALIDATOR);
    }

    /** What Java cannot build: a switch reads Data no strict boundary read. Outcomes are ADR-067's, on this frontend's script. */
    private static EvalResult evaluate(String members, PlutusData... arguments) {
        var program = new JulcCompiler(StdlibRegistry.defaultRegistry(), new CompilerOptions().setFrontend(CompilerFrontend.JAVAC)
                .setOptimizationLevel(OptimizationLevel.DEFAULT)).compileMethod(source(members), "m").program();
        return CompilerTestVm.pv11("Java").evaluateWithArgs(program, List.of(arguments));
    }

    /** {@link #evaluate} at each level, the one outcome they share. */
    private static Class<?> outcome(String members, PlutusData... arguments) {
        var outcomes = new HashSet<Class<?>>();
        for (var level : OptimizationLevel.values()) {
            var program = new JulcCompiler(StdlibRegistry.defaultRegistry(), new CompilerOptions().setFrontend(CompilerFrontend.JAVAC)
                    .setOptimizationLevel(level)).compileMethod(source(members), "m").program();
            outcomes.add(CompilerTestVm.pv11("Java").evaluateWithArgs(program, List.of(arguments)).getClass());
        }
        assertEquals(1, outcomes.size(), () -> "levels disagree on " + members);
        return outcomes.iterator().next();
    }

    private static PlutusData.ListData list(PlutusData... elements) {
        return new PlutusData.ListData(List.of(elements));
    }

    /** Data Java cannot build, in an element: an inferred parameter is the written type's, so what a tag that is no constructor does is too. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "v -> switch (v) { case A a -> true; default -> false; }           | (S v) -> switch (v) { case A a -> true; default -> false; }",
            "v -> switch (v) { case A a when c -> true; default -> false; }    | (S v) -> switch (v) { case A a when c -> true; default -> false; }",
    })
    void aTagThatIsNoConstructorFailsInAnElement(String inferred, String written) {
        String source = "static boolean m(boolean c, JulcList<S> xs) { return xs.any(%s); }";
        for (var element : List.of(constr(9), constr(3, 1))) {
            assertEquals(EvalResult.Failure.class, outcome(source.formatted(inferred), constr(1), list(element)));
            assertEquals(EvalResult.Failure.class, outcome(source.formatted(written), constr(1), list(element)));
        }
        assertEquals(EvalResult.Success.class, outcome(source.formatted(inferred), constr(1), list(constr(2))));
    }

    /** A tag test outside a guard says no to a tag that is no constructor, written or inferred, and a one-constructor sum checks its tag in a guard. */
    @Test
    void aTagTestOnAnElementReadsAsTheWrittenType() {
        String test = "static boolean m(JulcList<S> xs) { return xs.any(%s -> { if (v instanceof A q) { return q.v().signum() > 0; } return true; }); }";
        for (var parameter : List.of("v", "(S v)")) {
            String members = test.formatted(parameter);
            assertEquals(EvalResult.Success.class, outcome(members, list(constr(9))));
            assertEquals(EvalResult.Success.class, outcome(members, list(constr(0, 1))));
        }
        for (var parameter : List.of("o", "(O o)")) {
            String one = "static boolean m(boolean c, JulcList<O> os) { return os.any(" + parameter + " -> switch (o) { case P p when c -> true; case P p -> false; }); }";
            assertEquals(EvalResult.Failure.class, outcome(one, constr(1), list(constr(3, 1))));
            assertEquals(EvalResult.Success.class, outcome(one, constr(1), list(constr(0, 1))));
        }
    }

    private static PlutusData constr(int tag, long... fields) {
        return new PlutusData.ConstrData(tag, java.util.Arrays.stream(fields).mapToObj(v -> (PlutusData) new PlutusData.IntData(BigInteger.valueOf(v))).toList());
    }

    @Test
    void aTagThatIsNoConstructorFailsAndReachesNoDefault() {
        String plain = "static long m(S s) { return switch (s) { case A a -> 1; default -> 2; }; }";
        String guarded = "static long m(S s, boolean c) { return switch (s) { case A a when c -> 1; default -> 2; }; }";
        assertInstanceOf(EvalResult.Failure.class, evaluate(plain, constr(9)));
        assertInstanceOf(EvalResult.Failure.class, evaluate(guarded, constr(9), constr(1)));
        assertInstanceOf(EvalResult.Success.class, evaluate(plain, constr(2)));
    }

    /** F11: a guarded switch of one constructor checks the tag, and the same in a guard's {@code instanceof}. */
    @Test
    void aWrongTagOnOneConstructorFails() {
        String guarded = "static long m(O o, boolean c) { return switch (o) { case P p when c -> 1; case P p -> 2; }; }";
        assertInstanceOf(EvalResult.Failure.class, evaluate(guarded, constr(3, 1), constr(1)));
        assertInstanceOf(EvalResult.Success.class, evaluate(guarded, constr(0, 1), constr(1)));
        String test = "static long m(S s, O o) { return switch (s) { case A a when o instanceof P -> 1; default -> 3; }; }";
        assertInstanceOf(EvalResult.Failure.class, evaluate(test, constr(0, 1), constr(3, 1)));
    }

    /** F3: {@code instanceof} in a guard is a total dispatch, and {@code false} is not what a bad tag gives. */
    @Test
    void aBadTagInAGuardsInstanceofFails() {
        String test = "static long m(S s, S t) { return switch (s) { case A a when t instanceof A -> 1; default -> 3; }; }";
        assertInstanceOf(EvalResult.Failure.class, evaluate(test, constr(0, 1), constr(9)));
        assertInstanceOf(EvalResult.Success.class, evaluate(test, constr(0, 1), constr(2)));
    }

    private static String javacCode(String members) {
        var failure = assertThrows(CompilerException.class, () -> new JulcCompiler(StdlibRegistry.defaultRegistry(),
                new CompilerOptions().setFrontend(CompilerFrontend.JAVAC)).compileMethod(source(members), "m"));
        return failure.diagnostics().getFirst().code();
    }

    /** A lambda's body is ordinary code: {@code ==} on references there is D6's, as it is in an arm of a {@code switch} in a guard. */
    @ParameterizedTest
    @ValueSource(strings = {
            "lst.any(x -> x == str)",
            "lst.any(x -> x != str)",
            "lst.any(x -> str == x)",
            "lst.any(x -> x == \"ab\")",
            "lst.any(x -> \"ab\" == x)",
            "lst.any(x -> { var y = x; return y == str; })",
            "lst.any(x -> { return x == str; })",
            "lst.filter(x -> x == str).isEmpty()",
            "!lst.any(x -> x == str)",
            "c && lst.any(x -> x == str)",
            "lst.any(x -> l.any(y -> x == str))",
            "lst.any(x -> (x == str) == c)",
            "l.any(x -> x == n)",
            "ls.any(v -> v == t)",
    })
    void anIdentityComparisonInALambdaInAGuard(String guard) {
        assertEquals("JULC0086", javacCode("static long m(S s, S t, boolean c, BigInteger n, String str, JulcList<String> lst, JulcList<BigInteger> l, JulcList<S> ls) "
                + "{ return switch (s) { case A a when " + guard + " -> 1; default -> 2; }; }"));
    }

    /** What is no comparison of references in a lambda in a guard stays what it was. */
    @ParameterizedTest
    @ValueSource(strings = {
            "lst.any(x -> x.equals(str))",
            "l.any(x -> x.signum() == 1)",
            "l.any(x -> x.signum() != k)",
            "lb.any(x -> x == c)",
    })
    void aComparisonOfValuesInALambdaInAGuard(String guard) {
        same("static long m(S s, boolean c, long k, String str, JulcList<String> lst, JulcList<BigInteger> l, JulcList<Boolean> lb) "
                + "{ return switch (s) { case A a when " + guard + " -> 1; default -> 2; }; }");
    }

    /** After a lambda the guard is still a guard's to judge, so D6's rule does not read a later {@code ==}: the lowering's stop does. */
    @Test
    void aGuardGoesOnAfterALambda() {
        assertEquals("JULC0084", javacCode("static long m(S s, BigInteger n, JulcList<BigInteger> l) { return switch (s) { case A a when l.any(x -> true) && a.v() == n -> 1; default -> 2; }; }"));
    }

    /**
     * D6 in a guard: {@code ==} is ADR-067's, and the rest holds as anywhere, after the guard's own rules. A guard that is
     * constant as a whole has Java's value and reaches no term, and an arm of a {@code switch} in a guard is ordinary code.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "JULC0090 | static long m(S s, boolean c) { return switch (s) { case A a when c && (\"a\" + (2147483647 + 1) == \"a-2147483648\") -> 1; default -> 3; }; }",
            "JULC0090 | static long m(S s) { return switch (s) { case A a when (\"a\" + 1).equals(\"a1\") -> 1; default -> 3; }; }",
            "JULC0087 | static long m(S s, byte[] b, byte[] c) { return switch (s) { case A a when b.equals(c) -> 1; default -> 3; }; }",
            "JULC0072 | static long m(S s, String t) { return switch (s) { case A a when t.equals(a.v()) -> 1; default -> 3; }; }",
            "JULC0084 | static long m(S s, BigInteger n) { return switch (s) { case A a when a.v().equals(s) -> 1; default -> 3; }; }",
            "JULC0072 | static long m(S s, BigInteger n) { return switch (s) { case A a when a.v() == n -> 1; default -> 3; }; }",
            "JULC0086 | static long m(S s, S t, BigInteger n) { return switch (s) { case A a when switch (t) { case A b -> b.v() == n; default -> false; } -> 1; default -> 3; }; }",
            "JULC0086 | static long m(S s, BigInteger n) { return switch (s) { case A a -> a.v() == n ? 1 : 2; default -> 3; }; }",
            "JULC0090 | static long m(S s) { return switch (s) { case A a -> (\"a\" + 1).equals(\"a1\") ? 1 : 2; default -> 3; }; }",
    })
    void equalityInAndAroundAGuard(String code, String members) {
        assertEquals(code, javacCode(members));
    }

    /** A list method that takes a function is typed by what the registry declares on the receiver the reference sees. */
    @ParameterizedTest
    @ValueSource(strings = {
            "static long m(S s, JulcList<BigInteger> l) { return switch (s) { case A a when !l.any(x -> x.signum() > 0) -> 1; default -> 3; }; }",
            "static long m(S s, JulcList<BigInteger> l) { return switch (s) { case A a when !l.all(x -> x.signum() > 0) -> 1; default -> 3; }; }",
            "static long m(S s, JulcList<BigInteger> l, boolean c) { return switch (s) { case A a when l.all(x -> x.signum() > 0) && c -> 1; default -> 3; }; }",
            "static long m(S s, JulcList<BigInteger> l, boolean c) { return switch (s) { case A a when c || l.any(x -> x.signum() > 0) -> 1; default -> 3; }; }",
            "static long m(S s, JulcList<BigInteger> l, boolean c) { return switch (s) { case A a when l.any(x -> x.signum() > 0) == c -> 1; default -> 3; }; }",
            "static long m(S s, JulcList<BigInteger> l) { return switch (s) { case A a when !l.filter(x -> x.signum() > 0).isEmpty() -> 1; default -> 3; }; }",
            "static long m(S s, JulcList<BigInteger> l) { return switch (s) { case A a when l.filter(x -> x.signum() > 0).size() > 0 -> 1; default -> 3; }; }",
            "static long m(S s, JulcList<BigInteger> l) { return switch (s) { case A a when !l.map(x -> x.add(x)).isEmpty() -> 1; default -> 3; }; }",
            "static long m(S s, JulcList<BigInteger> l, boolean c) { return switch (s) { case A a when l.filter(x -> true).any(y -> y.signum() > 0) && c -> 1; default -> 3; }; }",
            "static long m(S s, S t, JulcList<BigInteger> l) { return switch (s) { case A a when switch (t) { case A b -> !l.any(x -> x.signum() > 0); default -> false; } -> 1; default -> 3; }; }",
            "static boolean m(S s, JulcList<BigInteger> k, JulcList<BigInteger> l) { return k.any(w -> switch (s) { case A a when !l.any(x -> x.signum() > 0) -> true; default -> false; }); }",
    })
    void aGuardCombinesAListMethodThatTakesAFunction(String members) {
        same(members);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "static long m(S s, JulcList<BigInteger> l) { return switch (s) { case A a when !(l).any(x -> x.signum() > 0) -> 1; default -> 3; }; }",
            "static long m(S s, JulcList<BigInteger> l) { return switch (s) { case A a when l.find(x -> x.signum() > 0).signum() > 0 -> 1; default -> 3; }; }",
            "static long m(S s, JulcList<BigInteger> l) { return switch (s) { case A a when l.map(x -> x.add(x)).head().signum() > 0 -> 1; default -> 3; }; }",
    })
    void aReceiverOrResultTheRegistryDoesNotDeclareStaysRejected(String members) {
        rejected(members);
    }

    /**
     * A guard's type is read before it is lowered, so a variable of a {@code switch} in the guard has no binder, and the
     * reference reads nothing off it. Its message quotes the {@code switch} over several lines, written here as it prints it.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "static long m(S s, S t) { return switch (s) { case A a when (switch(t) { case A i -> i.v().signum() > 0; default -> false; }) -> 1; default -> 3; }; }",
            "static long m(S s, S t) { return switch (s) { case A a when (switch(t) { case A i -> i.v().signum() >= 0; default -> false; }) -> 1; default -> 3; }; }",
            "static long m(S s, S t) { return switch (s) { case A a when (switch(t) { case A i -> { var b = i.v().signum() > 0; yield b; } default -> false; }) -> 1; default -> 3; }; }",
            "static long m(S s, R r) { return switch (s) { case A a when (switch(r.s()) { case B i -> i.w().compareTo(i.v()) > 0; default -> false; }) -> 1; default -> 3; }; }",
            "static long m(S s, T t) { return switch (s) { case A a when (switch(t) { case T.Y i -> i.s() instanceof A; default -> false; }) -> 1; default -> 3; }; }",
            "static long m(S s, R r) { return switch (s) { case A a when (r).n().signum() > 0 -> 1; default -> 3; }; }",
            "@NewType record Ni(BigInteger value) { } sealed interface Wn { record U(Ni n) implements Wn { } record Z() implements Wn { } } "
                    + "static long m(S s, Wn t) { return switch (s) { case A a when (switch(t) { case Wn.U i -> i.n().value().signum() > 0; default -> false; }) -> 1; default -> 3; }; }",
    })
    void aReadOfWhatHasNoBinderYetIsNoEstablishedType(String members) {
        LoweringFixture.assertSameMethodRejectionAsReference(source(members), "m", StdlibRegistry.defaultRegistry(), LoweringFixture.SPACING);
    }

    /** What a variable of the arm or around the switch gives a guard, through as many accessors as it takes. */
    @ParameterizedTest
    @ValueSource(strings = {
            "static long m(S s, S t) { return switch (s) { case A a when (switch (t) { case A i -> a.v().signum() > 0; default -> false; }) -> 1; default -> 3; }; }",
            "static long m(S s, R r) { return switch (s) { case A a when r.n().signum() > 0 -> 1; default -> 3; }; }",
            "record N(R r) { } static long m(S s, N n) { return switch (s) { case A a when n.r().n().signum() > 0 -> 1; default -> 3; }; }",
            "static long m(S s, S t) { return switch (s) { case A a when (switch (t) { case A i -> i instanceof A; default -> false; }) -> 1; default -> 3; }; }",
    })
    void aReadOfWhatHasABinderKeepsItsType(String members) {
        same(members);
    }

    /** An inferred parameter says nothing to a guard by name, whatever the receiver, so it is rejected before the guard is lowered. */
    @ParameterizedTest
    @ValueSource(strings = {
            "static long m(S s, JulcList<Boolean> l) { return l.filter(b -> switch (s) { case A q when b -> true; default -> false; }).size(); }",
            "static long m(S s, JulcList<Boolean> l) { return l.filter(b -> switch (s) { case A q when !b -> true; default -> false; }).size(); }",
            "static long m(S s, JulcList<Boolean> l, boolean c) { return l.filter(b -> switch (s) { case A q when b && c -> true; default -> false; }).size(); }",
            "static long m(S s, JulcList<Boolean> l) { return l.filter(b -> { var w = b; return switch (s) { case A q when w -> true; default -> false; }; }).size(); }",
            "static JulcList<Boolean> h(JulcList<Boolean> l) { return l; } static long m(S s, JulcList<Boolean> l) { return ids(l).filter(b -> switch (s) { case A q when b -> true; default -> false; }).size(); }",
            "record F(JulcList<Boolean> bs) { } static long m(S s, F f) { return f.bs().filter(b -> switch (s) { case A q when b -> true; default -> false; }).size(); }",
    })
    void anInferredParameterIsNoGuardByItsName(String members) {
        rejected(members);
    }

    /** Where the reference has the parameter's type too, one term on both frontends. */
    @ParameterizedTest
    @ValueSource(strings = {
            "static JulcList<BigInteger> ids(JulcList<BigInteger> l) { return l; } static long m(S s, JulcList<BigInteger> l) { return ids(l).filter(x -> switch (s) { case A a when x.signum() > 0 -> true; default -> false; }).size(); }",
            "static JulcList<BigInteger> ids(JulcList<BigInteger> l) { return l; } static long m(S s, JulcList<BigInteger> l) { return ids(l).filter(x -> { var w = x; return switch (s) { case A a when w.signum() > 0 -> true; default -> false; }; }).size(); }",
            "static long m(S s, JulcList<BigInteger> l) { return l.tail().filter(x -> switch (s) { case A a when x.signum() > 0 -> true; default -> false; }).size(); }",
            "static long m(S s, JulcList<BigInteger> l) { return (l).filter(x -> switch (s) { case A a when x.signum() > 0 -> true; default -> false; }).size(); }",
            "record F(JulcList<BigInteger> is) { } static long m(S s, F f) { return f.is().filter(x -> switch (s) { case A a when x.signum() > 0 -> true; default -> false; }).size(); }",
            "record Q(BigInteger a) { } static JulcList<Q> ids(JulcList<Q> l) { return l; } static long m(S s, JulcList<Q> l) { return ids(l).filter(q -> switch (s) { case A a when q.a().signum() > 0 -> true; default -> false; }).size(); }",
            "static JulcList<BigInteger> ids(JulcList<BigInteger> l) { return l; } static boolean m(S s, JulcList<BigInteger> l, JulcList<BigInteger> k) { return ids(l).any(x -> k.any(y -> switch (s) { case A a when x.signum() > 0 -> true; default -> false; })); }",
            "static JulcList<BigInteger> ids(JulcList<BigInteger> l) { return l; } static boolean m(S s, JulcList<BigInteger> l, JulcList<BigInteger> k) { return l.any(x -> ids(k).any(y -> switch (s) { case A a when y.signum() > 0 -> true; default -> false; })); }",
            "record G(JulcList<BigInteger> is) { } static JulcList<G> ids(JulcList<G> l) { return l; } static boolean m(S s, JulcList<G> l) { return ids(l).any(g -> g.is().any(y -> switch (s) { case A a when y.signum() > 0 -> true; default -> false; })); }",
            "static long m(S s, JulcList<BigInteger> l) { return l.filter(x -> switch (s) { case A a when x.equals(BigInteger.ONE) -> true; default -> false; }).size(); }",
            "static long m(S s, JulcList<BigInteger> l) { return l.filter(x -> { BigInteger w = x; return switch (s) { case A a when w.signum() > 0 -> true; default -> false; }; }).size(); }",
            "@NewType record Ni(BigInteger value) { } static JulcList<Ni> ids(JulcList<Ni> l) { return l; } static long m(S s, JulcList<Ni> l) { return ids(l).filter(n -> switch (s) { case A a when n.value().signum() > 0 -> true; default -> false; }).size(); }",
            "record Q(BigInteger a) { } static long m(S s, JulcList<Q> l) { return l.filter(q -> switch (s) { case A a when q.a().equals(BigInteger.ONE) -> true; default -> false; }).size(); }",
            "record Q(BigInteger a) { } static long m(S s, JulcList<Q> l) { return l.filter((Q q) -> switch (s) { case A a when q.a().equals(BigInteger.ONE) -> true; default -> false; }).size(); }",
    })
    void aParameterWithItsTypeReadsInAGuard(String members) {
        same(members);
    }

    /** A field of an interface is {@code static final} without saying so, as JavaParser and javac both read it. */
    @ParameterizedTest
    @ValueSource(strings = {
            "sealed interface Kn { boolean ON = true; boolean OFF = false; long N = 3; int M = 2147483647; String TEXT = \"a\"; static final long W = 4; record U() implements Kn { } } static long m(S s) { return switch (s) { case A a when Kn.ON -> 1; default -> 2; }; }",
            "sealed interface Kn { boolean ON = true; boolean OFF = false; long N = 3; int M = 2147483647; String TEXT = \"a\"; static final long W = 4; record U() implements Kn { } } static long m(S s) { return switch (s) { case A a when !Kn.OFF -> 1; default -> 2; }; }",
            "sealed interface Kn { boolean ON = true; boolean OFF = false; long N = 3; int M = 2147483647; String TEXT = \"a\"; static final long W = 4; record U() implements Kn { } } static long m(S s, long x) { return switch (s) { case A a when x > Kn.N -> 1; default -> 2; }; }",
            "sealed interface Kn { boolean ON = true; boolean OFF = false; long N = 3; int M = 2147483647; String TEXT = \"a\"; static final long W = 4; record U() implements Kn { } } static long m(S s, long x) { return switch (s) { case A a when x > Kn.W -> 1; default -> 2; }; }",
            "sealed interface Kn { boolean ON = true; boolean OFF = false; long N = 3; int M = 2147483647; String TEXT = \"a\"; static final long W = 4; record U() implements Kn { } } static long m(S s) { return switch (s) { case A a when (Kn.M + 1) < a.v().signum() -> 1; default -> 2; }; }",
            "sealed interface Kn { boolean ON = true; boolean OFF = false; long N = 3; int M = 2147483647; String TEXT = \"a\"; static final long W = 4; record U() implements Kn { } } static long m(S s) { return switch (s) { case A a when Kn.TEXT == \"a\" -> 1; default -> 2; }; }",
            "sealed interface Kn { boolean ON = true; boolean OFF = false; long N = 3; int M = 2147483647; String TEXT = \"a\"; static final long W = 4; record U() implements Kn { } } static long m(S s, boolean c) { return switch (s) { case A a when Kn.N > 2 && c -> 1; default -> 2; }; }",
            "static boolean Z = true; static long m(S s) { return switch (s) { case A a when Z -> 1; default -> 2; }; }",
    })
    void aConstantOfAnInterface(String members) {
        same(members);
    }

    /** A read of a type's field in a guard that is no constant has no binder on either frontend. */
    @ParameterizedTest
    @ValueSource(strings = {
            "sealed interface Kn { boolean ON = true; boolean OFF = false; long N = 3; int M = 2147483647; String TEXT = \"a\"; static final long W = 4; record U() implements Kn { } } static long m(S s, boolean c) { return switch (s) { case A a when Kn.ON && c -> 1; default -> 2; }; }",
            "sealed interface Kn { boolean ON = true; boolean OFF = false; long N = 3; int M = 2147483647; String TEXT = \"a\"; static final long W = 4; record U() implements Kn { } } static long m(S s, boolean c) { return switch (s) { case A a when Kn.OFF || c -> 1; default -> 2; }; }",
            "sealed interface Kn { BigInteger B = BigInteger.ONE; record U() implements Kn { } } static long m(S s) { return switch (s) { case A a when a.v().equals(Kn.B) -> 1; default -> 2; }; }",
            "record Rc(long n) { static long L = 3; } static long m(S s, long x) { return switch (s) { case A a when x > Rc.L -> 1; default -> 2; }; }",
    })
    void aFieldOfATypeThatIsNoConstantInAGuard(String members) {
        for (var frontend : CompilerFrontend.values()) {
            var e = assertThrows(CompilerException.class, () -> new JulcCompiler(StdlibRegistry.defaultRegistry(),
                    new CompilerOptions().setFrontend(frontend)).compileMethod(source(members), "m"));
            assertTrue(e.getMessage().contains("Undefined variable"), () -> frontend + ": " + e.getMessage());
        }
    }

    @Test
    void aConstantFalseOfAnInterfaceIsJavacsRejection() {
        rejected("sealed interface Kn { boolean ON = true; boolean OFF = false; long N = 3; int M = 2147483647; String TEXT = \"a\"; static final long W = 4; record U() implements Kn { } } static long m(S s) { return switch (s) { case A a when Kn.OFF -> 1; default -> 2; }; }");
    }

    /** An element keeps its sealed type, nested or not, as the reference reads it where the receiver is no simple name. */
    @ParameterizedTest
    @ValueSource(strings = {
            "record F(JulcList<S> ss) { } static boolean m(F f) { return f.ss().any(v -> switch (v) { case A a -> true; default -> false; }); }",
            "record F(JulcList<S> ss) { } static JulcList<BigInteger> m(F f) { return f.ss().map(v -> switch (v) { case A a -> a.v(); default -> BigInteger.ONE; }); }",
            "record F(JulcList<S> ss) { } static boolean m(F f) { return f.ss().any(v -> { var w = v; return switch (w) { case A a -> true; default -> false; }; }); }",
            "static JulcList<S> ids(JulcList<S> s) { return s; } static boolean m(JulcList<S> xs) { return ids(xs).any(v -> switch (v) { case A a -> true; default -> false; }); }",
            "static boolean m(JulcList<S> xs) { return xs.tail().any(v -> switch (v) { case A a -> true; default -> false; }); }",
            "static boolean m(JulcList<S> xs) { return xs.filter(u -> true).any(v -> switch (v) { case A a -> true; default -> false; }); }",
            "static boolean m(JulcList<S> xs) { return (xs).any(v -> switch (v) { case A a -> true; default -> false; }); }",
            "record F(JulcList<S> ss) { } static boolean m(JulcList<BigInteger> xs, F f) { return xs.any(x -> f.ss().any(v -> switch (v) { case A a -> true; default -> false; })); }",
            "record F(JulcList<S> ss) { } static boolean m(JulcList<S> xs, F f) { return f.ss().any(v -> xs.any(w -> switch (v) { case A a -> true; default -> false; })); }",
            "record Tr(JulcList<S> kids) { } static JulcList<Boolean> m(Tr n) { return n.kids().map(k -> switch (k) { case A a -> true; default -> false; }); }",
    })
    void aSwitchOnAnElementAtAnyOtherReceiverLowers(String members) {
        same(members);
    }

    /** The reference reads no local through a {@code new}, so a guard that is constant by one keeps a read at run time, in other bytes. */
    @ParameterizedTest
    @ValueSource(strings = {
            "record X(BigInteger v) { } static X m(S s) { final boolean on = true; return new X(switch (s) { case A a when on -> BigInteger.ONE; default -> BigInteger.TWO; }); }",
            "record X(BigInteger v) { } static X m(S s) { final boolean on = true; return new X(switch (s) { case A a when (on) -> BigInteger.ONE; default -> BigInteger.TWO; }); }",
            "record X(BigInteger v) { } static X m(S s) { final boolean off = false; return new X(switch (s) { case A a when !off -> BigInteger.ONE; default -> BigInteger.TWO; }); }",
            "record X(BigInteger v) { } static X m(S s) { final boolean on = true; return new X(switch (s) { case A a when on && true -> BigInteger.ONE; default -> BigInteger.TWO; }); }",
            "record X(BigInteger v) { } static X m(S s) { final boolean on = true; return new X(switch (s) { case A a when (boolean) on -> BigInteger.ONE; default -> BigInteger.TWO; }); }",
            "record X(BigInteger v) { } static X m(S s) { final var on = true; return new X(switch (s) { case A a when on -> BigInteger.ONE; default -> BigInteger.TWO; }); }",
            "record X(BigInteger v) { } static X m(S s) { final boolean on = true; final boolean p = on; return new X(switch (s) { case A a when p -> BigInteger.ONE; default -> BigInteger.TWO; }); }",
            "record X(BigInteger v) { } static X m(S s, S t) { final boolean on = true; return new X(switch (s) { case A a -> { final boolean p = on; yield switch (t) { case A b when p -> BigInteger.ONE; default -> BigInteger.TWO; }; } default -> BigInteger.ZERO; }); }",
            "record X(BigInteger v) { } static X m(S s, S t) { final boolean on = true; return new X(switch (s) { case A a -> switch (t) { case A b when on -> BigInteger.ONE; default -> BigInteger.TWO; }; default -> BigInteger.ZERO; }); }",
            "record X(BigInteger v) { } static X m(S s) { final boolean on = true; return new X(h(switch (s) { case A a when on -> BigInteger.ONE; default -> BigInteger.TWO; })); }",
    })
    void aConstantGuardThatReadsALocalThroughANewStops(String members) {
        LoweringFixture.assertMethodNotPorted(source(members), "m", "a constant guard that reads a local through a new", 27);
    }

    /** A local on the same side of the {@code new}, no local, no {@code new}, or a guard read at run time on both. */
    @ParameterizedTest
    @ValueSource(strings = {
            "record X(BigInteger v) { } static X m(S s, S t) { return new X(switch (s) { case A a -> { final boolean on = true; yield switch (t) { case A b when on -> BigInteger.ONE; default -> BigInteger.TWO; }; } default -> BigInteger.ZERO; }); }",
            "record X(BigInteger v) { } static X m(S s) { return new X(switch (s) { case A a when ON -> BigInteger.ONE; default -> BigInteger.TWO; }); }",
            "record X(BigInteger v) { } static X m(S s) { final boolean on = true; return switch (s) { case A a when on -> new X(BigInteger.ONE); default -> new X(BigInteger.TWO); }; }",
            "record X(BigInteger v) { } static X m(S s) { final boolean on = true; var v = switch (s) { case A a when on -> BigInteger.ONE; default -> BigInteger.TWO; }; return new X(v); }",
            "record X(BigInteger v) { } static X m(S s, boolean c) { final boolean on = true; return new X(switch (s) { case A a when on && c -> BigInteger.ONE; default -> BigInteger.TWO; }); }",
            "record X(BigInteger v) { } static X m(S s, boolean c) { final boolean on = true; return new X(switch (s) { case A a when c || on -> BigInteger.ONE; default -> BigInteger.TWO; }); }",
            "record X(BigInteger v) { } static X m(S s, boolean c) { final boolean on = true; return new X(switch (s) { case A a when on == c -> BigInteger.ONE; default -> BigInteger.TWO; }); }",
            "record X(BigInteger v) { } static X m(S s) { boolean on = true; return new X(switch (s) { case A a when on -> BigInteger.ONE; default -> BigInteger.TWO; }); }",
            "record X(BigInteger v) { } static X m(S s, boolean c) { return new X(switch (s) { case A a when c -> BigInteger.ONE; default -> BigInteger.TWO; }); }",
    })
    void aGuardReadAtRunTimeOrOnOneSideOfANew(String members) {
        same(members);
    }

    /**
     * An operand of either parent of a variant passes {@code instanceof}'s rule on tags, so what a variant of two interfaces
     * says, the stop at its type, comes next. Read against its first parent alone, the second would be {@code JULC0072}.
     */
    @ParameterizedTest
    @ValueSource(strings = {"Pf", "Pg"})
    void anOperandOfEitherParentOfAVariant(String parent) {
        String members = "sealed interface Pf permits Vb { } sealed interface Pg permits Vb { } record Vb(BigInteger v) implements Pf, Pg { } "
                + "static long m(S s, " + parent + " x) { return switch (s) { case A a when x instanceof Vb -> 1; default -> 3; }; }";
        assertEquals("JULC0084", javacCode(members));
    }

    /** {@code JULC0053} in a colon group, where javac's two declarators stand in no block. */
    /** Outside a guard {@code instanceof} needs a pattern, on both frontends, whatever the operand's type. */
    @ParameterizedTest
    @ValueSource(strings = {
            "static boolean m(JulcList<S> xs) { return xs.any(v -> v instanceof A); }",
            "static long m(JulcList<S> xs) { return xs.filter(v -> v instanceof B).size(); }",
            "static boolean m(JulcList<S> xs) { return xs.any(v -> { var w = v; return w instanceof A; }); }",
    })
    void anInstanceofWithNoPatternHasNoVariant(String members) {
        for (var frontend : CompilerFrontend.values()) {
            var e = assertThrows(CompilerException.class, () -> new JulcCompiler(StdlibRegistry.defaultRegistry(),
                    new CompilerOptions().setFrontend(frontend)).compileMethod(source(members), "m"));
            assertTrue(e.getMessage().contains("Unsupported instanceof pattern"), () -> frontend + ": " + e.getMessage());
        }
    }

    @Test
    void twoDeclaratorsInAGroup() {
        rejected("static long m(S s) { return switch (s) { case A a: long x = 1, y = 2; yield x + y; default: yield 3; }; }");
        rejected("static long m(S s) { return switch (s) { case A a -> { long x = 1, y = 2; yield x + y; } default -> 3; }; }");
    }
}
