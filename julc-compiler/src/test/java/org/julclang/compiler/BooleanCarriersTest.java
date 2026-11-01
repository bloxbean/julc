package org.julclang.compiler;

import org.julclang.core.PlutusData;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.vm.EvalResult;
import org.julclang.vm.JulcVm;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What holds a {@code Boolean} and is reached through a cast from a value of another type. The boolean is read out of Data,
 * so the read fails on what is not one, whichever way the program was compiled, on each input. A generic helper's type
 * variable does not compile.
 */
class BooleanCarriersTest {

    private static final String IMPORTS = """
            import java.math.BigInteger;
            import java.util.Optional;
            import org.julclang.core.PlutusData;
            import org.julclang.core.types.JulcList;
            import org.julclang.core.types.JulcMap;
            import org.julclang.core.types.Tuple2;
            import org.julclang.stdlib.Builtins;
            """;

    private static JulcVm vm;

    @BeforeAll
    static void setUp() {
        vm = CompilerTestVm.pv11();
    }

    private static String method(String body) {
        return IMPORTS + """
                class P {
                    record Q(BigInteger a) { }
                    record F(boolean flag) { }
                    record G(Boolean flag) { }
                    static boolean m(BigInteger r) { %s }
                }
                """.formatted(body);
    }

    private static CompileResult compile(String source, OptimizationLevel level) {
        return new JulcCompiler(StdlibRegistry.defaultRegistry(), new CompilerOptions().setOptimizationLevel(level)).compileMethod(source, "m");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "JulcList<BigInteger> xs = JulcList.of(r); JulcList<Boolean> l = (JulcList<Boolean>) (Object) xs; return l.head();",
            "JulcList<BigInteger> xs = JulcList.of(r); JulcList<Boolean> l = (JulcList<Boolean>) (Object) xs; return l.get(0);",
            "JulcList<Boolean> l = (JulcList<Boolean>) (Object) r; return l.head();",
            "Optional<BigInteger> o = Optional.of(r); Optional<Boolean> p = (Optional<Boolean>) (Object) o; return p.get();",
            "Optional<Boolean> p = (Optional<Boolean>) (Object) r; return p.get();",
            "JulcMap<BigInteger, Boolean> n = (JulcMap<BigInteger, Boolean>) (Object) r; return n.get(r);",
            "Tuple2<BigInteger, BigInteger> t = new Tuple2<>(r, r); Tuple2<Boolean, BigInteger> u = (Tuple2<Boolean, BigInteger>) (Object) t; return u.first();",
            "Tuple2<Boolean, BigInteger> u = (Tuple2<Boolean, BigInteger>) (Object) r; return u.first();",
            "F x = (F) (Object) new Q(r); return x.flag();",
            "G x = (G) (Object) new Q(r); return x.flag();",
            "F x = (F) (Object) r; return x.flag();"})
    void aBooleanInACarrierFailsOnAnotherValue(String body) {
        for (var level : OptimizationLevel.values()) {
            var program = compile(method(body), level).program();
            for (long r : List.of(0L, 1L, 2L)) {
                var result = vm.evaluateWithArgs(program, List.of(PlutusData.integer(r)));
                assertInstanceOf(EvalResult.Failure.class, result, () -> level + " " + body);
                assertFalse(result.isSuccess());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "static <T> T f(BigInteger x) { return (T) (Object) x; } static boolean m(BigInteger r) { boolean b = f(r); return b; }",
            "static <T> T f(T x) { return x; } static boolean m(BigInteger r) { boolean b = (Boolean) f((Object) true); return b; }"})
    void aHelpersTypeVariableDoesNotCompile(String members) {
        var source = IMPORTS + "class P { %s }".formatted(members);
        for (var level : OptimizationLevel.values()) {
            assertThrows(CompilerException.class, () -> compile(source, level), level::name);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "JulcList<Boolean> l = JulcList.of(r.signum() > 0); boolean first = l.head(); return first;",
            "Optional<Boolean> p = Optional.of(r.signum() > 0); return p.get();"})
    void aCarrierOfABooleanReadsIt(String body) {
        for (var level : OptimizationLevel.values()) {
            var result = vm.evaluateWithArgs(compile(method(body), level).program(), List.of(PlutusData.integer(1)));
            assertTrue(result.isSuccess(), () -> level + " " + result);
        }
    }
}
