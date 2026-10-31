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
                "JulcList<JulcArray<BigInteger>> o = JulcList.of(xs.toArray()); return BigInteger.ONE;");
    }

    @Test
    void nestedPositionsAreCheckedWhereTheTypeIsWritten() {
        // The innermost container that holds the array is reported.
        rejected("A type argument of Optional", 11, 43, helper("JulcList<Optional<JulcArray<BigInteger>>>"), SIG, ONE);
        rejected("A type argument of JulcList", 11, 32, "    record R(Optional<JulcList<JulcArray<BigInteger>>> o) {}", SIG, ONE);
        rejected("A type argument of JulcArray", 11, 35, helper("JulcArray<JulcArray<BigInteger>>"), SIG, ONE);
    }

    @Test
    void boundaryInputs() {
        rejected("Parameter 'arr' of the compiled method", 12, 25, "", "BigInteger m(JulcArray<BigInteger> arr)",
                "return BigInteger.valueOf(arr.length());");
        rejected("@Param field 'limits'", 11, 12, "    @Param JulcArray<BigInteger> limits;", SIG,
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
