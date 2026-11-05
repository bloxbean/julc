package org.julclang.compiler.frontend.javac.lower;

import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.error.CompilerDiagnostic;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-068 M3b: operators, against the reference's bytes at every level, and D6's rules, where the frontends mean to
 * differ. Their meaning against the JVM is {@code Adr068CallsHarnessTest}'s.
 */
class JavacOperatorsTest {

    private static String source(String members) {
        return """
                import java.math.BigInteger;
                import org.julclang.core.PlutusData;
                import org.julclang.core.types.JulcG1;
                import org.julclang.core.types.JulcG2;
                import org.julclang.core.types.JulcList;
                import org.julclang.core.types.JulcMap;
                import org.julclang.ledger.PubKeyHash;
                import org.julclang.stdlib.Builtins;
                class C {
                """ + members + "\n}\n";
    }

    private static CompilerException javac(String members) {
        return assertThrows(CompilerException.class, () -> new JulcCompiler(null, new CompilerOptions())
                .compileMethod(source(members), "m", List.of()));
    }

    private static CompilerDiagnostic rejected(String members) {
        var failure = javac(members);
        assertEquals(1, failure.diagnostics().size(), failure::getMessage);
        return failure.diagnostics().getFirst();
    }

    private static void assertSame(String members) {
        LoweringFixture.assertSameMethodAsReference(source(members), "m");
    }

    @ParameterizedTest
    @ValueSource(strings = {"a + b", "a - b", "a * b", "a / b", "a % b", "-a", "-(a)", "- -a", "(a + b) * (a - b)", "a + 1 + b"})
    void arithmetic(String body) {
        assertSame("static long m(long a, long b) { return " + body + "; }");
        assertSame("static int m(int a, int b) { return " + body + "; }");
    }

    /** The JVM wraps these and the reference does not, so they stop rather than choose. */
    @ParameterizedTest
    @ValueSource(strings = {"-0x80000000", "-(0x80000000)", "-((0x80000000))", "-020000000000", "-0b10000000000000000000000000000000",
            "-(-2147483648)", "- -2147483648", "-0x8000000000000000L", "-(-9223372036854775808L)", "- -9223372036854775808L"})
    void theNegationOfAMinimumValueIsJavas(String body) {
        // Java's negation wraps a type's minimum value to itself (JLS 15.15.4); the reference gives the positive one (M5).
        // Adr068M5bHarnessTest holds the value to the JVM's at every level.
        new JulcCompiler(null, new CompilerOptions())
                .compileMethod(source("static long m() { return " + body + "; }"), "m", List.of());
    }

    @ParameterizedTest
    @ValueSource(strings = {"-2147483648", "-9223372036854775808L", "-0x7FFFFFFF", "-0x80000000L", "-0", "-(1)", "-(2147483647)"})
    void anyOtherNegationOfALiteralIsAsTheReferenceHasIt(String body) {
        assertSame("static long m() { return " + body + "; }");
    }

    @ParameterizedTest
    @ValueSource(strings = {"a < b", "a <= b", "a > b", "a >= b", "a == b", "a != b", "(a + 1) > (b + 1)", "(a + 1) >= b", "a > (b + 1)"})
    void comparison(String body) {
        assertSame("static boolean m(long a, long b) { return " + body + "; }");
    }

    @ParameterizedTest
    @ValueSource(strings = {"a && b", "a || b", "!a", "!!a", "a == b", "a != b", "a && b || !a", "(a || b) && !(a && b)"})
    void logical(String body) {
        assertSame("static boolean m(boolean a, boolean b) { return " + body + "; }");
    }

    @Test
    void conditional() {
        assertSame("static long m(boolean c, long a, long b) { return c ? a : b; }");
        assertSame("static long m(boolean c, long a) { return c ? a > 0 ? 1 : 2 : 3; }");
        assertSame("static String m(boolean c) { return c ? \"a\" : \"b\"; }");
    }

    /** ADR-047: a branch of another native type does not pass through a conditional, and the words are the reference's. */
    @Test
    void aConditionalKeepsNativeTypesApart() {
        var members = "static PlutusData h(boolean c, JulcG1 a, JulcG2 b) { return Builtins.listData(JulcList.of(c ? a : b)); }\n"
                + "static boolean m() { return true; }";
        var source = source(members);
        var options = new CompilerOptions();
        var javac = assertThrows(CompilerException.class, () -> new JulcCompiler(null, options).compileMethod(source, "m", List.of()));
        // Arms of two types are rejected at the conditional first (ADR-068 M5), and never meet the native boundary.
        assertEquals("JULC0012", javac.diagnostics().getFirst().code(), javac::getMessage);
        assertTrue(javac.getMessage().contains("different types"), javac::getMessage);
    }

    @ParameterizedTest
    @ValueSource(strings = {"a.compareTo(b) < 0", "a.compareTo(b) <= 0", "a.compareTo(b) > 0", "a.compareTo(b) >= 0", "a.compareTo(b) == 0",
            "a.compareTo(b) != 0", "0 > a.compareTo(b)", "0 >= a.compareTo(b)", "0 < a.compareTo(b)", "0 <= a.compareTo(b)", "0 == a.compareTo(b)",
            "0 != a.compareTo(b)", "a.add(b).compareTo(b.add(a)) > 0", "0 < a.add(b).compareTo(b.add(a))", "a.add(b).compareTo(b) >= 0",
            "a.compareTo(b.add(a)) >= 0"})
    void compareToForms(String body) {
        assertSame("static boolean m(BigInteger a, BigInteger b) { return " + body + "; }");
    }

    @ParameterizedTest
    @ValueSource(strings = {"(a.compareTo(b)) > 0", "a.compareTo(b) > (0)", "a.compareTo(b) > 0L", "a.compareTo(b) > -0", "a.compareTo(b) > 1",
            "a.compareTo(b) > 0x0", "a.compareTo(b) > 0_0", "1 < a.compareTo(b)"})
    void formsOutsideOrInsideTheRuleAsTheReferenceHasThem(String body) {
        assertSame("static boolean m(BigInteger a, BigInteger b) { return " + body + "; }");
    }

    /** A {@code compareTo} of another class is not D6's: on javac the receiver's type is what it is. */
    @Test
    void aUsersCompareToIsNotTheRule() {
        // A record's own method is outside the subset (ADR-067 D9): it stops at the declaration, before any call of it.
        var failure = rejected("record R(BigInteger a) { int compareTo(R other) { return 0; } }\n"
                + "static boolean m(R a, R b) { return a.compareTo(b) > 0; }");
        assertEquals("JULC0080", failure.code());
    }

    /** An operator the reference does not lower is its rejection, in its words and with a code (I2a). */
    @Test
    void anOperatorTheReferenceDoesNotLowerIsItsRejection() {
        for (String body : List.of("a & b", "a | b", "a ^ b", "a << 1", "a >> 1", "a >>> 1", "~a", "+a")) {
            String members = "static long m(long a, long b) { return " + body + "; }";
            LoweringFixture.assertSameMethodRejectionAsReference(source(members), "m");
            var code = javac(members).diagnostics().getFirst().code();
            assertEquals(body.length() == 2 ? "JULC0119" : "JULC0118", code, body);
        }
    }

    // --- JULC0086 ---

    @ParameterizedTest
    @ValueSource(strings = {"a == b", "a != b", "a.add(b) == b", "(a) == b"})
    void referenceEqualityOnIntegers(String body) {
        assertEquals("JULC0086", rejected("static boolean m(BigInteger a, BigInteger b) { return " + body + "; }").code());
    }

    @Test
    void referenceEqualityOnEachKindOfReference() {
        assertEquals("JULC0086", rejected("static boolean m(byte[] a, byte[] b) { return a == b; }").code());
        assertEquals("JULC0086", rejected("static boolean m(String a, String b) { return a == b; }").code());
        assertEquals("JULC0086", rejected("static boolean m(Boolean a, Boolean b) { return a == b; }").code());
        assertEquals("JULC0086", rejected("record R(BigInteger x) { } static boolean m(R a, R b) { return a == b; }").code());
        assertEquals("JULC0086", rejected("static boolean m(PlutusData a, PlutusData b) { return a != b; }").code());
    }

    @Test
    void theRejectionSaysWhatAndWhere() {
        var failure = rejected("static boolean m(BigInteger a, BigInteger b) { return a == b; }");
        assertEquals("a == b compares java.math.BigInteger by reference; use equals", failure.message());
        assertEquals(10, failure.line());
        assertEquals(55, failure.column());
    }

    /** D6: a primitive operand makes it numeric or boolean equality, which stays. */
    @Test
    void aPrimitiveOperandIsValueEquality() {
        assertSame("static boolean m(Boolean a, boolean b) { return a == b; }");
        assertSame("static boolean m(Boolean a) { return a == true; }");
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"a\" == \"a\"", "\"a\" != \"b\"", "\"a\" + \"b\" == \"ab\"", "(\"a\") == \"a\"", "A == \"x\"", "B == A + \"y\"", "A == A"})
    void plainConstantStringsStay(String body) {
        assertSame("static final String A = \"x\"; static final String B = A + \"y\"; static boolean m() { return " + body + "; }");
    }

    @ParameterizedTest
    @ValueSource(strings = {"(2147483647 + 1 < 0 ? \"a\" : \"b\") == \"a\"", "\"a\" + 1 == \"a1\"", "\"a\" + (\"b\" == \"b\") == \"atrue\"", "BAD == \"a\"", "ALIAS == \"a\"", "ALIAS2 == \"a\"",
            "S == \"a\"", "\"x\" == S", "NOT_FINAL == \"a\""})
    void anyOtherStringComparisonIsReferenceEquality(String body) {
        var failure = rejected("""
                static final String BAD = 2147483647 + 1 < 0 ? "a" : "b";
                static final String ALIAS = BAD;
                static final String ALIAS2 = ALIAS;
                static String NOT_FINAL = "a";
                static boolean m(String S) { return %s; }
                """.formatted(body));
        assertEquals("JULC0086", failure.code(), failure::toString);
    }

    @Test
    void aConstantThatComesFromAClassFileIsJavasExactValue() {
        assertSame("static final String A = \"x\"; static boolean m() { return A == \"x\" && \"x\" == A; }");
    }

    // --- JULC0090 ---

    @ParameterizedTest
    @ValueSource(strings = {"a + 1", "1 + a", "a + true", "a + b", "b + a", "a + a + 1", "1 + 2 + a"})
    void aNonStringOperand(String body) {
        var failure = rejected("static String m(String a, long b) { return " + body + "; }");
        assertEquals("JULC0090", failure.code(), failure::toString);
    }

    @Test
    void aConcatenationOfAnObject() {
        assertEquals("JULC0090", rejected("static String m(String a, BigInteger b) { return a + b; }").code());
        assertEquals("JULC0090", rejected("static String m(String a, byte[] b) { return a + b; }").code());
    }

    @Test
    void theConcatenationRejectionSaysWhat() {
        assertEquals("String concatenation a + 1 has a non-String operand of type int",
                rejected("static String m(String a) { return a + 1; }").message());
    }

    // --- JULC0087 ---

    @Test
    void equalsOnAnArrayIsIdentity() {
        var failure = rejected("static boolean m(byte[] a, byte[] b) { return a.equals(b); }");
        assertEquals("JULC0087", failure.code());
        assertEquals("Object.equals compares byte[], whose equality on the JVM compares an array by identity", failure.message());
    }

    /** javac numbers a capture by an identity hash: a message must not change from one compilation to the next (I4). */
    @Test
    void aCaptureIsPrintedWithoutItsNumber() {
        String members = "static boolean m(boolean c, BigInteger a) { var x = c ? new org.julclang.core.types.Tuple2<>(a, true) : new org.julclang.core.types.Tuple2<>(new byte[]{1}, true); return x.first().equals(a); }";
        var first = javac(members).diagnostics().getFirst().message();
        assertEquals(first, javac(members).diagnostics().getFirst().message());
        assertFalse(first.matches(".*capture#\\d.*"), first);
    }

    @Test
    void equalsThroughARecordOrASealedVariant() {
        assertEquals("JULC0087", rejected("record R(byte[] b) { } static boolean m(R a, R b) { return a.equals(b); }").code());
        assertEquals("JULC0087", rejected("record I(byte[] b) { } record R(I i) { } static boolean m(R a, R b) { return a.equals(b); }").code());
        assertEquals("JULC0087", rejected("sealed interface S { record A(byte[] b) implements S { } } static boolean m(S a, S b) { return a.equals(b); }").code());
    }

    @Test
    void aRecordThatDeclaresEqualsIsNotOnTheAllowList() {
        // A record declares no equals at all (ADR-067 D9): the declaration stops first.
        assertEquals("JULC0080", rejected("record R(BigInteger x) { public boolean equals(Object o) { return true; } }\n"
                + "static boolean m(R a, R b) { return a.equals(b); }").code());
    }

    @Test
    void aJuLCCollectionComparesBytesByValue() {
        assertSame("static boolean m(JulcList<byte[]> l, byte[] x) { return l.contains(x); }");
        assertSame("static BigInteger m(JulcMap<byte[], BigInteger> l, byte[] x) { return l.get(x); }");
    }

    @Test
    void aCollectionOfRecordsWithArraysIsNotSafe() {
        assertEquals("JULC0087", rejected("record R(byte[] b) { } static boolean m(JulcList<R> l, R x) { return l.contains(x); }").code());
        assertEquals("JULC0087", rejected("record R(byte[] b) { }\n"
                + "static boolean m(JulcMap<R, BigInteger> l, R x) { return l.containsKey(x); }").code());
    }

    @Test
    void aLedgerHashIsOnTheAllowList() {
        assertSame("static boolean m(PubKeyHash a, PubKeyHash b) { return a.equals(b); }");
        assertSame("static boolean m(JulcList<PubKeyHash> l, PubKeyHash b) { return l.contains(b); }");
    }

    /**
     * Code nothing lowers never runs: after the reference's rules that hold wherever code stands, a statement there is passed
     * over, as the reference passes over it (ADR-068 M5), so a method of it compiles to the reference's bytes.
     */
    @ParameterizedTest
    @ValueSource(strings = {"long notLowered(long a) { return a + 1; }", "long notLowered(BigInteger a) { return a.longValue(); }",
            "static class N { static long f() { return 1 + 1; } }",
            "static class N { static long f(JulcList<BigInteger> xs) { long c = 0; for (var x : xs) { if (x.signum() < 0) { continue; } c = c + 1; } return c; } }",
            "static class N { static long f(long a) { long c = a; while (c > 0) { c = c - 1; } done: { c = c + 1; } return c; } }"})
    void codeNothingLowersIsPassedOver(String member) {
        assertSame(member + "\nstatic boolean m() { return true; }");
    }

    /** A string of an unpaired surrogate in code nothing lowers is the reference's rejection, as in a field's initializer. */
    @Test
    void anUnpairedSurrogateInCodeNothingLowers() {
        String members = "static class N { static String f() { String s = \"\\uD800\"; return s; } }\nstatic boolean m() { return true; }";
        assertEquals("JULC0093", javac(members).diagnostics().getFirst().code());
    }

    /** What the reference rejects wherever it stands is rejected in code nothing lowers, with its code. */
    @Test
    void codeNothingLowersKeepsTheRulesThatHoldEverywhere() {
        assertEquals("JULC0017", javac("static class N { static long f() { done: { BigInteger z = null; } return 1; } }\n"
                + "static boolean m() { return true; }").diagnostics().getFirst().code());
        // A type declared there is passed over too: javac's generated super() in its constructor is not source (M5a part 2).
        for (var member : new String[]{"static class N { static long f() { class L { } return 1; } }",
                "static class N { static long f() { record L(long a) { } return new L(1).a(); } }"}) {
            assertSame(member + "\nstatic boolean m() { return true; }");
        }
        // A super written in the source is still the reference's rejection there.
        assertEquals("JULC0107", javac("static class N { String f() { return super.toString(); } }\nstatic boolean m() { return true; }")
                .diagnostics().getFirst().code());
    }

    /** A field the compilation lowers is read as a method is, so D6's rules reach its initializer. */
    @Test
    void aLoweredFieldMeetsTheEqualityRules() {
        assertEquals("JULC0086", javac("static BigInteger x = BigInteger.ONE; static boolean b = x == BigInteger.TWO;\n"
                + "static boolean m() { return b; }").diagnostics().getFirst().code());
    }

    /** A field nothing lowers holds a constant: {@code JULC0095}'s rule, whatever lowering has learned. */
    @ParameterizedTest
    @ValueSource(strings = {"long f = 1 + 1;", "BigInteger f = BigInteger.valueOf(1);", "boolean f = !true;", "long f = true ? 1 : 2;",
            "static class N { static long f = 1 + 1; }", "record N(long a) { static long f = 1 + 1; }"})
    void aFieldNothingLowersHoldsAConstant(String member) {
        assertEquals("JULC0095", javac(member + "\nstatic boolean m() { return true; }").diagnostics().getFirst().code());
    }

    /** {@code @Param}'s initializer is not lowered: it holds a constant, and D14's remedy is one. */
    @Test
    void aParamInitializerIsNotLowered() {
        var failure = assertThrows(CompilerException.class, () -> compileParam("@Param static long f = 1 + 1;"));
        assertEquals("JULC0095", failure.diagnostics().getFirst().code());
        assertDoesNotThrow(() -> compileParam("@Param static boolean f = Boolean.valueOf(true);"));
    }

    private static void compileParam(String field) {
        var source = "import org.julclang.stdlib.annotation.Param;\nclass C {\n" + field + "\nstatic boolean m() { return true; }\n}\n";
        new JulcCompiler(null, new CompilerOptions()).compileMethod(source, "m", List.of());
    }

    @Test
    void anAssignmentIsAnAnnotationsArgumentOnly() {
        assertEquals("JULC0061", javac("static long f; static long g = (f = 1);\nstatic boolean m() { return true; }").diagnostics().getFirst().code());
        assertEquals("JULC0095", javac("long f; long g = (f = 1);\nstatic boolean m() { return true; }").diagnostics().getFirst().code());
        assertSame("@SuppressWarnings(value = \"x\") static boolean m() { return true; }");
    }

    /** A null is a rejection of its own, not a reference comparison: {@code JULC0017}, as on the reference. */
    @Test
    void comparingWithNullIsNotJulc0086() {
        var failure = javac("static boolean m(BigInteger a) { return a == null; }");
        assertEquals("JULC0017", failure.diagnostics().getFirst().code(), failure::getMessage);
        LoweringFixture.assertSameMethodRejectionAsReference(source("static boolean m(BigInteger a) { return a == null; }"), "m");
    }

    /** A record's method is outside the subset wherever it stands (ADR-067 D9). */
    @Test
    void aRecordsMethodIsNoMemberOfTheSubset() {
        assertEquals("JULC0080", javac("record N(long a) { long f() { return a + 1; } }\nstatic boolean m() { return true; }")
                .diagnostics().getFirst().code());
    }
}
