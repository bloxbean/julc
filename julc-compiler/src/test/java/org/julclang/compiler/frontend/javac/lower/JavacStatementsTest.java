package org.julclang.compiler.frontend.javac.lower;

import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerFrontend;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.frontend.javac.NotPorted;
import org.julclang.stdlib.StdlibRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locals, {@code if}, {@code return} and expression statements: each program compiles to the reference's bytes at every
 * level, with and without a source map, and a rejection is the reference's. Evaluation against the JVM is
 * {@code Adr068StatementsHarnessTest}'s; this is I1 and I2a on one screen.
 */
class JavacStatementsTest {

    private static final String IMPORTS = """
            import java.math.BigInteger;
            import java.util.Optional;
            import org.julclang.core.PlutusData;
            import org.julclang.core.types.JulcArray;
            import org.julclang.core.types.JulcG1;
            import org.julclang.core.types.JulcList;
            import org.julclang.ledger.Credential;
            import org.julclang.ledger.OutputDatum;
            import org.julclang.ledger.Value;
            import org.julclang.stdlib.Builtins;
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
            "static long m(long a) { long b = a + 1; return b; }",
            "static long m(long a) { long b = a + 1; long c = b * 2; return b + c; }",
            "static long m(long a) { var b = a; return b; }",
            "static BigInteger m(BigInteger a) { var b = a.add(BigInteger.ONE); return b.multiply(b); }",
            "static boolean m(long a) { boolean b = a > 0; return !b; }",
            "static boolean m(long a) { Boolean b = a > 0; return b; }",
            "static String m(String a) { String b = a + \"x\"; return b; }",
            "static byte[] m(byte[] a) { byte[] b = new byte[]{1}; return Builtins.appendByteString(a, b); }",
            "static PlutusData m(PlutusData a) { PlutusData b = a; return b; }",
            "static long m(long a) { final long b = a; return b; }",
            "static long m() { final int b = 5; return b + 1; }",
            "static final long K = 3; static long m(long a) { long K = a; return K; }",
            "static final long K = 3; static long m(long a) { long b = K; return b + a; }",
            "static void h(long a) { } static long m(long a) { h(a); return a; }",
            "static long h(long a) { return a; } static long m(long a) { h(a); h(a + 1); return a; }",
            "static void m() { }",
            "static void m() { return; }",
            "static void m(long a) { long b = a; }",
            "static void h(long a) { } static void m(long a) { h(a); }",
    })
    void locals(String members) {
        same(members);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "static long m(boolean c) { if (c) { return 1; } return 2; }",
            "static long m(boolean c) { if (c) { return 1; } else { return 2; } }",
            "static long m(boolean c) { if (c) return 1; return 2; }",
            "static long m(boolean c) { if (c) return 1; else return 2; }",
            "static long m(boolean c, boolean d) { if (c) { if (d) { return 1; } return 2; } return 3; }",
            "static long m(boolean c, boolean d) { if (c) { return 1; } else if (d) { return 2; } else { return 3; } }",
            "static long m(boolean c, boolean d) { if (c) { return 1; } else if (d) { return 2; } return 3; }",
            "static long m(boolean c, boolean d) { if (c) if (d) return 1; return 3; }",
            "static long m(boolean c) { long a = 1; if (c) { long b = a + 1; return b; } long b = a + 2; return b; }",
            "static long m(boolean c) { long a = 1; if (c) { long b = a + 1; if (b > 0) { return b; } } long b = a + 2; return b; }",
            "static final long K = 10; static long m(long a) { if (a > 0) { long K = a + a; if (K > 5) { return K; } } return a + K; }",
            "static final long K = 10; static long m(long a) { if (a > 0) { return a; } long K = 5; return K; }",
            "static long h(long a) { return a; } static long m(boolean c) { if (c) { h(1); } return 2; }",
            "static long h(long a) { return a; } static long m(boolean c) { if (c) { h(1); } else { h(2); } return 2; }",
            "static long h(long a) { return a; } static long m(boolean c) { if (c) h(1); else h(2); return 2; }",
            "static long h(long a) { return a; } static long m(boolean c) { if (c) { long x = h(1); h(x); } return 2; }",
            "static long h(long a) { return a; } static long m(boolean c, boolean d) { if (c) { if (d) { h(1); } } return 2; }",
            "static long h(long a) { return a; } static long m(boolean c, boolean d) { if (c) if (d) h(1); return 2; }",
            "static long h(long a) { return a; } static long m(boolean c, boolean d) { if (c) { if (d) { h(1); } h(2); } return 2; }",
            "static long h(long a) { return a; } static long m(boolean c) { if (c) { return 1; } else { h(2); } return 2; }",
            "static long h(long a) { return a; } static long m(boolean c, boolean d) { if (c) { if (d) { h(1); } return 1; } return 2; }",
            "static void h(long a) { } static void m(boolean c) { if (c) { h(1); } }",
            "static void h(long a) { } static void m(boolean c) { if (c) { h(1); } else { h(2); } }",
            "static void h(long a) { } static void m(boolean c) { if (c) { return; } h(1); }",
            "static void h(long a) { } static void m(boolean c) { if (c) { h(1); return; } h(1); }",
            "static void m(boolean c) { if (c) { } }",
            "static void m(boolean c) { if (c) { } else { } }",
            "static long m(boolean c) { if (c) { } return 1; }",
            "static long m(boolean c, boolean d) { if (c) { return 1; } if (d) { return 2; } return 3; }",
            "static long m(boolean c, boolean d, boolean e) { if (c) { return 1; } if (d) { return 2; } if (e) { return 3; } return 4; }",
            "static long m(boolean c) { if ((c)) { return 1; } return 2; }",
            "static boolean m(long a) { if (a > 0 && a < 5) { return true; } return false; }",
            "static long m(long a) { if (Builtins.trace(\"x\", Builtins.iData(a)).equals(Builtins.iData(a))) { return 1; } return 2; }",
    })
    void ifs(String members) {
        same(members);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "static long m() { int a = 1, b = 2; return a + b; }",
            "static long m() { final long a = 1, b = 2; return a + b; }",
            "static long m() { long a = 1; return 1; long b; }",
            "static long m() { long a; a = 1; return a; }",
            "static long m(long a) { a = 1; return a; }",
            "static long m(long a) { long b = 1; b = 2; return b; }",
            "static long m(long a) { a += 1; return a; }",
            "static long m(long a) { a -= 1; return a; }",
            "static long m(long a) { a *= 2; return a; }",
            "static long m(long a) { a &= 1; return a; }",
            "static long m(long a) { a <<= 1; return a; }",
            "static long m(long a) { a >>>= 1; return a; }",
            "static long m(boolean c, long a) { if (c) { a = 1; } return a; }",
            "static long m(long a) { { return a; } }",
            "static long m(long a) { { long b = a; } return a; }",
            "static long m(long a) { ; return a; }",
            "static long m(boolean c) { if (c) { ; } return 1; }",
            "static long m(boolean c) { if (c) { { return 1; } } return 2; }",
            "static long m(boolean c) { if (c) { { return 1; } } else { return 2; } }",
            "static long m(boolean c) { if (c) { return 1; } else { { return 2; } } }",
            "static long m(boolean c) { if (c) { { } } return 2; }",
            "static long m(long a) { x: if (a > 0) { return 1; } return 2; }",
            "static long m(long a) { x: return a; }",
            "static long m(boolean c) { if (c) y: { return 1; } return 2; }",
            "static long m(boolean c) { if (c) y: Builtins.error(); return 2; }",
            "static long m(JulcList b) { JulcList<JulcG1> x = b; return 1; }",
    })
    void rejections(String members) {
        rejected(members);
    }

    /**
     * Which of two rejections comes first is the reference's order of generation (I2a), and it differs by shape. Each
     * program breaks two rules, and what the rejection says shows which ran first.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "static long m(boolean c, long a) { if (c) { a = 1; return 1; } a = 2; return 2; }",
            "static long m(boolean c, long a) { if (c) { a = 1; } a = 2; return 2; }",
            "static long m(S s, long a) { if (s instanceof S.A x) { a = 1; return 1; } else { a = 2; return 2; } }",
            "static long m(S s, long a) { if (s instanceof S.A x) { a = 1; } else { a = 2; } return 3; }",
            "static long m(boolean c, long a) { if (c) { a = 1; } else { a = 2; } return 3; }",
            "static long m(boolean c, long a) { if (c) { a = 1; return 1; } else { a = 2; return 2; } }",
    })
    void theFirstOfTwoRejections(String members) {
        rejected(SUM + members);
    }

    /** A record's component is no field to the reference, so a pattern variable may take its name: only a field's is {@code JULC0059}. */
    @Test
    void aPatternVariableMayTakeAComponentsName() {
        LoweringFixture.assertSameMethodAsReference("""
                import java.math.BigInteger;
                record O(BigInteger a) {
                    static class C {
                        sealed interface S { record A(BigInteger v) implements S { } }
                        static boolean m(S s) { return s instanceof S.A a; }
                    }
                }
                """, "m");
    }

    /** I2: the reference does not know what a stdlib call returns, so neither its call nor a var of it has members. */
    @ParameterizedTest
    @ValueSource(strings = {
            "static boolean m(BigInteger a) { var o = Optional.of(a); return o.isPresent(); }",
            "static BigInteger m(BigInteger a) { var o = Optional.of(a); return o.get(); }",
    })
    void aVarOfAStdlibCallHasNoMembers(String members) {
        var failure = javac(members);
        assertEquals("JULC0055", failure.diagnostics().getFirst().code(), failure::getMessage);
    }

    @Test
    void aVarOfAnArrayOfTwoTypesIsRejected() {
        rejected("static long m() { var a = JulcArray.of(BigInteger.ONE, \"x\"); return 1; }");
    }

    /** The reference says the same with no position, and qualifies nothing: the type rejections of M2. */
    @ParameterizedTest
    @ValueSource(strings = {"byte b = 1; return b;", "short b = 1; return b;", "Integer b = 1; return b;", "Object b = 1; return 1;"})
    void aLocalOfATypeOutsideTheSubset(String body) {
        var failure = assertThrows(CompilerException.class, () -> new JulcCompiler(null, new CompilerOptions().setFrontend(CompilerFrontend.JAVAC))
                .compileMethod(source("static long m() { " + body + " }"), "m", List.of()));
        assertFalse(failure instanceof NotPorted, failure::getMessage);
        assertEquals(12, failure.diagnostics().getFirst().line());
    }

    @Test
    void anEmptyStatementAsABranchIsRejected() {
        var failure = assertThrows(CompilerException.class, () -> new JulcCompiler(null, new CompilerOptions().setFrontend(CompilerFrontend.JAVAC))
                .compileMethod(source("static long m(boolean c) { if (c) ; return 1; }"), "m", List.of()));
        assertEquals("Unsupported statement: EmptyStmt", failure.diagnostics().getFirst().message());
    }

    @Test
    void unreachableCodeWarnsAsTheReferenceDoes() {
        same("static long m(long a) { Builtins.error(); return a; }");
        same("static long m(boolean c) { if (c) { Builtins.error(); long b = 1; return b; } return 2; }");
    }

    // --- instanceof ---

    private static final String SUM = """
            sealed interface S { record A(BigInteger v) implements S { } record B(BigInteger v, BigInteger w) implements S { } record D() implements S { } }
            static BigInteger read(S.B b) { return b.w(); }
            """;

    @ParameterizedTest
    @ValueSource(strings = {
            "static BigInteger m(S s) { if (s instanceof S.A a) { return a.v(); } return BigInteger.ZERO; }",
            "static BigInteger m(S s) { if (s instanceof S.B b) { return b.w(); } return BigInteger.ZERO; }",
            "static BigInteger m(S s) { if (s instanceof S.A a) { return a.v(); } else { return BigInteger.ONE; } }",
            "static BigInteger m(S s) { if (s instanceof S.A a) return a.v(); return BigInteger.ZERO; }",
            "static BigInteger m(S s) { if (s instanceof S.A a) { return a.v(); } else if (s instanceof S.B b) { return b.w(); } return BigInteger.ZERO; }",
            "static BigInteger m(S s) { if (s instanceof S.A v) { return v.v(); } else if (s instanceof S.B v) { return v.w(); } else { return BigInteger.TEN; } }",
            "static BigInteger m(S s) { if (s instanceof S.A a) { if (s instanceof S.A b) { return b.v().add(a.v()); } } return BigInteger.ZERO; }",
            "static BigInteger m(S s) { if (s instanceof S.A a) { var x = a.v(); return x; } var y = BigInteger.ONE; return y; }",
            "static BigInteger m(S s, BigInteger k) { if (s instanceof S.B b) { k.add(b.w()); } return k; }",
            "static void u(BigInteger x) { } static BigInteger m(S s, BigInteger k) { if (s instanceof S.A a) { u(a.v()); } return k; }",
            "static void u(BigInteger x) { } static BigInteger m(S s, BigInteger k) { if (s instanceof S.A a) { u(a.v()); } else { u(k); } return k; }",
            "static void u(BigInteger x) { } static BigInteger m(S s, boolean c, BigInteger k) { if (c) { if (s instanceof S.A a) { u(a.v()); } } return k; }",
            "static void u(BigInteger x) { } static void m(S s) { if (s instanceof S.A a) { u(a.v()); } }",
            "static void u(BigInteger x) { } static void m(S s, boolean c) { if (c) if (s instanceof S.A a) u(a.v()); }",
            "static boolean m(S s) { return s instanceof S.A a; }",
            "static boolean m(S s) { boolean b = s instanceof S.D d; return b; }",
            "static boolean m(S s) { if (s instanceof S.A a && true) { return true; } return false; }",
            "static boolean m(S s) { if (!(s instanceof S.A a)) { return true; } return false; }",
            "static boolean m(S s) { if ((s instanceof S.A a)) { return true; } return false; }",
            "static BigInteger m(S.A a) { if (a instanceof S.A b) { return b.v(); } return BigInteger.ZERO; }",
    })
    void instanceOf(String members) {
        same(SUM + members);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "static final BigInteger a = BigInteger.ONE; static BigInteger m(S s) { if (s instanceof S.A a) { return a.v(); } return a; }",
            "BigInteger a; static boolean m(S s) { return s instanceof S.A a; }",
    })
    void instanceOfRejections(String members) {
        rejected(SUM + members);
    }

    /** The reference's words; it says them with no diagnostic, and the javac frontend at the construct. */
    private static void rejectedWith(String members, String message) {
        var reference = assertThrows(CompilerException.class, () -> new JulcCompiler().compileMethod(source(SUM + members), "m", List.of()));
        assertTrue(reference.getMessage().contains(message), reference::getMessage);
        var failure = javac(SUM + members);
        assertFalse(failure instanceof NotPorted, failure::getMessage);
        assertEquals(message, failure.diagnostics().getFirst().message());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "static boolean m(S s) { return s instanceof S.A; }",
            "static boolean m(S s) { if (s instanceof S.A) { return true; } return false; }",
            "static boolean m(S s) { if (s instanceof S t) { return true; } return false; }",
    })
    void aTypeTestThatIsNoVariantPattern(String members) {
        var start = members.indexOf("s instanceof");
        var text = members.substring(start, start + (members.contains("S t") ? "s instanceof S t" : "s instanceof S.A").length());
        rejectedWith(members, "Unsupported instanceof pattern: " + text);
    }

    @Test
    void aRecordPatternIsNotOneEither() {
        var failure = javac(SUM + "static boolean m(S s) { if (s instanceof S.A(BigInteger v)) { return true; } return false; }");
        assertTrue(failure.diagnostics().getFirst().message().startsWith("Unsupported instanceof pattern: "), failure::getMessage);
        assertTrue(javac(SUM + "static boolean m(S s) { return s instanceof S.A(var v); }").getMessage().contains("Unsupported instanceof pattern: "));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "static BigInteger m(S s) { if ((s instanceof S.A a)) { return a.v(); } return BigInteger.ZERO; }",
            "static BigInteger m(S s) { if (s instanceof S.A a && a.v().signum() > 0) { return a.v(); } return BigInteger.ZERO; }",
            "static BigInteger m(S s) { if (!(s instanceof S.A a)) { return BigInteger.ZERO; } return a.v(); }",
            "static BigInteger m(S s) { return s instanceof S.A a ? a.v() : BigInteger.ZERO; }",
    })
    void aPatternVariableBindsOnlyWhereItIsTheWholeCondition(String members) {
        rejectedWith(members, "Undefined variable: a");
    }

    private static CompilerException javac(String members) {
        return assertThrows(CompilerException.class, () -> new JulcCompiler(StdlibRegistry.defaultRegistry(),
                new CompilerOptions().setFrontend(CompilerFrontend.JAVAC)).compileMethod(source(members), "m", List.of()));
    }

    /** What the JVM tests by class and a tag cannot: each stops, and never compiles to another answer. */
    @ParameterizedTest
    @ValueSource(strings = {
            "sealed interface P1 permits R { } sealed interface P2 permits R { } record R() implements P1, P2 { } static boolean m(P1 p) { return p instanceof R r; }",
            "sealed interface O permits W { } static final class W implements O { } static boolean m(O o) { return o instanceof W v; }",
            "sealed interface N permits W { } @org.julclang.stdlib.annotation.NewType record W(BigInteger value) implements N { } "
                    + "static boolean m(N n) { return n instanceof W v; }",
    })
    void aTypeTestOutsideOneSum(String members) {
        assertInstanceOf(NotPorted.class, javac(SUM + members));
    }

    /** The ledger's sums, whose variants come from class files, in their fixed order. */
    @ParameterizedTest
    @ValueSource(strings = {
            "static boolean m(Credential c) { return c instanceof Credential.ScriptCredential s; }",
            "static boolean m(Credential c) { return c instanceof Credential.PubKeyCredential s; }",
            "static boolean m(OutputDatum d) { return d instanceof OutputDatum.NoOutputDatum n; }",
            "static boolean m(OutputDatum d) { return d instanceof OutputDatum.OutputDatumHash n; }",
    })
    void ledgerSums(String members) {
        same(members);
    }

    // --- Validators: the entry point's own path, with a guarded return under a source map ---

    private static final String VALIDATOR = """
            import java.math.BigInteger;
            import org.julclang.core.PlutusData;
            import org.julclang.ledger.ScriptContext;
            import org.julclang.stdlib.annotation.*;
            %s
            class V {
                sealed interface R { record A(BigInteger v) implements R { } record B() implements R { } }
                %s
            }
            """;

    @ParameterizedTest
    @ValueSource(strings = {
            "@Entrypoint static boolean validate(BigInteger r, PlutusData c) { BigInteger b = r.add(BigInteger.ONE); return b.signum() > 0; }",
            "@Entrypoint static boolean validate(BigInteger r, PlutusData c) { if (r.signum() > 0) { return true; } return false; }",
            "@Entrypoint static boolean validate(BigInteger r, PlutusData c) { if (r.signum() > 0) { return true; } else { return r.signum() == 0; } }",
            "@Entrypoint static boolean validate(BigInteger r, PlutusData c) { if (r.signum() > 0) { if (r.signum() < 5) { return true; } } var x = r; return x.signum() < 0; }",
            "@Entrypoint static boolean validate(R r, ScriptContext c) { if (r instanceof R.A a) { return a.v().signum() > 0; } return false; }",
            "@Entrypoint static boolean validate(R r, ScriptContext c) { if (r instanceof R.B b) { return true; } else { return false; } }",
            "static BigInteger h(BigInteger x) { BigInteger y = x.add(x); return y; } @Entrypoint static boolean validate(BigInteger r, PlutusData c) { var z = h(r); return z.signum() > 0; }",
    })
    void validators(String members) {
        LoweringFixture.assertSameAsReference(VALIDATOR.formatted("@MintingValidator", members));
    }
}
