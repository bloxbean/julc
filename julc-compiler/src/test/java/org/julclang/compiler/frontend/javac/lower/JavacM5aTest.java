package org.julclang.compiler.frontend.javac.lower;

import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerFrontend;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.error.CompilerDiagnostic;
import org.julclang.compiler.frontend.javac.NotPorted;
import org.julclang.core.flat.UplcFlatEncoder;
import org.julclang.stdlib.StdlibRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    private static JulcCompiler compiler(CompilerFrontend frontend) {
        return new JulcCompiler(StdlibRegistry.defaultRegistry(), new CompilerOptions().setFrontend(frontend));
    }

    private static CompilerException javac(String members) {
        return assertThrows(CompilerException.class, () -> compiler(CompilerFrontend.JAVAC).compileMethod(source(members), "m", List.of()));
    }

    private static CompilerDiagnostic rejected(String members) {
        var failure = javac(members);
        assertFalse(failure instanceof NotPorted, failure::getMessage);
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
        assertArrayEquals(bytes(CompilerFrontend.JAVAPARSER, members), bytes(CompilerFrontend.JAVAC, members));
    }

    private static byte[] bytes(CompilerFrontend frontend, String members) {
        return UplcFlatEncoder.encodeProgram(compiler(frontend).compileMethod(source(members), "m", List.of()).program());
    }

    // --- Arms of a conditional or a switch javac types Object, Record or an intersection ---

    @ParameterizedTest
    @ValueSource(strings = {
            "sealed interface S permits A, B { } record A() implements S { } record B() implements S { } static boolean m(S s) { return s.equals(switch (s) { case A a -> a; default -> s; }); }",
            "sealed interface S { record A() implements S { } } static boolean m(boolean c, S a, S.A b) { return a.equals(c ? a : b); }",
            "sealed interface S { record A(BigInteger v) implements S { } record B() implements S { } } static boolean m(boolean c, BigInteger a) { var t = new Tuple2<>(c ? new S.A(a) : new S.B(), a); return true; }"})
    void armsOfOneRepresentationLower(String members) {
        compiler(CompilerFrontend.JAVAC).compileMethod(source(members), "m", List.of());
    }

    /**
     * Arms of different representations, one not Data, have no type: the reference says so of a {@code switch} when it is
     * encoded ({@code JULC0012}), and so does a conditional here, whose else branch the reference reads as its then branch.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "sealed interface S permits A, B { } record A(BigInteger n) implements S { } record B(BigInteger n) implements S { } static PlutusData d(BigInteger v) { return Builtins.iData(v); }\n"
                    + "static long m(BigInteger x) { S s = new A(x); var v = switch (s) { case A a -> a.n(); case B b -> d(b.n()); }; return JulcList.of(v).size(); }",
            "static long m(boolean c, BigInteger a) { return JulcList.of(c ? a : Builtins.iData(a)).size(); }"})
    void armsOfDifferentRepresentationsAreNotEncoded(String members) {
        var diagnostic = rejected(members);
        assertEquals("JULC0012", diagnostic.code(), diagnostic::message);
        assertTrue(diagnostic.message().contains("arms have different types"), diagnostic::message);
    }

    // --- equals between types with no class in common (JULC0078, JavacCallsTest), and its remedy ---

    /** The external UVerifyProxy.java:70 writes TxId.equals(byte[]); a typed comparison is its remedy, and lowers. */
    @Test
    void aTypedComparisonReplacesIt() {
        assertEquals("JULC0078", rejected("static boolean m(org.julclang.ledger.TxId a, byte[] b) { return a.equals(b); }").code());
        compiler(CompilerFrontend.JAVAC).compileMethod(source(
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
        // The reference recognises JulcMap.of by either name.
        assertEquals("JULC0062", rejected("static boolean m(byte[] a) { var map = org.julclang.core.types.JulcMap.of(a, BigInteger.ONE); "
                + "return map.containsKey(a); }").code());
    }

    @Test
    void aCharHasNoRepresentation() {
        var diagnostic = rejected("static char m(BigInteger a) { char c = 'x'; return c; }");
        assertEquals("JULC0124", diagnostic.code());
        assertEquals("Unsupported primitive type: char. Supported types: boolean, int, long", diagnostic.message());
        assertEquals("JULC0124", rejected("static long m(BigInteger a) { long c = 'x'; return c; }").code());
        assertEquals("JULC0124", rejected("static long m(short a) { return 1; }").code());
    }

    @Test
    void aVariantOfTwoSealedInterfacesIsRejectedWhereverItIsRead() {
        var diagnostic = rejected("sealed interface S permits B, A { } sealed interface T permits B, D { } record A(BigInteger v) implements S { }"
                + " record B(BigInteger v) implements S, T { } record D(BigInteger v) implements T { }"
                + " static boolean m(BigInteger a) { S s = new B(a); return s instanceof B; }");
        assertEquals("JULC0079", diagnostic.code(), diagnostic::message);
    }
}
