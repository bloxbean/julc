package org.julclang.compiler.frontend.javac.lower;

import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerFrontend;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.OptimizationLevel;
import org.julclang.compiler.error.CompilerDiagnostic;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.julclang.compiler.frontend.javac.lower.LoweringFixture.assertSameMethodAsReference;
import static org.julclang.compiler.frontend.javac.lower.LoweringFixture.assertSameMethodRejectionAsReference;

/**
 * ADR-068 I2a: a rejection the reference makes without a code has one on the javac frontend, with the reference's words and
 * position ({@link LoweringFixture}'s comparison holds each to that). Each rule is held in both directions: the shape it
 * rejects, and the nearest shape it lets through, which compiles to the reference's bytes at every level.
 */
class JavacSubsetCodesTest {

    private static final String IMPORTS = """
            import java.math.BigInteger;
            import org.julclang.core.PlutusData;
            import org.julclang.core.types.JulcList;
            import org.julclang.stdlib.Builtins;
            """;

    private static String code(String source) {
        var options = new CompilerOptions().setFrontend(CompilerFrontend.JAVAC).setOptimizationLevel(OptimizationLevel.DEFAULT);
        var thrown = assertThrows(CompilerException.class, () -> new JulcCompiler(null, options).compileMethod(source, "m"));
        return thrown.diagnostics().stream().filter(CompilerDiagnostic::isError).findFirst().orElseThrow().code();
    }

    /** Both frontends reject {@code body}, in a class {@code F} with a method {@code m}, alike; javac's has {@code expected}. */
    private static void assertCoded(String expected, String body) {
        var source = IMPORTS + body;
        assertSameMethodRejectionAsReference(source, "m");
        assertEquals(expected, code(source));
    }

    // --- The reference's validator: rejected wherever they stand, in lowered code and in code nothing lowers ---

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "JULC0104|class F { static BigInteger m(BigInteger a) { synchronized (a) { return a; } } }",
            "JULC0104|class F { static BigInteger m(BigInteger a) { return a; } BigInteger n(BigInteger a) { synchronized (a) { return a; } } }",
            "JULC0107|class F { static long m() { return 1; } long n() { return this.hashCode(); } }",
            "JULC0107|class F { static long m() { return 1; } long n() { return super.hashCode(); } }",
            "JULC0109|class F extends G { static long m() { return 1; } } class G { }",
            "JULC0014|class F { static long m() { var a = new long[]{1, 2}; return 1; } }",
            "JULC0014|class F { static long m() { var a = new byte[3]; return 1; } }",
            "JULC0108|class F { static long m(long[] a) { return a[0]; } }",
            "JULC0017|class F { static long m() { BigInteger z = null; return 1; } }",
            "JULC0017|class F { static long m() { return 1; } long n() { BigInteger z = null; return 1; } }",
            "JULC0020|class F { static long m(double d) { return 1; } }",
            "JULC0105|class F { static long m() { java.util.function.Function<BigInteger, BigInteger> f = BigInteger::negate; return 1; } }",
            "JULC0004|class F { static BigInteger m(BigInteger a) { switch (a.intValue()) { case 1: break; default: break; } return a; } }"})
    void theValidatorsRules(String expected, String body) {
        assertCoded(expected, body);
    }

    // --- Statements and loops ---

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "JULC0110|class F { static long m(long a) { { long b = a; } return a; } }",
            "JULC0110|class F { static long m(long a) { ; return a; } }",
            "JULC0002|class F { static long m(long a) { long b; return a; } }",
            "JULC0006|class F { static long m(long a) { { return a; } } }",
            "JULC0011|class F { sealed interface S permits A { } record A(long v) implements S { } static long m(S s) { if (!(s instanceof A q)) { return 0; } return q.v(); } }",
            "JULC0111|class F { static long m(JulcList<BigInteger> xs) { for (var x : xs) { return 1; } return 0; } }",
            "JULC0113|class F { static long m(JulcList<BigInteger> xs) { long t = 0; for (var x : xs) { long b = 0; if (x.signum() > 0) { b = 1; } t = t + b; } return t; } }",
            "JULC0118|class F { static long m(long a) { return a << 2; } }",
            "JULC0118|class F { static long m(long a) { return a >>> 2; } }",
            "JULC0118|class F { static long m(long a) { return a >> 2; } }",
            "JULC0118|class F { static long m(long a) { return a & 2; } }",
            "JULC0119|class F { static long m(long a) { return ~a; } }",
            "JULC0119|class F { static long m(long a) { return +a; } }"})
    void statementsAndOperators(String expected, String body) {
        assertCoded(expected, body);
    }

    /** What the rules let through: the nearest shapes, on the reference's bytes at every level. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "class F { static byte[] m() { var b = new byte[]{1, -2}; return b; } }",
            "class F { static long m(long a) { return -a; } }",
            "class F { static boolean m(boolean a) { return !a; } }",
            "class F { static long m(long a) { long b = a; if (a > 0) { return b; } return a; } }",
            "class F { static long m(JulcList<BigInteger> xs) { long t = 0; for (var x : xs) { if (x.signum() > 0) { t = t + 1; } } return t; } }",
            "class F { static long m(JulcList<BigInteger> xs) { long t = 0; for (var x : xs) { if (x.signum() > 0) { t = t + 1; break; } } return t; } }"})
    void theNearestShapesCompile(String body) {
        assertSameMethodAsReference(IMPORTS + body, "m");
    }

    /** An array initializer with no {@code new} has no rule of the reference's own: it stays a stop. */
    @Test
    void aBareArrayInitializerStaysAStop() {
        LoweringFixture.assertMethodNotPorted(IMPORTS + "class F { static long m() { long[] a = {1, 2}; return 1; } }", "m", "NEW_ARRAY", 5);
    }

    // --- F946: every error of a scan, the first first ---

    /** Two errors: the first is what the first alone gives, and with it removed, the second is the reference's. */
    @Test
    void theScanCollectsEveryErrorInOrder() {
        var both = IMPORTS + "class F { static long m(long a) { BigInteger z = null; synchronized (z) { return a; } } }";
        var options = new CompilerOptions().setFrontend(CompilerFrontend.JAVAC);
        var thrown = assertThrows(CompilerException.class, () -> new JulcCompiler(null, options).compileMethod(both, "m"));
        var codes = thrown.diagnostics().stream().filter(CompilerDiagnostic::isError).map(CompilerDiagnostic::code).toList();
        assertEquals(List.of("JULC0017", "JULC0104"), codes);
        assertCoded("JULC0104", "class F { static long m(long a) { BigInteger z = BigInteger.ONE; synchronized (z) { return a; } } }");
    }

    /** A construct not ported after an error: the error is the rejection, and no {@code JULC0084} goes with it. */
    @Test
    void anErrorComesBeforeAStop() {
        var source = IMPORTS + "class F { static long m(long a) { BigInteger z = null; long c = 'a'; return a; } }";
        var options = new CompilerOptions().setFrontend(CompilerFrontend.JAVAC);
        var thrown = assertThrows(CompilerException.class, () -> new JulcCompiler(null, options).compileMethod(source, "m"));
        assertTrue(thrown.diagnostics().stream().noneMatch(d -> "JULC0084".equals(d.code())), thrown.getMessage());
        assertEquals("JULC0017", thrown.diagnostics().getFirst().code());
    }

    // --- ADR-067 on the javac frontend (D11): a record's declaration ---

    private static final String VALIDATOR = IMPORTS + """
            import org.julclang.ledger.ScriptContext;
            import org.julclang.stdlib.annotation.*;
            @SpendingValidator
            class V {
                %s
                @Entrypoint
                static boolean validate(PlutusData redeemer, ScriptContext ctx) { return true; }
            }
            """;

    private static String javacCode(String source) {
        var options = new CompilerOptions().setFrontend(CompilerFrontend.JAVAC);
        var thrown = assertThrows(CompilerException.class, () -> new JulcCompiler(null, options).compile(source));
        return thrown.diagnostics().stream().filter(CompilerDiagnostic::isError).findFirst().orElseThrow().code();
    }

    /** ADR-067 D6: a record is a variant of one sealed interface; the reference accepts two (T30), and javac's frontend does not. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "sealed interface S permits A { } sealed interface T permits A { } record A(long v) implements S, T { }",
            "sealed interface S permits A, B { } sealed interface T permits B, A { } record A(long v) implements S, T { } record B(long v) implements S, T { }"})
    void aRecordHasOneSealedParent(String declarations) {
        assertEquals("JULC0079", javacCode(VALIDATOR.formatted(declarations)));
    }

    /**
     * What stopped at {@code JULC0084} where a record of two sealed parents is built, tested or matched now stops at its
     * declaration. The reference accepts each (T30), and a compilation with the gate on too: the rule is this frontend's (D11).
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "sealed interface P1 permits R { } sealed interface P2 permits R { } record R() implements P1, P2 { } static long m(P1 p) { return switch (p) { case R r -> 1; }; }",
            "sealed interface P1 permits R { } sealed interface P2 permits R { } record R() implements P1, P2 { } static boolean m(P1 p) { return p instanceof R r; }",
            "sealed interface U { record I(BigInteger a) implements U, W { } } sealed interface W permits U.I { } static U m(BigInteger a) { return new U.I(a); }"})
    void aUseOfARecordOfTwoSealedParentsStopsAtItsDeclaration(String members) {
        var source = IMPORTS + "class C {\n" + members + "\n}\n";
        assertEquals("JULC0079", code(source));
        new JulcCompiler(null, new CompilerOptions()).compileMethod(source, "m");
    }

    /** A record's instance method is no member a record holds, before the lambda it returns is read. */
    @Test
    void aRecordsMethodStopsAtTheRecord() {
        assertEquals("JULC0080", code(IMPORTS + "class C { record R(BigInteger a) { java.util.function.Predicate<BigInteger> h() { return x -> true; } } "
                + "static boolean m(R r) { return true; } }"));
    }

    /** In a guard too, a unary plus is the reference's uncoded rejection, here with its code. */
    @Test
    void aUnaryPlusInAGuard() {
        var source = IMPORTS + "class C { sealed interface S permits A, B { } record A() implements S { } record B() implements S { } "
                + "static long m(S s, long n) { return switch (s) { case A a when +n > 0 -> 1; default -> 3; }; } }";
        assertEquals("JULC0119", code(source));
        var reference = assertThrows(CompilerException.class, () -> new JulcCompiler(null, new CompilerOptions()).compileMethod(source, "m"));
        assertEquals(null, reference.diagnostics().getFirst().code());
    }

    /** A sealed parent and a plain interface: one sealed parent. */
    @Test
    void aPlainInterfaceIsNoSecondParent() {
        LoweringFixture.assertSameAsReference(VALIDATOR.formatted(
                "interface Named { } sealed interface S permits A { } record A(long v) implements S, Named { }"));
    }

    /** ADR-067 D9: a record's body holds nested types alone. The rules that hold on both frontends read their members first. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "JULC0080|record R(long v) { static long twice(long x) { return x + x; } }",
            "JULC0080|record R(long v) { long twice() { return v + v; } }",
            "JULC0080|record R(long v) { public String toString() { return \"R\"; } }",
            "JULC0080|record R(long v) { public int hashCode() { return 1; } }",
            "JULC0080|record R(long v) { R { } }",
            "JULC0099|record R(long v) { R(long v, long w) { this(v); } }",
            "JULC0080|record R(long v) { static final long ONE = 1; }",
            "JULC0080|record R(long v) { static { } }",
            "JULC0101|record R(long v) { public long v() { return v; } }"})
    void aRecordsBodyHoldsNestedTypesAlone(String expected, String declaration) {
        assertEquals(expected, javacCode(VALIDATOR.formatted(declaration)));
    }

    @Test
    void aRecordWithANestedTypeCompiles() {
        LoweringFixture.assertSameAsReference(VALIDATOR.formatted("record R(long v) { record Inner(long w) { } }"));
    }

    // --- JULC0087's words ---

    @Test
    void anArrayAndWhatTheCheckCannotReadAreToldApart() {
        var array = IMPORTS + "class F { static boolean m(byte[] a, byte[] b) { return a.equals(b); } }";
        var raw = IMPORTS + "class F { static boolean m(JulcList xs, BigInteger b) { return xs.contains(b); } }";
        var options = new CompilerOptions().setFrontend(CompilerFrontend.JAVAC);
        var first = assertThrows(CompilerException.class, () -> new JulcCompiler(null, options).compileMethod(array, "m"))
                .diagnostics().getFirst();
        assertEquals("JULC0087", first.code());
        assertTrue(first.message().endsWith("whose equality on the JVM compares an array by identity"), first.message());
        var second = assertThrows(CompilerException.class, () -> new JulcCompiler(null, options).compileMethod(raw, "m"))
                .diagnostics().getFirst();
        assertEquals("JULC0087", second.code());
        assertTrue(second.message().endsWith(", whose equality on the JVM cannot be shown to compare by value"), second.message());
    }
}
