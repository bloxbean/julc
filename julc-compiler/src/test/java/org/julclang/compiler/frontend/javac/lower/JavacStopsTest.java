package org.julclang.compiler.frontend.javac.lower;

import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerFrontend;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.frontend.javac.NotPorted;
import org.julclang.compiler.pir.StdlibLookup;
import org.julclang.stdlib.StdlibRegistry;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The shapes the javac frontend stopped at {@code JULC0084} until ADR-068 M5, because Java and the JavaParser frontend disagree
 * on them, each with what this frontend does now, {@code lowers} or a code, and what that frontend does behind the default
 * gate. A shape that lowers is held to the JVM's value by {@code Adr068M5bHarnessTest}; a rejection fails closed.
 */
class JavacStopsTest {

    private static final String IMPORTS = """
            import java.math.BigInteger;
            import org.julclang.core.PlutusData;
            import org.julclang.core.types.JulcList;
            import org.julclang.core.types.JulcMap;
            import org.julclang.core.types.Tuple2;
            import org.julclang.ledger.Address;
            import org.julclang.ledger.Credential;
            import org.julclang.ledger.PubKeyHash;
            import org.julclang.ledger.TokenName;
            import org.julclang.ledger.TxOutRef;
            import org.julclang.ledger.Value;
            import org.julclang.stdlib.Builtins;
            import org.julclang.stdlib.annotation.NewType;
            import org.julclang.stdlib.annotation.Param;
            """;

    /** @param onDefault the default frontend's code, {@code accepted}, or {@code uncoded} for a rejection with no code */
    record Stop(String onJavac, String members, String onDefault) {
    }

    private static final List<Stop> STOPS = List.of(
            new Stop("lowers", "static boolean m(JulcList<BigInteger> xs, BigInteger k) { return xs.any((x -> x.equals(k))); }", "accepted"),
            new Stop("lowers", "static boolean m(JulcList<BigInteger> xs, boolean c) { return xs.any(c ? x -> true : x -> false); }", "accepted"),
            new Stop("JULC0125", "static boolean m(JulcList<BigInteger> xs) { return xs.any(java.util.function.Predicate.isEqual(BigInteger.ONE)); }", "uncoded"),
            new Stop("JULC0125", "static JulcList<BigInteger> m(JulcList<BigInteger> xs) { return xs.map(java.util.function.Function.identity()); }", "uncoded"),
            new Stop("lowers", "static boolean m(JulcList<BigInteger> xs) { return xs.any((var x) -> x.signum() > 0); }", "JULC0055"),
            new Stop("lowers", "static JulcList<BigInteger> m(JulcList<BigInteger> xs) { return xs.map((var x) -> x); }", "accepted"),
            new Stop("lowers", "static boolean m(JulcList xs) { return xs.any(x -> true); }", "accepted"),
            new Stop("JULC0022", "static boolean m(boolean c) { java.util.function.Predicate<BigInteger> p = c ? x -> true : x -> false; return c; }", "uncoded"),
            new Stop("lowers", "static long m(JulcList<BigInteger> xs) { long s = 0; for (Object o : xs) { s = s + 1; } return s; }", "accepted"),
            new Stop("lowers", "static long m(JulcList xs) { long s = 0; for (Object o : xs) { s = s + 1; } return s; }", "accepted"),
            new Stop("JULC0055", "static long m(JulcList<BigInteger> xs) { long s = 0; for (Number o : xs) { s = s + o.longValue(); } return s; }", "accepted"),
            new Stop("lowers", "static long m(long z) { long s = 0; for (Long x : JulcList.of(z)) { s = s + 1; } return s; }", "accepted"),
            new Stop("JULC0128", "record R(PlutusData d) { } static R m(PlutusData z) { PlutusData c = Builtins.unListData(z); long i = 0; while (i < 1) { c = Builtins.tailList(c); i = i + 1; } return new R(c); }", "accepted"),
            new Stop("JULC0014", "static long m(byte[] a) { long s = 0; for (byte b : a) { s = s + 1; } return s; }", "accepted"),
            new Stop("lowers", "static byte[] m(PubKeyHash p) { return p.hash(); }", "accepted"),
            new Stop("lowers", "record R(PubKeyHash p) { } static byte[] m(R r) { return (r.p()).hash(); }", "accepted"),
            new Stop("lowers", "@Param static PubKeyHash K; static byte[] m() { return K.hash(); }", "accepted"),
            new Stop("lowers", "static byte[] m(TokenName t) { return t.name(); }", "accepted"),
            new Stop("lowers", "@NewType record H(byte[] v) { } static byte[] m(H h) { return h.v(); }", "JULC0101"),
            new Stop("JULC0128", "static boolean m(PlutusData d) { Value v = Value.fromPlutusData(d); return true; }", "accepted"),
            new Stop("JULC0128", "static long m(byte[] a) { PlutusData.BytesData b = Builtins.bData(a); return Builtins.lengthOfByteString(b); }", "accepted"),
            new Stop("JULC0128", "static PlutusData m(byte[] a) { PlutusData.BytesData b = Builtins.bData(a); return Builtins.appendByteString(b, b); }", "accepted"),
            new Stop("JULC0128", "static boolean m(byte[] a) { PlutusData.BytesData b = Builtins.bData(a); return Builtins.equalsByteString(a, b); }", "accepted"),
            new Stop("JULC0128", "static long m(byte[] a) { PlutusData.BytesData b = Builtins.bData(a); return Builtins.indexByteString(b, 0); }", "accepted"),
            new Stop("JULC0128", "static PlutusData m(byte[] a) { PlutusData.BytesData b = Builtins.bData(a); return Builtins.bData(b); }", "accepted"),
            new Stop("JULC0128", "static long m(Value v) { return v.inner().size(); }", "accepted"),
            new Stop("JULC0055", "static String m(PubKeyHash p) { return p.toString(); }", "JULC0101"),
            new Stop("JULC0055", "static int m(PubKeyHash p) { return p.hashCode(); }", "JULC0101"),
            new Stop("JULC0055", "@NewType record A(BigInteger abs) { } record R(A a) { } static BigInteger m(R r) { return r.a().abs(); }", "JULC0101"),
            new Stop("lowers", "@NewType record A(byte[] length) { } record R(A a) { } static byte[] m(R r) { return r.a().length(); }", "JULC0101"),
            new Stop("lowers", "static int m() { return -0x80000000; }", "accepted"),
            new Stop("lowers", "static int m() { return -(-2147483648); }", "accepted"),
            new Stop("lowers", "static long m() { return -0x8000000000000000L; }", "accepted"),
            new Stop("JULC0024", "static long m(long a) { return Math.absExact(a); }", "uncoded"),
            new Stop("JULC0125", "static String m() { return \"\"\"\n  a\"\"\"; }", "uncoded"),
            new Stop("JULC0127",
                    "sealed interface O permits W { } static final class W implements O { } static long m(O o) { return switch (o) { case W v -> 1; }; }", "accepted"),
            new Stop("JULC0127",
                    "sealed interface N permits W { } @NewType record W(BigInteger value) implements N { } static long m(N n) { return switch (n) { case W v -> 1; }; }", "accepted"),
            new Stop("JULC0127",
                    "sealed interface O permits W { } static final class W implements O { } static boolean m(O o) { return o instanceof W v; }", "accepted"),
            new Stop("JULC0127",
                    "sealed interface N permits W { } @NewType record W(BigInteger value) implements N { } static boolean m(N n) { return n instanceof W v; }", "accepted"),
            new Stop("JULC0128", "static Value m() { return new Value(JulcMap.empty()); }", "accepted"),
            new Stop("JULC0127",
                    "sealed interface W permits K { } static final class K implements W { } static W m() { return new K(); }", "accepted"),
            new Stop("JULC0127",
                    "sealed interface W permits V { } @NewType record V(BigInteger value) implements W { } static W m(BigInteger a) { return new V(a); }", "accepted"),
            new Stop("JULC0109", "static boolean m() { var o = new BigInteger(\"5\") { }; return true; }", "JULC0100"),
            new Stop("JULC0070", "static BigInteger m() { return new BigInteger(\"abc\"); }", "NumberFormatException"),
            new Stop("JULC0128",
                    "record R(PlutusData d) { } static boolean m(PlutusData l) { var r = new R(Builtins.tailList(Builtins.unListData(l))); return true; }", "accepted"),
            new Stop("JULC0128",
                    "sealed interface W { record I(PlutusData d) implements W { } } static W m(PlutusData l) { return new W.I(Builtins.unListData(l)); }", "accepted"),
            new Stop("JULC0128",
                    "static boolean m(PlutusData l) { var t = new Tuple2<PlutusData, BigInteger>(Builtins.unListData(l), BigInteger.ONE); return true; }", "accepted"),
            new Stop("JULC0128",
                    "record R(PlutusData d) { } static boolean m(PlutusData v) { var r = new R(Builtins.unMapData(v)); return true; }", "accepted"),
            new Stop("JULC0128", "record R(PlutusData d) { } static R m(PlutusData z) { return new R(Builtins.constrFields(z)); }", "accepted"),
            new Stop("JULC0128", "record R(PlutusData d) { } static R m(PlutusData z) { return new R(Builtins.unConstrData(z)); }", "accepted"),
            new Stop("JULC0128", "record R(PlutusData d) { } static R m(PlutusData z) { return new R(Builtins.mkPairData(z, z)); }", "accepted"),
            new Stop("JULC0128", "record R(PlutusData.ListData d) { } static R m(PlutusData z) { return new R(Builtins.constrFields(z)); }", "accepted"),
            new Stop("lowers",
                    "sealed interface S { record A() implements S { } record B() implements S { } } static boolean m(boolean c, S.A a, S.B b) { return (c ? a : b) instanceof S.B x; }", "JULC0100"),
            new Stop("JULC0124", "static long m(long a) { return (short) a; }", "accepted"),
            new Stop("lowers", "static final boolean J = false; static boolean m() { boolean J = (true || J); return J; }", "accepted"),
            new Stop("lowers", "static byte[] m(byte[] a) { return new PubKeyHash(a).hash(); }", "accepted"),
            new Stop("JULC0125", "static boolean m() { var c = String.class; return true; }", "uncoded"));

    static Stream<Stop> stops() {
        return STOPS.stream();
    }

    private static String source(String members) {
        return IMPORTS + "class C {\n" + members + "\n}\n";
    }

    @ParameterizedTest
    @MethodSource("stops")
    void eachHasItsAnswer(Stop stop) {
        assertEquals(stop.onJavac(), onJavac(stop.members()), stop.members());
    }

    /** {@code lowers}, a rejection's code, or {@code JULC0084} and the construct: the last no longer happens (ADR-068 M5). */
    private static String onJavac(String members) {
        try {
            new JulcCompiler(stdlib(), new CompilerOptions().setFrontend(CompilerFrontend.JAVAC)).compileMethod(source(members), "m", List.of());
            return "lowers";
        } catch (NotPorted e) {
            return "JULC0084 " + e.construct();
        } catch (CompilerException e) {
            var code = e.diagnostics().isEmpty() ? null : e.diagnostics().getFirst().code();
            return code == null ? "uncoded" : code;
        }
    }

    @ParameterizedTest
    @MethodSource("stops")
    void theDefaultFrontendsAnswerIsKnown(Stop stop) {
        assertEquals(stop.onDefault(), onDefault(stop.members()), stop.members());
    }

    /** A program compiles with the stdlib, so a stop is the shape's and not a missing lookup's. */
    private static StdlibLookup stdlib() {
        return StdlibRegistry.defaultRegistry();
    }

    private static String onDefault(String members) {
        try {
            new JulcCompiler(stdlib(), new CompilerOptions().setFrontend(CompilerFrontend.JAVAPARSER)).compileMethod(source(members), "m", List.of());
            return "accepted";
        } catch (CompilerException e) {
            var code = e.diagnostics().isEmpty() ? null : e.diagnostics().getFirst().code();
            return code == null ? "uncoded" : code;
        } catch (NumberFormatException e) {
            return "NumberFormatException";
        }
    }
}
