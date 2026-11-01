package org.julclang.compiler.frontend.javac.lower;

import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerFrontend;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.error.CompilerDiagnostic;
import org.julclang.compiler.frontend.javac.NotPorted;
import org.julclang.core.flat.UplcFlatEncoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-068 M3b: what a call binds to, and what it refuses to bind to. The bytes of what compiles are compared with the
 * reference's, at every level. The rest are programs that must not lower as the reference lowers them, or that the
 * reference rejects: a name binds only to what javac bound (I12), a shape the reference rejects stays rejected (I2).
 * Stdlib calls need the registry, which depends on this module: {@code Adr068CallsHarnessTest} has those.
 */
class JavacCallsTest {

    private static final String IMPORTS = """
            import java.math.BigInteger;
            import org.julclang.core.PlutusData;
            import org.julclang.core.types.JulcList;
            import org.julclang.core.types.JulcMap;
            import org.julclang.ledger.PubKeyHash;
            import org.julclang.ledger.TokenName;
            import org.julclang.stdlib.annotation.NewType;
            """;

    private static String source(String members) {
        return IMPORTS + "class C {\n" + members + "\n}\n";
    }

    private static CompilerException javac(String members) {
        return assertThrows(CompilerException.class, () -> new JulcCompiler(null, new CompilerOptions().setFrontend(CompilerFrontend.JAVAC))
                .compileMethod(source(members), "m", List.of()));
    }

    private static CompilerDiagnostic rejected(String members) {
        var failure = javac(members);
        assertEquals(1, failure.diagnostics().size(), failure::getMessage);
        return failure.diagnostics().getFirst();
    }

    private static String notPorted(String members) {
        return assertInstanceOf(NotPorted.class, javac(members)).construct();
    }

    private static void assertSame(String members) {
        LoweringFixture.assertSameMethodAsReference(source(members), "m");
    }

    // --- Instance dispatch, by identity ---

    @ParameterizedTest
    @ValueSource(strings = {"a.add(b)", "a.subtract(b)", "a.multiply(b)", "a.divide(b)", "a.remainder(b)", "a.mod(b)", "a.max(b)",
            "a.min(b)", "a.abs()", "a.negate()", "a.add(b).abs()", "(a).add(b)", "a.add((b))"})
    void integerMethods(String body) {
        assertSame("static BigInteger m(BigInteger a, BigInteger b) { return " + body + "; }");
    }

    @ParameterizedTest
    @ValueSource(strings = {"a.signum()", "a.compareTo(b)", "a.intValue()"})
    void integerMethodsToInt(String body) {
        assertSame("static int m(BigInteger a, BigInteger b) { return " + body + "; }");
    }

    @ParameterizedTest
    @ValueSource(strings = {"l.head()", "l.get(0)", "l.tail().head()", "l.reverse().head()", "l.prepend(l.head()).head()",
            "l.take(1).drop(1).head()", "l.concat(l).head()"})
    void listMethods(String body) {
        assertSame("static BigInteger m(JulcList<BigInteger> l) { return " + body + "; }");
    }

    @Test
    void aUserValueDoesNotReachTheLedgersHandlers() {
        var failure = rejected("record Value(BigInteger inner) { BigInteger lovelaceOf() { return inner; } }\n"
                + "static BigInteger m(Value v) { return v.lovelaceOf(); }");
        assertEquals("JULC0055", failure.code());
    }

    @Test
    void aUserBigIntegerIsNotTheIntegerType() {
        var failure = rejected("static class BigInteger { static long valueOf(long n) { return n; } }\n"
                + "static long m() { return BigInteger.valueOf(1); }");
        assertEquals("Undefined variable: BigInteger", failure.message());
    }

    /** {@code Shadow} in D4: a variable next to a class of its name is a variable, on javac as in Java. */
    @Test
    void aVariableNamedLikeAClassIsTheVariable() {
        var options = new CompilerOptions().setFrontend(CompilerFrontend.JAVAC);
        var shadowing = new JulcCompiler(null, options).compileMethod(source(
                "static class Shadow { static boolean compareTo(BigInteger x) { return true; } }\n"
                        + "static boolean m(BigInteger Shadow, BigInteger y) { return Shadow.compareTo(y) > 0; }"), "m", List.of());
        var plain = new JulcCompiler(null, options).compileMethod(source(
                "static boolean m(BigInteger x, BigInteger y) { return x.compareTo(y) > 0; }"), "m", List.of());
        assertArrayEquals(UplcFlatEncoder.encodeProgram(plain.program()), UplcFlatEncoder.encodeProgram(shadowing.program()));
    }

    @Test
    void anObjectMethodHasNoLowering() {
        assertEquals("JULC0055", rejected("static int m(BigInteger a) { return a.hashCode(); }").code());
        assertEquals("JULC0055", rejected("static String m(BigInteger a) { return a.toString(); }").code());
        assertEquals("JULC0055", rejected("static BigInteger m(BigInteger a) { return a.pow(2); }").code());
        assertEquals("JULC0055", rejected("record R(BigInteger a) { BigInteger twice() { return a; } }\n"
                + "static BigInteger m(R r) { return r.twice(); }").code());
    }

    // --- equals ---

    @Test
    void equalsReadsTheReceiversTypeNotTheDeclaringClass() {
        assertSame("static boolean m(BigInteger a, BigInteger b) { return a.equals(b); }");
        assertSame("static boolean m(String a, String b) { return a.equals(b); }");
        assertSame("static boolean m(PlutusData a, PlutusData b) { return a.equals(b); }");
        assertSame("record R(BigInteger x) { } static boolean m(R a, R b) { return a.equals(b); }");
        assertSame("sealed interface S { record A(BigInteger x) implements S { } } static boolean m(S a, S b) { return a.equals(b); }");
    }

    @Test
    void equalsBetweenUnrelatedTypesDoesNotLower() {
        assertEquals("an equals between unrelated types", notPorted("static boolean m(BigInteger a, PlutusData b) { return a.equals(b); }"));
        assertEquals("an equals between unrelated types", notPorted("static boolean m(BigInteger a, String b) { return a.equals(b); }"));
        assertEquals("an equals between unrelated types", notPorted("static boolean m(BigInteger a) { return a.equals(1); }"));
        assertEquals("an equals between unrelated types", notPorted("record A(BigInteger x) { } record B(BigInteger x) { }\n"
                + "static boolean m(A a, B b) { return a.equals(b); }"));
    }

    @Test
    void equalsWithNoLoweringOnTheReceiversType() {
        assertEquals("JULC0055", rejected("static boolean m(JulcList<BigInteger> a, JulcList<BigInteger> b) { return a.equals(b); }").code());
        assertEquals("JULC0055", rejected("static boolean m(Boolean a, Boolean b) { return a.equals(b); }").code());
    }

    @Test
    void anEqualsOverloadIsNotEquals() {
        assertEquals("JULC0055", rejected("record R(BigInteger x) { boolean equals(BigInteger y) { return true; } }\n"
                + "static boolean m(R r, BigInteger y) { return r.equals(y); }").code());
    }

    // --- Accessors ---

    @Test
    void anAccessorReadsItsField() {
        assertSame("record R(BigInteger a, byte[] b) { } static BigInteger m(R r) { return r.a(); }");
        assertSame("record R(BigInteger a, byte[] b) { } static byte[] m(R r) { return r.b(); }");
        assertSame("record I(BigInteger x) { } record R(I i) { } static BigInteger m(R r) { return r.i().x(); }");
        assertSame("record R(JulcList<BigInteger> l) { } static BigInteger m(R r) { return r.l().head(); }");
        assertSame("record R(BigInteger a) { } static final BigInteger K = BigInteger.ONE; static BigInteger m(R r) { return r.a().add(K); }");
    }

    @Test
    void aDeclaredAccessorIsARecordMember() {
        assertEquals("a declared accessor", notPorted(
                "record R(BigInteger a) { public BigInteger a() { return BigInteger.ONE; } } static BigInteger m(R r) { return r.a(); }"));
    }

    @Test
    void aDeclaredToPlutusDataIsARecordMember() {
        assertEquals("a declared toPlutusData", notPorted(
                "record R(BigInteger a) { PlutusData toPlutusData() { return PlutusData.UNIT; } } static PlutusData m(R r) { return r.toPlutusData(); }"));
    }

    @Test
    void aMemberOfAnUntypedReceiverStaysRejected() {
        assertEquals("JULC0055", rejected("record R(BigInteger a) { } static R r(R x) { return x; }\n"
                + "static BigInteger m(R x) { return r(x).a(); }").code());
        assertEquals("JULC0055", rejected("record R(BigInteger a) { } static BigInteger m(R x) { return (x).a(); }").code());
        assertEquals("JULC0055", rejected("record R(BigInteger a) { }\n"
                + "static BigInteger m(boolean c, R x, R y) { return (c ? x : y).a(); }").code());
    }

    @Test
    void aNewTypeComponentNamedLikeAMethodOfItsValueDoesNotLower() {
        assertEquals("a wrapper's component named like a method of its value", notPorted(
                "@NewType record A(BigInteger abs) { } record R(A a) { } static BigInteger m(R r) { return r.a().abs(); }"));
        assertEquals("a wrapper's component named like a method of its value", notPorted(
                "@NewType record A(byte[] length) { } record R(A a) { } static byte[] m(R r) { return r.a().length(); }"));
    }

    @Test
    void aNewTypeValueIsItsComponent() {
        assertSame("@NewType record A(BigInteger value) { } record R(A a) { } static BigInteger m(R r) { return r.a().value(); }");
        assertSame("@NewType record A(byte[] value) { } record R(A a) { } static byte[] m(R r) { return r.a().value(); }");
        assertSame("@NewType record H(byte[] v) { } record R(H h) { } static byte[] m(R r) { return r.h().v(); }");
        assertEquals("JULC0055", rejected("@NewType record A(BigInteger amount) { } record R(A a) { }\n"
                + "static BigInteger m(R r) { return r.a().amount(); }").code());
    }

    /** A hash in a variable is bytes here, and the reference reads it as Data: it fails on-chain where Java does not. */
    @ParameterizedTest
    @ValueSource(strings = {
            "static byte[] m(PubKeyHash p) { return p.hash(); }",
            "record R(PubKeyHash p) { } static byte[] m(R r) { return (r.p()).hash(); }",
            "static PubKeyHash h(PubKeyHash p) { return p; } static byte[] m(PubKeyHash p) { return h(p).hash(); }",
            "static byte[] m(TokenName t) { return t.name(); }",
            "@NewType record H(byte[] v) { } static byte[] m(H h) { return h.v(); }"})
    void aWrapperReadAsDataDoesNotLower(String members) {
        assertEquals("a wrapper's component read as Data", notPorted(members));
    }

    @Test
    void fromPlutusDataOnAHashLeavesDataWhereBytesAreExpected() {
        assertEquals("fromPlutusData on a ledger hash", notPorted("static PubKeyHash m(PlutusData d) { return PubKeyHash.fromPlutusData(d); }"));
    }

    @Test
    void aHashReadFromATypedCallIsDecoded() {
        assertSame("record R(PubKeyHash p) { } static byte[] m(R r) { return r.p().hash(); }");
        assertSame("record R(JulcList<PubKeyHash> l) { } static byte[] m(R r) { return r.l().head().hash(); }");
        assertSame("record R(TokenName t) { } static byte[] m(R r) { return r.t().name(); }");
    }

    // --- Static calls ---

    @Test
    void aLocalCallOfEachShape() {
        assertSame("static long h(long a, long b) { return a + b; } static long m(long a) { return h(a, 1); }");
        assertSame("static long h() { return 2; } static long m() { return h(); }");
        assertSame("static long h(long n) { return n <= 0 ? 0 : h(n - 1); } static long m(long a) { return h(a); }");
        assertSame("static long h(long a) { return a; } static long h(int a) { return a; } static long m(long a) { return h(a); }");
    }

    @Test
    void valueOf() {
        assertSame("static BigInteger m(long a) { return BigInteger.valueOf(a); }");
        assertSame("static BigInteger m() { return BigInteger.valueOf(5); }");
        assertSame("static BigInteger m(long a) { return BigInteger.valueOf(a + 1).add(BigInteger.ONE); }");
    }

    /** {@code JulcMap.of} is off-chain only: the reference's rejection, at the reference's place. */
    @Test
    void julcMapOfIsRejected() {
        var members = "static boolean m(BigInteger a) { return JulcMap.of(a, a).isEmpty(); }";
        var failure = rejected(members);
        assertEquals("JULC0062", failure.code());
        var reference = assertThrows(CompilerException.class, () -> new JulcCompiler(null, new CompilerOptions())
                .compileMethod(source(members), "m", List.of())).diagnostics().getFirst();
        assertEquals(reference.code(), failure.code());
        assertEquals(reference.message(), failure.message());
    }

    @Test
    void aQualifiedOwnMethodIsRejectedAsByTheReference() {
        assertEquals("Undefined variable: C", javac("static long h() { return 1; } static long m() { return C.h(); }")
                .diagnostics().getFirst().message());
    }

    @Test
    void aMethodOfAnotherClassOfTheUnitIsRejected() {
        assertEquals("Undefined variable: D", javac("static class D { static long h() { return 1; } } static long m() { return D.h(); }")
                .diagnostics().getFirst().message());
    }

    @Test
    void aStaticMethodThroughAVariableDoesNotLower() {
        assertEquals("a static call not qualified by a simple class name",
                notPorted("static BigInteger m(BigInteger a) { return a.valueOf(1); }"));
    }

    @Test
    void aQualifiedClassNameDoesNotLower() {
        assertEquals("a static call not qualified by a simple class name",
                notPorted("static java.math.BigInteger m() { return java.math.BigInteger.valueOf(1); }"));
    }

    @Test
    void anInstanceHelperIsNotALocal() {
        var source = IMPORTS + """
                import org.julclang.ledger.ScriptContext;
                import org.julclang.stdlib.annotation.Entrypoint;
                import org.julclang.stdlib.annotation.SpendingValidator;
                @SpendingValidator
                class V {
                    boolean helper() { return true; }
                    @Entrypoint boolean validate(PlutusData r, ScriptContext c) { return helper(); }
                }
                """;
        var failure = assertInstanceOf(NotPorted.class, assertThrows(CompilerException.class, () ->
                new JulcCompiler(null, new CompilerOptions().setFrontend(CompilerFrontend.JAVAC)).compile(source, List.of())));
        assertTrue(failure.construct().startsWith("a call of helper"), failure::construct);
    }

    // --- Varargs ---

    @Test
    void aVarargsMethodDoesNotLower() {
        assertEquals("a call of a varargs method", notPorted(
                "static long h(long a, byte... rest) { return a; } static long m() { return h(1); }"));
        assertEquals("a call of a varargs method", notPorted(
                "static long h(long a, byte... rest) { return a; } static long m() { return h(1, new byte[]{1}); }"));
    }

    // --- JULC0057 ---

    @Test
    void anInitializerThatCallsAMethodIsRejectedWhereTheReferenceRejectsIt() {
        var members = "static long h() { return 1; } static final long K = h(); static long m() { return K; }";
        var failure = rejected(members);
        assertEquals("JULC0057", failure.code());
        assertEquals("Static field 'K' calls method h; static field initializers cannot call methods of their class", failure.message());
        var reference = assertThrows(CompilerException.class, () -> new JulcCompiler(null, new CompilerOptions())
                .compileMethod(source(members), "m", List.of())).diagnostics().getFirst();
        assertEquals(reference.code(), failure.code());
        assertEquals(reference.message(), failure.message());
        assertEquals(reference.line(), failure.line());
        assertEquals(reference.column(), failure.column());
    }

    @Test
    void anInitializerThatCallsAMethodThroughAnOperatorIsRejected() {
        assertEquals("JULC0057", rejected("static long h() { return 1; } static final long K = 1 + h(); static long m() { return K; }").code());
    }

    @Test
    void anInitializerWithNoCallCompiles() {
        assertSame("static long h() { return 1; } static final long K = 1 + 2; static long m() { return K + h(); }");
        assertSame("static final BigInteger K = BigInteger.valueOf(5).add(BigInteger.ONE); static BigInteger m() { return K; }");
    }

    /** A name in the initializer that shares a method's name is the field, so nothing here is a call of the method. */
    @Test
    void aFieldNamedLikeAMethodIsNotACall() {
        assertSame("static long h() { return 1; } static final long h = 2; static final long K = h; static long m() { return K + h(); }");
    }
}
