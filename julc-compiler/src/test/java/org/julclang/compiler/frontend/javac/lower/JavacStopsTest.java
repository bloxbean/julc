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
 * The shapes the javac frontend stops at {@code JULC0084} because Java and the JavaParser frontend disagree on them, each
 * with what that frontend does behind the default gate. A program that holds one fails closed. This list is the input of
 * M5's decision, so a shape that starts to lower, or a default that changes, fails here.
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
    record Stop(String construct, String members, String onDefault) {
    }

    private static final List<Stop> STOPS = List.of(
            new Stop("a function that is no lambda at the call", "static boolean m(JulcList<BigInteger> xs, BigInteger k) { return xs.any((x -> x.equals(k))); }", "accepted"),
            new Stop("a function that is no lambda at the call", "static boolean m(JulcList<BigInteger> xs, boolean c) { return xs.any(c ? x -> true : x -> false); }", "accepted"),
            new Stop("a function that is no lambda at the call", "static boolean m(JulcList<BigInteger> xs) { return xs.any(java.util.function.Predicate.isEqual(BigInteger.ONE)); }", "uncoded"),
            new Stop("a function that is no lambda at the call", "static JulcList<BigInteger> m(JulcList<BigInteger> xs) { return xs.map(java.util.function.Function.identity()); }", "uncoded"),
            new Stop("a lambda parameter written var", "static boolean m(JulcList<BigInteger> xs) { return xs.any((var x) -> x.signum() > 0); }", "JULC0055"),
            new Stop("a lambda parameter written var", "static JulcList<BigInteger> m(JulcList<BigInteger> xs) { return xs.map((var x) -> x); }", "accepted"),
            new Stop("a lambda parameter of type java.lang.Object", "static boolean m(JulcList xs) { return xs.any(x -> true); }", "accepted"),
            new Stop("LAMBDA_EXPRESSION", "record R(BigInteger a) { java.util.function.Predicate<BigInteger> h() { return x -> true; } } static boolean m(R r) { return true; }", "accepted"),
            new Stop("LAMBDA_EXPRESSION", "static boolean m(boolean c) { java.util.function.Predicate<BigInteger> p = c ? x -> true : x -> false; return c; }", "uncoded"),
            new Stop("a for-each variable of type java.lang.Object", "static long m(JulcList<BigInteger> xs) { long s = 0; for (Object o : xs) { s = s + 1; } return s; }", "accepted"),
            new Stop("a for-each variable of type java.lang.Object", "static long m(JulcList xs) { long s = 0; for (Object o : xs) { s = s + 1; } return s; }", "accepted"),
            new Stop("a for-each variable of type java.lang.Number", "static long m(JulcList<BigInteger> xs) { long s = 0; for (Number o : xs) { s = s + o.longValue(); } return s; }", "accepted"),
            new Stop("a for-each variable of type java.lang.Long", "static long m(long z) { long s = 0; for (Long x : JulcList.of(z)) { s = s + 1; } return s; }", "accepted"),
            new Stop("a for-each over an array", "static long m(byte[] a) { long s = 0; for (byte b : a) { s = s + 1; } return s; }", "accepted"),
            new Stop("a wrapper's component read as Data", "static byte[] m(PubKeyHash p) { return p.hash(); }", "accepted"),
            new Stop("a wrapper's component read as Data", "record R(PubKeyHash p) { } static byte[] m(R r) { return (r.p()).hash(); }", "accepted"),
            new Stop("a wrapper's component read as Data", "@Param static PubKeyHash K; static byte[] m() { return K.hash(); }", "accepted"),
            new Stop("a wrapper's component read as Data", "static byte[] m(TokenName t) { return t.name(); }", "accepted"),
            new Stop("a wrapper's component read as Data", "@NewType record H(byte[] v) { } static byte[] m(H h) { return h.v(); }", "JULC0101"),
            new Stop("fromPlutusData on a ledger hash", "static PubKeyHash m(PlutusData d) { return PubKeyHash.fromPlutusData(d); }", "accepted"),
            new Stop("fromPlutusData on a ledger type", "static boolean m(PlutusData d) { Value v = Value.fromPlutusData(d); return true; }", "accepted"),
            new Stop("fromPlutusData on a ledger type", "static boolean m(PlutusData d) { Address v = Address.fromPlutusData(d); return true; }", "accepted"),
            new Stop("fromPlutusData on a ledger type", "static boolean m(PlutusData d) { Credential c = Credential.fromPlutusData(d); return c.equals(c); }", "accepted"),
            new Stop("fromPlutusData on a ledger type", "static BigInteger m(PlutusData d) { TxOutRef r = TxOutRef.fromPlutusData(d); return r.index(); }", "accepted"),
            new Stop("fromPlutusData on a ledger type", "static BigInteger m(PlutusData d) { return Value.fromPlutusData(d).lovelaceOf(); }", "accepted"),
            new Stop("an argument of lengthOfByteString that is Data", "static long m(byte[] a) { PlutusData.BytesData b = Builtins.bData(a); return Builtins.lengthOfByteString(b); }", "accepted"),
            new Stop("an argument of appendByteString that is Data", "static PlutusData m(byte[] a) { PlutusData.BytesData b = Builtins.bData(a); return Builtins.appendByteString(b, b); }", "accepted"),
            new Stop("an argument of equalsByteString that is Data", "static boolean m(byte[] a) { PlutusData.BytesData b = Builtins.bData(a); return Builtins.equalsByteString(a, b); }", "accepted"),
            new Stop("an argument of indexByteString that is Data", "static long m(byte[] a) { PlutusData.BytesData b = Builtins.bData(a); return Builtins.indexByteString(b, 0); }", "accepted"),
            new Stop("an argument of bData that is Data", "static PlutusData m(byte[] a) { PlutusData.BytesData b = Builtins.bData(a); return Builtins.bData(b); }", "accepted"),
            new Stop("a component of a ledger type whose Data is no constructor", "static long m(Value v) { return v.inner().size(); }", "accepted"),
            new Stop("the method toString of a byte string", "static String m(PubKeyHash p) { return p.toString(); }", "JULC0101"),
            new Stop("the method hashCode of a byte string", "static int m(PubKeyHash p) { return p.hashCode(); }", "JULC0101"),
            new Stop("a wrapper's component named like a method of its value", "@NewType record A(BigInteger abs) { } record R(A a) { } static BigInteger m(R r) { return r.a().abs(); }", "JULC0101"),
            new Stop("a wrapper's component named like a method of its value", "@NewType record A(byte[] length) { } record R(A a) { } static byte[] m(R r) { return r.a().length(); }", "JULC0101"),
            new Stop("the negation of a type's minimum value", "static int m() { return -0x80000000; }", "accepted"),
            new Stop("the negation of a type's minimum value", "static int m() { return -(-2147483648); }", "accepted"),
            new Stop("the negation of a type's minimum value", "static long m() { return -0x8000000000000000L; }", "accepted"),
            new Stop("an equals between unrelated types", "static boolean m(PubKeyHash a, BigInteger b) { return a.equals(b); }", "accepted"),
            new Stop("an equals between unrelated types", "static boolean m(BigInteger a, PlutusData b) { return a.equals(b); }", "accepted"),
            new Stop("a static call not qualified by a simple class name", "static BigInteger m() { return java.math.BigInteger.valueOf(1); }", "uncoded"),
            new Stop("a method with no stdlib lowering", "static long m(long a) { return Math.absExact(a); }", "uncoded"),
            new Stop("a text block", "static String m() { return \"\"\"\n  a\"\"\"; }", "uncoded"),
            new Stop("an expression of type java.lang.Object",
                    "sealed interface S permits A, B { } record A() implements S { } record B() implements S { } static boolean m(S s) { return s.equals(switch (s) { case A a -> a; default -> s; }); }", "accepted"),
            new Stop("UNARY_PLUS", "sealed interface S permits A, B { } record A() implements S { } record B() implements S { } static long m(S s, long n) { return switch (s) { case A a when +n > 0 -> 1; default -> 3; }; }", "uncoded"),
            new Stop("a case of a variant of two sealed interfaces",
                    "sealed interface P1 permits R { } sealed interface P2 permits R { } record R() implements P1, P2 { } static long m(P1 p) { return switch (p) { case R r -> 1; }; }", "accepted"),
            new Stop("a case of a variant that is not a plain record",
                    "sealed interface O permits W { } static final class W implements O { } static long m(O o) { return switch (o) { case W v -> 1; }; }", "accepted"),
            new Stop("a case of a variant that is not a plain record",
                    "sealed interface N permits W { } @NewType record W(BigInteger value) implements N { } static long m(N n) { return switch (n) { case W v -> 1; }; }", "accepted"),
            new Stop("an instanceof of a variant of two sealed interfaces",
                    "sealed interface P1 permits R { } sealed interface P2 permits R { } record R() implements P1, P2 { } static boolean m(P1 p) { return p instanceof R r; }", "accepted"),
            new Stop("an instanceof of a variant that is not a plain record",
                    "sealed interface O permits W { } static final class W implements O { } static boolean m(O o) { return o instanceof W v; }", "accepted"),
            new Stop("an instanceof of a variant that is not a plain record",
                    "sealed interface N permits W { } @NewType record W(BigInteger value) implements N { } static boolean m(N n) { return n instanceof W v; }", "accepted"),
            new Stop("a new of a ledger type whose Data is no constructor", "static Value m() { return new Value(JulcMap.empty()); }", "accepted"),
            new Stop("a new of a variant of two sealed interfaces",
                    "sealed interface U { record I(BigInteger a) implements U, W { } } sealed interface W permits U.I { } static U m(BigInteger a) { return new U.I(a); }", "accepted"),
            new Stop("a new of a variant that is not a record",
                    "sealed interface W permits K { } static final class K implements W { } static W m() { return new K(); }", "accepted"),
            new Stop("a new of a @NewType that is a variant",
                    "sealed interface W permits V { } @NewType record V(BigInteger value) implements W { } static W m(BigInteger a) { return new V(a); }", "accepted"),
            new Stop("a new with a class body", "static boolean m() { var o = new BigInteger(\"5\") { }; return true; }", "JULC0100"),
            new Stop("a new BigInteger of a string that is no integer", "static BigInteger m() { return new BigInteger(\"abc\"); }", "NumberFormatException"),
            new Stop("a Data component given a native list",
                    "record R(PlutusData d) { } static boolean m(PlutusData l) { var r = new R(Builtins.tailList(Builtins.unListData(l))); return true; }", "accepted"),
            new Stop("a Data component given a native list",
                    "sealed interface W { record I(PlutusData d) implements W { } } static W m(PlutusData l) { return new W.I(Builtins.unListData(l)); }", "accepted"),
            new Stop("a Data component given a native list",
                    "static boolean m(PlutusData l) { var t = new Tuple2<PlutusData, BigInteger>(Builtins.unListData(l), BigInteger.ONE); return true; }", "accepted"),
            new Stop("a Data component given a native list",
                    "record R(PlutusData d) { } static boolean m(PlutusData v) { var r = new R(Builtins.unMapData(v)); return true; }", "accepted"),
            new Stop("a Data component given a native list", "record R(PlutusData d) { } static R m(PlutusData z) { return new R(Builtins.constrFields(z)); }", "accepted"),
            new Stop("a Data component given a native list", "record R(PlutusData d) { } static R m(PlutusData z) { return new R(Builtins.unConstrData(z)); }", "accepted"),
            new Stop("a Data component given a native list", "record R(PlutusData d) { } static R m(PlutusData z) { return new R(Builtins.mkPairData(z, z)); }", "accepted"),
            new Stop("a Data component given a native list", "record R(PlutusData.ListData d) { } static R m(PlutusData z) { return new R(Builtins.constrFields(z)); }", "accepted"),
            new Stop("an expression of type java.lang.Record&C.S", "sealed interface S { record A(BigInteger v) implements S { } record B() implements S { } } static boolean m(boolean c, BigInteger a) { var t = new Tuple2<>(c ? new S.A(a) : new S.B(), a); return true; }", "accepted"),
            new Stop("an instanceof of an operand outside the variant's sealed interface",
                    "sealed interface S { record A() implements S { } record B() implements S { } } static boolean m(boolean c, S.A a, S.B b) { return (c ? a : b) instanceof S.B x; }", "JULC0100"),
            new Stop("TYPE_CAST", "static boolean m(PlutusData v, PlutusData p) { return ((JulcMap)(Object) v).containsKey(p); }", "accepted"),
            new Stop("a library unit", "static boolean m(PlutusData r) { return org.julclang.stdlib.lib.MapLib.member(PlutusData.cast(r, JulcMap.class), Builtins.iData(2)); }", "uncoded"),
            new Stop("an expression of type java.lang.Object",
                    "sealed interface S { record A() implements S { } } static boolean m(boolean c, S a, S.A b) { return a.equals(c ? a : b); }", "accepted"),
            new Stop("IDENTIFIER", "static final boolean J = false; static boolean m() { boolean J = (true || J); return J; }", "accepted"),
            new Stop("a wrapper's component read as Data", "static byte[] m(byte[] a) { return new PubKeyHash(a).hash(); }", "accepted"),
            new Stop("MEMBER_SELECT", "static boolean m() { var c = String.class; return true; }", "uncoded"));

    static Stream<Stop> stops() {
        return STOPS.stream();
    }

    private static String source(String members) {
        return IMPORTS + "class C {\n" + members + "\n}\n";
    }

    @ParameterizedTest
    @MethodSource("stops")
    void aStopFailsClosed(Stop stop) {
        var failure = assertThrows(CompilerException.class, () -> new JulcCompiler(stdlib(), new CompilerOptions().setFrontend(CompilerFrontend.JAVAC))
                .compileMethod(source(stop.members()), "m", List.of()));
        assertEquals(stop.construct(), assertInstanceOf(NotPorted.class, failure, failure::getMessage).construct());
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
            new JulcCompiler(stdlib(), new CompilerOptions()).compileMethod(source(members), "m", List.of());
            return "accepted";
        } catch (CompilerException e) {
            var code = e.diagnostics().isEmpty() ? null : e.diagnostics().getFirst().code();
            return code == null ? "uncoded" : code;
        } catch (NumberFormatException e) {
            return "NumberFormatException";
        }
    }
}
