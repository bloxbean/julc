package org.julclang.compiler.frontend.javac.lower;

import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.OptimizationLevel;
import org.julclang.compiler.error.CompilerDiagnostic;
import org.julclang.core.Constant;
import org.julclang.core.PlutusData;
import org.julclang.core.Term;
import org.julclang.core.flat.UplcFlatEncoder;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.vm.EvalResult;
import org.julclang.vm.JulcVm;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigInteger;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-068 M5's entry gate, part 1 ({@code adr/evidence/068-m5a.md}): what stopped the corpus at {@code JULC0084} now lowers as
 * the reference does, or is a coded rejection. The values are the JVM's in {@code Adr068M5aHarnessTest}; this pins the
 * frontend's decisions.
 */
class JavacM5aTest {

    private static final String IMPORTS = """
            import java.math.BigInteger;
            import org.julclang.core.PlutusData;
            import org.julclang.core.types.JulcList;
            import org.julclang.core.types.JulcMap;
            import org.julclang.core.types.Tuple2;
            import org.julclang.core.types.Tuple3;
            import org.julclang.stdlib.Builtins;
            """;

    private static String source(String members) {
        return IMPORTS + "class C {\n" + members + "\n}\n";
    }

    private static JulcCompiler compiler() {
        return new JulcCompiler(StdlibRegistry.defaultRegistry(), new CompilerOptions());
    }

    private static CompilerException javac(String members) {
        return assertThrows(CompilerException.class, () -> compiler().compileMethod(source(members), "m", List.of()));
    }

    private static CompilerDiagnostic rejected(String members) {
        var failure = javac(members);
        assertEquals(1, failure.diagnostics().size(), failure::getMessage);
        return failure.diagnostics().getFirst();
    }

    // --- Raw tuples: a component is the record's field, Data ---

    @ParameterizedTest
    @ValueSource(strings = {
            "static boolean m(BigInteger a) { Tuple2 t = new Tuple2(Builtins.iData(a), Builtins.iData(BigInteger.TWO)); return Builtins.unIData(t.first()).equals(a); }",
            "static BigInteger m(BigInteger a) { Tuple2 t = new Tuple2(Builtins.iData(a), Builtins.iData(BigInteger.TWO)); return Builtins.unIData(t.second()); }",
            "static BigInteger m(BigInteger a) { Tuple3 t = new Tuple3(Builtins.iData(a), Builtins.iData(a), Builtins.iData(BigInteger.TEN)); return Builtins.unIData(t.first()).add(Builtins.unIData(t.third())); }"})
    void aComponentOfARawTupleIsData(String members) {
        FrozenReference.assertSame("a shape M5a kept|" + members, HexFormat.of().formatHex(bytes(members)));
    }

    private static byte[] bytes(String members) {
        return UplcFlatEncoder.encodeProgram(compiler().compileMethod(source(members), "m", List.of()).program());
    }

    // --- Arms of a conditional or a switch javac types Object, Record or an intersection ---

    /**
     * Arms of one sealed interface of records share its representation, and lower: in a return, a typed local, a switch. As a
     * diamond's component their type is javac's intersection, {@code Record & S}, which has no representation: JULC0012.
     */
    @Test
    void anIntersectionInADiamondHasNoType() {
        assertEquals("JULC0012", rejected("sealed interface S { record A(BigInteger v) implements S { } record B() implements S { } } "
                + "static boolean m(boolean c, BigInteger a) { var t = new Tuple2<>(c ? new S.A(a) : new S.B(), a); return true; }").code());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "sealed interface S { record A(BigInteger v) implements S { } record B() implements S { } } static S m(boolean c, BigInteger a) { return c ? new S.A(a) : new S.B(); }",
            "sealed interface S { record A(BigInteger v) implements S { } record B() implements S { } } static long m(boolean c, BigInteger a) { S s = c ? new S.A(a) : new S.B(); return switch (s) { case S.A p -> 1; case S.B q -> 2; }; }",
            "sealed interface S { record A(BigInteger v) implements S { } record B() implements S { } } static S m(S s) { return switch (s) { case S.A p -> new S.B(); case S.B q -> s; }; }"})
    void armsOfOneRepresentationLower(String members) {
        compiler().compileMethod(source(members), "m", List.of());
    }

    /**
     * Arms of different representations, one not Data, have no type: the reference says so of a {@code switch} when it is
     * encoded ({@code JULC0012}), and so does a conditional here, whose else branch the reference reads as its then branch.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "sealed interface S permits A, B { } record A(BigInteger n) implements S { } record B(BigInteger n) implements S { } static PlutusData d(BigInteger v) { return Builtins.iData(v); }\n"
                    + "static long m(BigInteger x) { S s = new A(x); var v = switch (s) { case A a -> a.n(); case B b -> d(b.n()); }; return JulcList.of(v).size(); }",
            "static long m(boolean c, BigInteger a) { return JulcList.of(c ? a : Builtins.iData(a)).size(); }",
            // As a diamond's component, which holds Data: DiamondTupleDiffTest#switchesOfUnknownTypeAreNotEncoded's.
            "sealed interface S permits A, B { } record A(BigInteger n) implements S { } record B(BigInteger n) implements S { } static PlutusData d(BigInteger v) { return Builtins.iData(v); }\n"
                    + "static long m(BigInteger x, BigInteger y) { S s = new A(x); var t = new Tuple2<>(switch (s) { case A a -> a.n(); case B b -> d(b.n()); }, y); return 1; }",
            "static long m(boolean c, BigInteger a) { var t = new Tuple2<>(c ? a : Builtins.iData(a), a); return 1; }",
            // Arms of two types, wherever they stand (review F1, F2): compared, in a var, nested, in a lambda.
            "sealed interface S permits A, B { } record A() implements S { } record B() implements S { } static boolean m(S s) { return s.equals(switch (s) { case A a -> a; default -> s; }); }",
            "sealed interface S { record A() implements S { } } static boolean m(boolean c, S a, S.A b) { return a.equals(c ? a : b); }",
            "static long m(boolean c, BigInteger a) { var v = c ? a : Builtins.iData(a); return 1; }",
            "static long m(boolean c, boolean d, BigInteger a) { return JulcList.of(c ? Builtins.iData(a) : (d ? a : Builtins.iData(a))).size(); }",
            "static long m(boolean c, BigInteger a) { JulcList<BigInteger> xs = JulcList.of(a); return xs.map(x -> c ? x : Builtins.iData(x)).size(); }",
            "sealed interface S permits A, B { } record A() implements S { } record B() implements S { } static long m(S s, PlutusData d) { var v = switch (s) { case A a -> Builtins.tailList(Builtins.unListData(d)); default -> d; }; return JulcList.of(v).size(); }"})
    void armsOfDifferentRepresentationsAreNotEncoded(String members) {
        var diagnostic = rejected(members);
        assertEquals("JULC0012", diagnostic.code(), diagnostic::message);
        assertTrue(diagnostic.message().contains("whose arms have different types"), diagnostic::message);
    }

    // --- equals between types with no class in common (JULC0078, JavacCallsTest), and its remedy ---

    /** The external UVerifyProxy.java:70 writes TxId.equals(byte[]); a typed comparison is its remedy, and lowers. */
    @Test
    void aTypedComparisonReplacesIt() {
        assertEquals("JULC0078", rejected("static boolean m(org.julclang.ledger.TxId a, byte[] b) { return a.equals(b); }").code());
        compiler().compileMethod(source(
                "static boolean m(org.julclang.ledger.TxId a, byte[] b) { return a.equals(new org.julclang.ledger.TxId(b)); }"), "m", List.of());
    }

    // --- Coded rejections where the reference rejects ---

    @Test
    void aStaticCallThroughAQualifiedClassName() {
        var diagnostic = rejected("static BigInteger m() { return java.math.BigInteger.valueOf(1); }");
        assertEquals("JULC0123", diagnostic.code());
        assertTrue(diagnostic.message().contains("java.math.BigInteger.valueOf"), diagnostic::message);
        assertEquals("JULC0123", rejected("static boolean m(PlutusData r) { return org.julclang.stdlib.lib.MapLib.member("
                + "PlutusData.cast(r, JulcMap.class), Builtins.iData(2)); }").code());
        // A nested class's qualified name: the reference reads the outer name as a variable (JULC0011), as here (review F8).
        assertEquals("JULC0011", rejected("static class H { static BigInteger f(BigInteger a) { return a; } } static BigInteger m(BigInteger a) { return C.H.f(a); }").code());
        // The reference recognises JulcMap.of by either name.
        assertEquals("JULC0062", rejected("static boolean m(byte[] a) { var map = org.julclang.core.types.JulcMap.of(a, BigInteger.ONE); "
                + "return map.containsKey(a); }").code());
    }

    @Test
    void aCharHasNoRepresentation() {
        var diagnostic = rejected("static char m(BigInteger a) { char c = 'x'; return c; }");
        assertEquals("JULC0124", diagnostic.code());
        assertEquals("Unsupported primitive type: char. Supported types: boolean, int, long", diagnostic.message());
        // A char literal elsewhere: the reference's words for it, "Unsupported expression: CharLiteralExpr" (review F7).
        var literal = rejected("static long m(BigInteger a) { long c = 'x'; return c; }");
        assertEquals("JULC0125", literal.code());
        assertEquals("Unsupported expression: CharLiteralExpr", literal.message());
        assertEquals("JULC0124", rejected("static long m(long a) { long q = (char) a; return q; }").code());
        assertEquals("JULC0124", rejected("static long m(short a) { return 1; }").code());
    }

    @Test
    void aVariantOfTwoSealedInterfacesIsRejectedWhereverItIsRead() {
        var diagnostic = rejected("sealed interface S permits B, A { } sealed interface T permits B, D { } record A(BigInteger v) implements S { }"
                + " record B(BigInteger v) implements S, T { } record D(BigInteger v) implements T { }"
                + " static boolean m(BigInteger a) { S s = new B(a); return s instanceof B; }");
        assertEquals("JULC0079", diagnostic.code(), diagnostic::message);
    }

    // --- I2a: the last uncoded rejections the head corpus reaches (part 3) ---

    /** A type parameter nothing specializes is read by no rule but its name on the reference (D15): it has JULC0012 here. */
    @Test
    void aTypeParameterNothingBindsHasACode() {
        var diagnostic = rejected("static <Boolean> Boolean bad(long x) { return (Boolean) (Object) x; } static long m() { return 1; }");
        assertEquals("JULC0012", diagnostic.code(), diagnostic::message);
        assertEquals("Cannot resolve type: type parameter Boolean", diagnostic.message());
    }

    @Test
    void aVarargsComponentIsAnArrayWithItsCode() {
        var diagnostic = rejected("record R(BigInteger... a) { } static long m() { return 1; }");
        assertEquals("JULC0014", diagnostic.code(), diagnostic::message);
        assertEquals("arrays are not supported on-chain", diagnostic.message());
    }

    @Test
    void aClassIsNoValueToConstruct() {
        var diagnostic = rejected("static class Foo { Foo(long a) { } long a() { return 9; } } static long m() { Foo f = new Foo(5); return f.a(); }");
        assertEquals("JULC0129", diagnostic.code(), diagnostic::message);
        assertEquals("Cannot construct non-record type: Foo", diagnostic.message());
        assertEquals("Only record types can be constructed on-chain. Declare the type as a record.", diagnostic.suggestion());
    }

    // --- The second review of M5a (F3, F6) ---

    /** A text block is the reference's words: "Unsupported expression: TextBlockLiteralExpr". */
    @Test
    void aTextBlockIsTheReferencesWords() {
        var diagnostic = rejected("static String m(BigInteger a) { return \"\"\"\n  hi\"\"\"; }");
        assertEquals("JULC0125", diagnostic.code(), diagnostic::message);
        assertEquals("Unsupported expression: TextBlockLiteralExpr", diagnostic.message());
    }

    /**
     * A variant's equals of a choice of two variants in an {@code Object} argument: javac types it {@code Object}, and the
     * arms keep their two types, so it stays the mixed-arm rule's, in words that name them.
     */
    @Test
    void aChoiceOfTwoVariantsAsAnObjectArgumentNamesBoth() {
        var diagnostic = rejected("sealed interface S permits A, B { } record A(BigInteger v) implements S { } record B(BigInteger v) implements S { }"
                + " static boolean m(BigInteger a, boolean c) { return new A(a).equals(c ? new A(a) : new B(a)); }");
        assertEquals("JULC0012", diagnostic.code(), diagnostic::message);
        assertTrue(diagnostic.message().contains("arms have different types, C.A and C.B"), diagnostic.message());
    }

    /** A narrowing cast of a literal is its Java value; of anything else it is the type's JULC0124. */
    @Test
    void aNarrowingOfALiteralIsJavasValue() throws Exception {
        assertEquals("JULC0124", rejected("static long m(long a) { long q = (char) (a + 1); return q; }").code());
        compiler().compileMethod(source("static long m(long a) { long q = (char) 97; return q; }"), "m", List.of());
    }

    /**
     * A conditional of two narrowed literals is an expression of type {@code char} that is no cast, so the type's rule in
     * {@code Typing.of} gives it {@code JULC0124}, where the reference compiles it to 97 or 98 (M5a confirmation review, F4:
     * without the rule it is {@code JULC0012}).
     */
    @Test
    void aCharOfNoCastIsTheTypesRejection() {
        var diagnostic = rejected("public static long m(boolean c) { return c ? (char) 97 : (char) 98; }");
        assertEquals("JULC0124", diagnostic.code(), diagnostic::message);
        assertTrue(diagnostic.message().contains("Unsupported primitive type: char"), diagnostic.message());
    }

    /**
     * {@code PlutusData} is no sum of records for the mixed-arm rule: a choice of {@code PlutusData} and {@code ConstrData} is
     * two Java types, {@code JULC0012}, although both arms are Data here (M32 of the second review, fail closed).
     */
    @Test
    void plutusDataIsNoSumOfItsViews() {
        var diagnostic = rejected("static boolean m(BigInteger a, boolean c) { PlutusData x = c ? Builtins.iData(a) : Builtins.constrData(0, Builtins.mkNilData()); return Builtins.equalsData(x, Builtins.iData(a)); }");
        assertEquals("JULC0012", diagnostic.code(), diagnostic::message);
        assertTrue(diagnostic.message().contains("arms have different types"), diagnostic.message());
    }

    /** ADR-068 r19: Java narrows {@code int += long} without saying so and the on-chain integer does not, so the form is rejected, and the fix names {@code long}. */
    @ParameterizedTest
    @ValueSource(strings = {"+=", "-=", "*=", "/=", "%="})
    void aCompoundAssignmentOfALongToAnIntIsRejected(String operator) {
        var diagnostic = rejected("static long m(JulcList<BigInteger> xs) { int x = 5; for (var y : xs) { x " + operator + " y.longValue(); } return x; }");
        assertEquals("JULC0130", diagnostic.code());
        assertTrue(diagnostic.suggestion().contains("long total = 0"), diagnostic.suggestion());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "static long m(JulcList<BigInteger> xs) { int x = 5; for (var y : xs) { x += 1L; } return x; }",
            // The review's shape: the update gives 5 on the JVM and 4294967301 on-chain at y = 2^32.
            "static long m(long y) { int x = 5; for (long z : JulcList.of(y)) { x += z; } return x; }",
            "static long m(JulcList<BigInteger> xs, boolean c) { int x = 5; for (var y : xs) { if (c) { x += y.longValue(); } } return x; }",
            "static long m(JulcList<BigInteger> xs) { int x = 5; long n = 2; while (n > 0) { x += n; n = n - 1; } return x; }",
    })
    void everyShapeOfTheNarrowingIsRejected(String members) {
        assertEquals("JULC0130", rejected(members).code());
    }

    /** A {@code Long} that javac inferred unboxes to a {@code long} before Java narrows the result, so it hides the same narrowing. */
    @ParameterizedTest
    @ValueSource(strings = {"+=", "-=", "*=", "/=", "%="})
    void anInferredLongOperandIsRejected(String operator) {
        assertEquals("JULC0130", rejected("static long m(long y) { int x = 5; for (var z : JulcList.of(y)) { x " + operator
                + " z; } return x; }").code());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "for (var z : ys) { x += z; }",
            "for (var w : xs) { x += ys.head(); }",
            "for (var w : xs) { var q = ys.head(); x += q; }",
            "for (var w : xs) { x += (ys.head()); }",
            "for (var w : xs) { var q = ys.head(); x += w.signum() > 0 ? q : q; }",
    })
    void everyShapeOfAnInferredLongIsRejected(String loop) {
        assertEquals("JULC0130", rejected("static long m(JulcList<BigInteger> xs, long y) { int x = 5; var ys = JulcList.of(y); "
                + loop + " return x; }").code());
    }

    /** Nothing narrows in these, or the program says so; each gives the JVM's value past 32 bits. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "long x = 5; for (var y : xs) { x += y.longValue(); } return x; | 4294967301",
            "BigInteger x = BigInteger.valueOf(5); for (var y : xs) { x = x.add(y); } return x.longValue(); | 4294967301",
            "int x = 5; for (var y : xs) { x = (int) (x + y.longValue()); } return x; | 5",
            "int x = 5; for (var y : xs) { x += (int) y.longValue(); } return x; | 5",
            "long x = 5; for (var z : JulcList.of(xs.head().longValue())) { x += z; } return x; | 4294967301",
            "int x = 5; for (var z : JulcList.of(xs.head().longValue())) { x = (int) (x + z); } return x; | 5",
            "int x = 5; for (var z : JulcList.of(7)) { x += z; } return x; | 12",
    })
    void whatDoesNotHideANarrowingCompiles(String body, long java) {
        var options = new CompilerOptions().setOptimizationLevel(OptimizationLevel.BASELINE);
        var program = new JulcCompiler(StdlibRegistry.defaultRegistry(), options)
                .compileMethod(source("static long m(JulcList<BigInteger> xs) { " + body + " }"), "m").program();
        var argument = PlutusData.list(PlutusData.integer(4294967296L));
        var result = JulcVm.create().evaluateWithArgs(program, List.of(argument));
        assertEquals(new Term.Const(Constant.integer(BigInteger.valueOf(java))),
                assertInstanceOf(EvalResult.Success.class, result, result::toString).resultTerm(), body);
    }

    /** An update that does not narrow stays as it was. */
    @ParameterizedTest
    @ValueSource(strings = {
            "static long m(JulcList<BigInteger> xs) { long x = 5; for (var y : xs) { x *= 2; } return x; }",
            "static long m(JulcList<BigInteger> xs, int k) { int x = 5; for (var y : xs) { x += k; } return x; }",
    })
    void anUpdateThatDoesNotNarrowCompiles(String members) {
        assertNotNull(compiler().compileMethod(source(members), "m").program());
    }
}
