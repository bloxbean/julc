package org.julclang.compiler.frontend.javac.lower;

import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerFrontend;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.frontend.javac.NotPorted;
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
            import org.julclang.ledger.PubKeyHash;
            import org.julclang.ledger.TokenName;
            import org.julclang.stdlib.annotation.NewType;
            import org.julclang.stdlib.annotation.Param;
            """;

    /** @param onDefault the default frontend's code, {@code accepted}, or {@code uncoded} for a rejection with no code */
    record Stop(String construct, String members, String onDefault) {
    }

    private static final List<Stop> STOPS = List.of(
            new Stop("a wrapper's component read as Data", "static byte[] m(PubKeyHash p) { return p.hash(); }", "accepted"),
            new Stop("a wrapper's component read as Data", "record R(PubKeyHash p) { } static byte[] m(R r) { return (r.p()).hash(); }", "accepted"),
            new Stop("a wrapper's component read as Data", "@Param static PubKeyHash K; static byte[] m() { return K.hash(); }", "accepted"),
            new Stop("a wrapper's component read as Data", "static byte[] m(TokenName t) { return t.name(); }", "accepted"),
            new Stop("a wrapper's component read as Data", "@NewType record H(byte[] v) { } static byte[] m(H h) { return h.v(); }", "JULC0101"),
            new Stop("fromPlutusData on a ledger hash", "static PubKeyHash m(PlutusData d) { return PubKeyHash.fromPlutusData(d); }", "accepted"),
            new Stop("the method toString of a byte string", "static String m(PubKeyHash p) { return p.toString(); }", "JULC0101"),
            new Stop("the method hashCode of a byte string", "static int m(PubKeyHash p) { return p.hashCode(); }", "JULC0101"),
            new Stop("a wrapper's component named like a method of its value", "@NewType record A(BigInteger abs) { } record R(A a) { } static BigInteger m(R r) { return r.a().abs(); }", "JULC0101"),
            new Stop("a wrapper's component named like a method of its value", "@NewType record A(byte[] length) { } record R(A a) { } static byte[] m(R r) { return r.a().length(); }", "JULC0101"),
            new Stop("a declared accessor", "record R(BigInteger a) { public BigInteger a() { return a; } } static BigInteger m(R r) { return r.a(); }", "JULC0101"),
            new Stop("a declared toPlutusData", "record R(PlutusData d) { PlutusData toPlutusData() { return d; } } static PlutusData m(R r) { return r.toPlutusData(); }", "JULC0101"),
            new Stop("the negation of a type's minimum value", "static int m() { return -0x80000000; }", "accepted"),
            new Stop("the negation of a type's minimum value", "static int m() { return -(-2147483648); }", "accepted"),
            new Stop("the negation of a type's minimum value", "static long m() { return -0x8000000000000000L; }", "accepted"),
            new Stop("an equals between unrelated types", "static boolean m(PubKeyHash a, BigInteger b) { return a.equals(b); }", "accepted"),
            new Stop("an equals between unrelated types", "static boolean m(BigInteger a, PlutusData b) { return a.equals(b); }", "accepted"),
            new Stop("a static call not qualified by a simple class name", "static BigInteger m() { return java.math.BigInteger.valueOf(1); }", "uncoded"),
            new Stop("a method with no stdlib lowering", "static long m(long a) { return Math.floorMod(a, 2L); }", "uncoded"),
            new Stop("a text block", "static String m() { return \"\"\"\n  a\"\"\"; }", "uncoded"));

    static Stream<Stop> stops() {
        return STOPS.stream();
    }

    private static String source(String members) {
        return IMPORTS + "class C {\n" + members + "\n}\n";
    }

    @ParameterizedTest
    @MethodSource("stops")
    void aStopFailsClosed(Stop stop) {
        var failure = assertThrows(CompilerException.class, () -> new JulcCompiler(null, new CompilerOptions().setFrontend(CompilerFrontend.JAVAC))
                .compileMethod(source(stop.members()), "m", List.of()));
        assertEquals(stop.construct(), assertInstanceOf(NotPorted.class, failure, failure::getMessage).construct());
    }

    @ParameterizedTest
    @MethodSource("stops")
    void theDefaultFrontendsAnswerIsKnown(Stop stop) {
        assertEquals(stop.onDefault(), onDefault(stop.members()), stop.members());
    }

    private static String onDefault(String members) {
        try {
            new JulcCompiler().compileMethod(source(members), "m", List.of());
            return "accepted";
        } catch (CompilerException e) {
            var code = e.diagnostics().isEmpty() ? null : e.diagnostics().getFirst().code();
            return code == null ? "uncoded" : code;
        }
    }
}
