package org.julclang.compiler;

import org.julclang.compiler.frontend.javac.JavacGateMode;
import org.julclang.compiler.uplc.UplcGenerator;
import org.julclang.core.PlutusData;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.vm.EvalResult;
import org.julclang.vm.ExBudget;
import org.julclang.vm.JulcVm;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigInteger;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A value that is not a real boolean, at a boolean position, fails at every optimization level, on every
 * route that puts one there. The javac gate is off here, since what is tested is the lowering of the
 * condition, not a rule in front of it: a source rule stops some of these shapes and not others.
 */
class BooleanPositionLevelsTest {

    private static final JulcVm VM = CompilerTestVm.pv11("Java");
    private static final ExBudget BUDGET = new ExBudget(10_000_000_000L, 14_000_000L);

    private static final String IMPORTS = """
            import java.math.BigInteger;
            import org.julclang.core.PlutusData;
            import org.julclang.core.types.*;
            import org.julclang.stdlib.Builtins;
            import org.julclang.stdlib.annotation.*;
            """;

    private record Shape(String name, PlutusData redeemer, String body, List<String> libraries) {
        Shape(String name, PlutusData redeemer, String body) {
            this(name, redeemer, body, List.of());
        }

        String source() {
            String library = libraries.isEmpty() ? "" : "import lib.L;\n";
            return IMPORTS + library + "@SpendingValidator\npublic class V {\n" + body + "\n}\n";
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private static PlutusData integer(long value) {
        return new PlutusData.IntData(BigInteger.valueOf(value));
    }

    private static PlutusData constr(int tag, PlutusData... fields) {
        return new PlutusData.ConstrData(tag, List.of(fields));
    }

    private static final String ENTRY_INT = "@Entrypoint public static boolean v(BigInteger r, PlutusData c) ";
    private static final String ENTRY_DATA = "@Entrypoint public static boolean v(PlutusData r, PlutusData c) ";

    static Stream<Shape> nonBooleans() {
        return Stream.of(
                // Casts, directly and through a local, a helper's parameter and a helper's result.
                new Shape("cast of a Data local", integer(1), ENTRY_INT + """
                        { PlutusData d = (PlutusData)(Object) r; return (boolean)(Object) d; }"""),
                new Shape("cast at an if", integer(0), ENTRY_INT + """
                        { PlutusData d = (PlutusData)(Object) r; if ((boolean)(Object) d) return false; return true; }"""),
                new Shape("cast of a helper's parameter", integer(1), """
                        static boolean f(PlutusData d) { return (boolean)(Object) d; }
                        """ + ENTRY_INT + "{ return f((PlutusData)(Object) r); }"),
                new Shape("cast of a helper's result", integer(1), """
                        static PlutusData o(BigInteger r) { return (PlutusData)(Object) r; }
                        """ + ENTRY_INT + "{ return (boolean)(Object) o(r); }"),
                new Shape("cast through a ternary", integer(1), ENTRY_INT + """
                        { return (boolean)(r.signum() > 0 ? (Object) r : (Object) r); }"""),
                new Shape("cast through another type", integer(1), ENTRY_DATA + """
                        { return (boolean)(Object)(BigInteger)(Object) r; }"""),
                new Shape("a Data subtype", integer(1),
                        "@Entrypoint public static boolean v(PlutusData.IntData r, PlutusData c) "
                                + "{ return (boolean)(Object) r; }"),
                new Shape("a Data subtype through a local", integer(1), ENTRY_INT + """
                        { PlutusData.IntData d = (PlutusData.IntData)(Object) r; return (Boolean)(Object) d; }"""),
                new Shape("a user type named PlutusData", integer(1), """
                        record PlutusData(BigInteger x) {}
                        @Entrypoint public static boolean v(BigInteger r, org.julclang.core.PlutusData c)
                        { return (boolean)(Object) new PlutusData(r); }"""),
                // The cast API.
                new Shape("cast API, integer 1", integer(1), ENTRY_INT + "{ return PlutusData.cast(r, Boolean.class); }"),
                new Shape("cast API at an if", integer(0), ENTRY_INT + """
                        { if (PlutusData.cast(r, Boolean.class)) return false; return true; }"""),
                new Shape("cast API, integer 2", integer(2), ENTRY_INT + "{ return PlutusData.cast(r, Boolean.class); }"),
                new Shape("cast API negated", integer(0), ENTRY_INT + """
                        { boolean b = PlutusData.cast(r, Boolean.class); return !b; }"""),
                new Shape("cast API, primitive token", integer(1), ENTRY_INT + "{ return PlutusData.cast(r, boolean.class); }"),
                new Shape("cast API in a library", integer(1), ENTRY_INT + "{ return L.t(r); }", List.of("""
                        package lib;
                        import java.math.BigInteger;
                        import org.julclang.core.PlutusData;
                        import org.julclang.stdlib.annotation.*;
                        @OnchainLibrary
                        public class L {
                            public static boolean t(BigInteger r) { return PlutusData.cast(r, Boolean.class); }
                        }
                        """)),
                // Values of other types that a Case selects on: an integer tag, an empty and a non-empty list.
                new Shape("a constructor tag", constr(1), ENTRY_DATA + """
                        { return (boolean)(Object) Builtins.fstPair(Builtins.unConstrData(r)); }"""),
                new Shape("a constructor tag by the cast API", constr(1), ENTRY_DATA + """
                        { return PlutusData.cast(Builtins.constrTag(r), Boolean.class); }"""),
                new Shape("an empty field list", constr(0), ENTRY_DATA + """
                        { return (boolean)(Object) Builtins.sndPair(Builtins.unConstrData(r)); }"""),
                new Shape("a non-empty field list", constr(0, integer(7)), ENTRY_DATA + """
                        { return (boolean)(Object) Builtins.sndPair(Builtins.unConstrData(r)); }"""),
                new Shape("an empty list through a Data local", constr(0), ENTRY_DATA + """
                        { JulcList<BigInteger> l = JulcList.empty(); PlutusData d = (PlutusData)(Object) l;
                          return (boolean)(Object) d; }"""),
                new Shape("Data holding a constructor", constr(1), ENTRY_DATA + "{ return (boolean)(Object) r; }"),
                // Names and generics that bind to something else.
                new Shape("a user type named Boolean", integer(1), """
                        @NewType record Boolean(BigInteger x) {}
                        """ + ENTRY_INT + """
                        { Boolean a = new Boolean(r); Boolean b = new Boolean(BigInteger.ONE); return a == b; }"""),
                new Shape("a static named fromPlutusData", integer(1), """
                        record R(BigInteger a) { static boolean fromPlutusData(long x) { return false; } }
                        """ + ENTRY_INT + "{ if (R.fromPlutusData(1)) { return true; } return r.signum() < 0; }"),
                new Shape("a static named valueOf on a type named BigInteger", integer(1), """
                        record BigInteger(long a) { static boolean valueOf(long x) { return false; } }
                        """ + ENTRY_DATA + "{ if (BigInteger.valueOf(1)) { return true; } return false; }"),
                new Shape("a static named of on a new type", integer(1), """
                        @NewType record N(boolean value) { static boolean of(long x) { return false; } }
                        """ + ENTRY_INT + "{ if (N.of(1)) { return true; } return false; }"),
                new Shape("a type variable named Boolean", integer(1), """
                        static <Boolean> Boolean bad(long x) { return (Boolean) (Object) x; }
                        """ + ENTRY_INT + "{ java.lang.Boolean b = bad(1); if (b) { return true; } return false; }"));
    }

    /** Valid Java that returns true on the JVM, whose element the lowering leaves as Data. */
    static Stream<Shape> undecodedElements() {
        return Stream.of(
                new Shape("a list head", integer(1), ENTRY_INT + """
                        { var xs = JulcList.of(true); if (xs.head()) { return true; } return false; }"""),
                new Shape("a found element", integer(1), ENTRY_INT + """
                        { JulcList<Boolean> xs = JulcList.of(true); if (xs.find(b -> b)) { return true; } return false; }"""),
                new Shape("an explicit lambda parameter", integer(1), ENTRY_INT + """
                        { JulcList<Boolean> xs = JulcList.of(true); return xs.any((Boolean b) -> b); }"""));
    }

    private static CompileResult compile(Shape shape, OptimizationLevel level, boolean sourceMap) {
        return new JulcCompiler(StdlibRegistry.defaultRegistry(), new CompilerOptions()
                .setOptimizationLevel(level).setSourceMapEnabled(sourceMap).setJavacGate(JavacGateMode.OFF))
                .compile(shape.source(), shape.libraries());
    }

    private static PlutusData scriptContext(PlutusData redeemer) {
        var txOutRef = constr(0, new PlutusData.BytesData(new byte[32]), integer(0));
        return constr(0, constr(0), redeemer, constr(1, txOutRef, constr(1)));
    }

    private static EvalResult evaluate(Shape shape, OptimizationLevel level, boolean sourceMap) {
        return VM.evaluateWithArgs(compile(shape, level, sourceMap).program(), List.of(scriptContext(shape.redeemer())), BUDGET);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nonBooleans")
    void aValueThatIsNotABooleanFailsAtEveryLevel(Shape shape) {
        for (var level : OptimizationLevel.values()) {
            for (boolean sourceMap : List.of(false, true)) {
                var result = evaluate(shape, level, sourceMap);
                assertInstanceOf(EvalResult.Failure.class, result, () -> level + " sourceMap=" + sourceMap);
            }
        }
    }

    /** A follow-up of the javac slices: this flips when they decode the element. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("undecodedElements")
    void anElementLeftAsDataFailsAtEveryLevel(Shape shape) {
        aValueThatIsNotABooleanFailsAtEveryLevel(shape);
    }

    /** The same programs, given real booleans, take the same path at every level. */
    @Test
    void realBooleansKeepTheirMeaningAtEveryLevel() {
        var decoded = "@Entrypoint public static boolean v(boolean r, PlutusData c) { if (r) { return true; } return false; }";
        var helper = """
                static boolean f(boolean b) { if (b) { return true; } return false; }
                @Entrypoint public static boolean v(boolean r, PlutusData c) { return f(r); }""";
        var data = """
                record Flag(boolean on) {}
                @Entrypoint public static boolean v(Flag r, PlutusData c) { if (r.on()) { return true; } return false; }""";
        var loop = """
                @Entrypoint public static boolean v(JulcList<BigInteger> r, PlutusData c) {
                    boolean seen = false;
                    for (BigInteger x : r) { if (x.signum() > 0) { seen = true; } }
                    return seen;
                }""";
        for (var level : OptimizationLevel.values()) {
            assertTrue(evaluate(new Shape("decoded", constr(1), decoded), level, false).isSuccess(), level.name());
            assertFalse(evaluate(new Shape("decoded", constr(0), decoded), level, false).isSuccess(), level.name());
            assertTrue(evaluate(new Shape("helper", constr(1), helper), level, false).isSuccess(), level.name());
            assertFalse(evaluate(new Shape("helper", constr(0), helper), level, false).isSuccess(), level.name());
            assertTrue(evaluate(new Shape("field", constr(0, constr(1)), data), level, false).isSuccess(), level.name());
            assertFalse(evaluate(new Shape("field", constr(0, constr(0)), data), level, false).isSuccess(), level.name());
            var one = new PlutusData.ListData(List.of(integer(1)));
            var none = new PlutusData.ListData(List.of(integer(0)));
            assertTrue(evaluate(new Shape("loop", one, loop), level, false).isSuccess(), level.name());
            assertFalse(evaluate(new Shape("loop", none, loop), level, false).isSuccess(), level.name());
        }
    }

    @Test
    void theRuleIsRecordedOnlyWhenACaseIsEmitted() {
        var proven = """
                @Entrypoint public static boolean v(BigInteger r, PlutusData c) {
                    if (r.signum() > 0) { return true; }
                    return false;
                }""";
        var compiled = compile(new Shape("proven", integer(1), proven), OptimizationLevel.PV11_SAFE, false);
        assertTrue(compiled.optimizationReport().appliedRules().contains(UplcGenerator.PV11_CASE_BOOL_RULE));
        var unproven = new JulcCompiler(StdlibRegistry.defaultRegistry(), new CompilerOptions()
                .setOptimizationLevel(OptimizationLevel.PV11_SAFE).setJavacGate(JavacGateMode.OFF))
                .compileMethod("""
                        import java.math.BigInteger;
                        class V { static BigInteger m(boolean b, BigInteger x, BigInteger y) { return b ? x : y; } }
                        """, "m");
        assertEquals(0, unproven.optimizationReport().appliedRules().stream()
                .filter(UplcGenerator.PV11_CASE_BOOL_RULE::equals).count());
    }
}
