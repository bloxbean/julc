package org.julclang.compiler;

import org.julclang.core.Constant;
import org.julclang.core.PlutusData;
import org.julclang.core.Term;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.vm.EvalResult;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JULC0091 (ADR-068 D6, #298): a {@code JulcArray} has no Data encoding, so it is rejected in every Data-encoded
 * position where its type is written. Such programs compiled and failed on-chain where the Java code succeeds. Arrays
 * of Data-encodable elements, and arrays held in locals and helper parameters, stay supported.
 */
class NativeArrayDataPositionTest {

    private static String source(String decls, String signature, String body) {
        return """
                import java.math.BigInteger;
                import java.util.List;
                import java.util.Optional;
                import org.julclang.core.types.JulcArray;
                import org.julclang.core.types.JulcList;
                import org.julclang.core.types.JulcMap;
                import org.julclang.core.types.Tuple2;
                import org.julclang.core.types.Tuple3;
                import org.julclang.stdlib.annotation.Param;
                class P {
                %s
                    static %s {
                        %s
                    }
                }
                """.formatted(decls, signature, body);
    }

    private static CompileResult compile(String decls, String signature, String body) {
        return new JulcCompiler(StdlibRegistry.defaultRegistry()).compileMethod(source(decls, signature, body), "m");
    }

    /** Rejected with JULC0091 at {@code line}:{@code column} of the source, the message naming {@code position}. */
    private static void rejected(String position, int line, int column, String decls, String signature, String body) {
        var e = assertThrows(CompilerException.class, () -> compile(decls, signature, body), position);
        var diagnostic = e.diagnostics().stream().filter(d -> "JULC0091".equals(d.code())).findFirst()
                .orElseThrow(() -> new AssertionError(position + ": " + e.diagnostics()));
        assertTrue(diagnostic.message().startsWith(position), diagnostic.message());
        assertTrue(diagnostic.message().contains("native array Array["), diagnostic.message());
        assertEquals(line, diagnostic.line(), position);
        assertEquals(column, diagnostic.column(), position);
    }

    private static final String SIG = "BigInteger m(JulcList<BigInteger> xs)";

    @Test
    void recordAndVariantComponents() {
        rejected("Component 'arr' of record R", 11, 14, "    record R(JulcArray<BigInteger> arr) {}", SIG,
                "return BigInteger.valueOf(new R(xs.toArray()).arr().length());");
        rejected("Component 'arr' of record A", 11, 88,
                "    sealed interface S permits A, B {} record B(BigInteger v) implements S {} record A(JulcArray<BigInteger> arr) implements S {}",
                SIG, "return BigInteger.ONE;");
        rejected("Component 'arr' of record R", 11, 28, "    record R(BigInteger v, JulcArray<BigInteger> arr) {}", SIG,
                "return BigInteger.ONE;");
        // A component is Data-encoded whatever the array holds.
        rejected("Component 'xss' of record R", 11, 14, "    record R(JulcArray<JulcList<BigInteger>> xss) {}", SIG,
                "return BigInteger.ONE;");
    }

    /** A helper's parameter of {@code type}, written at column 25 of line 11. */
    private static String helper(String type) {
        return "    static BigInteger h(" + type + " o) { return BigInteger.ONE; }";
    }

    private static final String ONE = "return BigInteger.ONE;";

    @Test
    void typeArgumentsOfDataEncodedContainers() {
        for (String container : List.of("Optional", "JulcList", "List", "JulcArray")) {
            rejected("A type argument of " + container, 11, 25 + container.length() + 1,
                    helper(container + "<JulcArray<BigInteger>>"), SIG, ONE);
        }
        rejected("A type argument of JulcMap", 11, 33, helper("JulcMap<JulcArray<BigInteger>, BigInteger>"), SIG, ONE);
        rejected("A type argument of JulcMap", 11, 45, helper("JulcMap<BigInteger, JulcArray<BigInteger>>"), SIG, ONE);
        rejected("A type argument of Tuple2", 11, 44, helper("Tuple2<BigInteger, JulcArray<BigInteger>>"), SIG, ONE);
        rejected("A type argument of Tuple3", 11, 56, helper("Tuple3<BigInteger, BigInteger, JulcArray<BigInteger>>"), SIG, ONE);
        // In a local's declared type, too.
        rejected("A type argument of JulcList", 13, 18, "", SIG,
                "JulcList<JulcArray<BigInteger>> o = JulcList.empty(); return BigInteger.ONE;");
    }

    @Test
    void nestedPositionsAreCheckedWhereTheTypeIsWritten() {
        // The innermost container that holds the array is reported.
        rejected("A type argument of Optional", 11, 43, helper("JulcList<Optional<JulcArray<BigInteger>>>"), SIG, ONE);
        rejected("A type argument of JulcList", 11, 32, "    record R(Optional<JulcList<JulcArray<BigInteger>>> o) {}", SIG, ONE);
        rejected("A type argument of JulcArray", 11, 35, helper("JulcArray<JulcArray<BigInteger>>"), SIG, ONE);
    }

    /**
     * JULC0091 where lowering Data-encodes a value whose type is inferred (F601, F700): reported at the
     * argument whose value would be encoded, on line 13 at the column where {@code argument} starts in
     * {@code body}. Each of these compiled before and failed on-chain (MkCons or MkPairData) where the JVM
     * returns normally.
     */
    private static void encodedAt(String position, String signature, String body, String argument) {
        rejected(position, 13, 9 + body.indexOf(argument), "", signature, body);
    }

    private static final String LONG_SIG = "long m(JulcList<BigInteger> xs)";

    @Test
    void inferredValuesAreCheckedWhereTheyAreEncoded() {
        encodedAt("The value of 'xs.toArray()'", LONG_SIG, "return JulcList.of(xs.toArray()).size();", "xs.toArray()");
        encodedAt("The value of 'a'", LONG_SIG,
                "var a = JulcArray.of(BigInteger.ONE); var ys = JulcList.<JulcArray<BigInteger>>of(a); return ys.size();", "a)");
        encodedAt("The value of 'a'", LONG_SIG, "var a = xs.toArray(); var b = JulcArray.of(a); return b.length();", "a)");
        encodedAt("The value of 'xs.toArray()'", LONG_SIG, "var o = Optional.of(xs.toArray()); return 1;", "xs.toArray()");
        encodedAt("The value of 'a'", LONG_SIG,
                "var a = xs.toArray(); var m2 = JulcMap.<JulcArray<BigInteger>, BigInteger>empty().insert(a, BigInteger.ONE); return m2.size();",
                "a, BigInteger.ONE)");
        encodedAt("The value of 'a'", LONG_SIG,
                "var a = xs.toArray(); var m2 = JulcMap.<BigInteger, JulcArray<BigInteger>>empty().insert(BigInteger.ONE, a); return m2.size();",
                "a);");
        encodedAt("Argument 'a' of new Tuple2", LONG_SIG,
                "var a = xs.toArray(); var t = new Tuple2<>(a, BigInteger.ONE); BigInteger s = t.second(); return s.longValue();",
                "a, BigInteger.ONE)");
        encodedAt("The value of 'a'", LONG_SIG,
                "var a = xs.toArray(); var ys = JulcList.<JulcArray<BigInteger>>empty().prepend(a); return ys.size();", "a);");
        // A lambda's result is Data-encoded by map: reported at the lambda body.
        encodedAt("The value encoded here", LONG_SIG, "var ys = xs.map(x -> JulcArray.of(x)); return ys.size();",
                "JulcArray.of(x)");
    }

    /** Review F710: a switch expression keeps the type its arms agree on, so its array is checked where encoded. */
    @Test
    void switchResultsAreCheckedWhereTheyAreEncoded() {
        String sealed = "    sealed interface S permits A, B {} record A(BigInteger n) implements S {} record B(BigInteger n) implements S {}";
        String body = "S s = new A(BigInteger.ONE); var arr = switch (s) { case A a -> JulcArray.of(a.n()); case B b -> JulcArray.of(b.n()); };"
                + " return JulcList.of(arr).size();";
        rejected("The value of 'arr'", 13, 9 + body.indexOf("arr).size()"), sealed, LONG_SIG, body);
        String lambda = "S s = new A(BigInteger.ONE); var ys = xs.map(v -> switch (s) { case A a -> JulcArray.of(v); case B b -> JulcArray.of(v); });"
                + " return ys.size();";
        rejected("The value encoded here", 13, 9 + lambda.indexOf("switch"), sealed, LONG_SIG, lambda);
    }

    @Test
    void inferredArraysOfDataEncodableElementsCompile() {
        // The positive controls of the encoding check: arrays in locals and of lists, and lists of lists.
        assertFalse(compile("", "long m(JulcList<JulcList<BigInteger>> xss)",
                "var arr = xss.toArray(); return arr.length();").hasErrors());
        assertFalse(compile("", LONG_SIG, "var a = xs.toArray(); return a.length();").hasErrors());
        assertFalse(compile("", LONG_SIG, "var ys = JulcList.of(xs, xs); return ys.size();").hasErrors());
        assertFalse(compile("", LONG_SIG, "var a = xs.toArray(); var ys = xs.map(x -> a.get(0)); return ys.size();").hasErrors());
    }

    @Test
    void boundaryInputs() {
        rejected("Parameter 'arr' of the compiled method", 12, 25, "", "BigInteger m(JulcArray<BigInteger> arr)",
                "return BigInteger.valueOf(arr.length());");
        rejected("@Param field 'limits'", 11, 19, "    @Param static JulcArray<BigInteger> limits;", SIG,
                "return BigInteger.valueOf(limits.length());");
    }

    @Test
    void arraysOfDataEncodableElementsAndArraysOutsideDataPositionsCompile() {
        // A JulcArray of lists, in a local and as the compiled method's array-free input; a helper taking an array.
        var result = compile("    static BigInteger len(JulcArray<BigInteger> a) { return BigInteger.valueOf(a.length()); }",
                "BigInteger m(JulcList<JulcList<BigInteger>> xss)",
                "JulcArray<JulcList<BigInteger>> arr = xss.toArray(); JulcArray<BigInteger> first = arr.get(0).toArray();"
                        + " return BigInteger.valueOf(arr.length()).add(len(first));");
        assertFalse(result.hasErrors());
        var vm = CompilerTestVm.pv11("Java");
        var xss = PlutusData.list(PlutusData.list(PlutusData.integer(1), PlutusData.integer(2), PlutusData.integer(3)),
                PlutusData.list());
        var eval = assertInstanceOf(EvalResult.Success.class, vm.evaluateWithArgs(result.program(), List.of(xss)));
        assertEquals(new Term.Const(Constant.integer(BigInteger.valueOf(5))), eval.resultTerm());
    }
}
