package org.julclang.compiler;

import org.julclang.core.Constant;
import org.julclang.core.PlutusData;
import org.julclang.core.Program;
import org.julclang.core.Term;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.vm.EvalResult;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-060: a source name can only resolve to the declaration Java binds it to. Each case names a
 * user variable, parameter or method exactly like a binder that the switch, loop, validator-wrapper
 * or parameter lowering used to generate, and checks Java's outcome on the VM at every level.
 * Before ADR-060 each of these read the compiler's binder instead (the constructor tag, the
 * remaining list, the spent datum, ...) or failed at run time.
 */
class GeneratedNameCaptureTest {
    private static final List<OptimizationLevel> LEVELS = List.of(
            OptimizationLevel.NONE, OptimizationLevel.BASELINE, OptimizationLevel.PV11_SAFE, OptimizationLevel.PV11_COSTED);

    private static JulcCompiler compiler(OptimizationLevel level) {
        return new JulcCompiler(StdlibRegistry.defaultRegistry(), new CompilerOptions().setOptimizationLevel(level));
    }

    private static boolean accepts(Program program, PlutusData... args) {
        return CompilerTestVm.pv11().evaluateWithArgs(program, List.of(args)).isSuccess();
    }

    private static PlutusData context(PlutusData redeemer) {
        return PlutusData.constr(0, PlutusData.integer(0), redeemer, PlutusData.integer(0));
    }

    private static PlutusData spendingContext(PlutusData redeemer, PlutusData datum) {
        return PlutusData.constr(0, PlutusData.integer(0), redeemer,
                PlutusData.constr(1, PlutusData.integer(0), PlutusData.constr(0, datum)));
    }

    // --- switch dispatch: __match_data / __match_pair / __match_tag / __match_fields / __rest_N ---

    private static final String SWITCH = """
            import java.math.BigInteger;
            @SpendingValidator
            class Dispatch {
                sealed interface Action permits Pay, Cancel {}
                record Pay(BigInteger amount, BigInteger fee) implements Action {}
                record Cancel() implements Action {}
                @Entrypoint
                static boolean validate(Action r, ScriptContext ctx) {
                    %s
                }
            }
            """;
    private static final PlutusData PAY = PlutusData.constr(0, PlutusData.integer(5), PlutusData.integer(1));
    private static final PlutusData BAD_PAY = PlutusData.constr(0, PlutusData.integer(-5), PlutusData.integer(1));
    private static final PlutusData CANCEL = PlutusData.constr(1);

    private static void dispatch(String body, boolean payAccepted, boolean badPayAccepted, boolean cancelAccepted) {
        for (var level : LEVELS) {
            var program = compiler(level).compile(SWITCH.formatted(body)).program();
            assertEquals(payAccepted, accepts(program, context(PAY)), level + " Pay: " + body);
            assertEquals(badPayAccepted, accepts(program, context(BAD_PAY)), level + " bad Pay: " + body);
            assertEquals(cancelAccepted, accepts(program, context(CANCEL)), level + " Cancel: " + body);
        }
    }

    @Test
    void switchArmReadsTheUserVariableNotTheConstructorTag() {
        dispatch("""
                BigInteger __match_tag = BigInteger.TEN;
                return switch (r) {
                    case Pay p -> __match_tag.equals(BigInteger.TEN) && p.amount().signum() > 0;
                    case Cancel c -> __match_tag.equals(BigInteger.TEN);
                };""", true, false, true);
    }

    @Test
    void switchArmReadsTheUserVariableNotTheDispatchData() {
        dispatch("""
                PlutusData __match_pair = Builtins.iData(BigInteger.ONE);
                BigInteger __match_data = BigInteger.valueOf(9);
                BigInteger __match_fields = BigInteger.valueOf(7);
                BigInteger __rest_0 = BigInteger.valueOf(3);
                return switch (r) {
                    case Pay p -> Builtins.equalsData(__match_pair, Builtins.iData(BigInteger.ONE))
                            && __match_data.equals(BigInteger.valueOf(9)) && __match_fields.equals(BigInteger.valueOf(7))
                            && __rest_0.equals(BigInteger.valueOf(3)) && p.amount().signum() > 0 && p.fee().signum() > 0;
                    case Cancel c -> false;
                };""", true, false, false);
    }

    // --- loops: xs__, loop__forEach__N, __acc_tuple, acc__forEach ---

    private static final String LOOPS = """
            import java.math.BigInteger;
            import org.julclang.core.types.JulcList;
            import org.julclang.stdlib.Builtins;
            class Loops {
                static BigInteger m(JulcList<BigInteger> items, JulcList<BigInteger> other) {
                    %s
                }
            }
            """;

    private static void loop(long expected, String body) {
        var items = PlutusData.list(PlutusData.integer(1), PlutusData.integer(2), PlutusData.integer(3));
        var other = PlutusData.list(PlutusData.integer(9), PlutusData.integer(9), PlutusData.integer(9),
                PlutusData.integer(9), PlutusData.integer(9));
        for (var level : LEVELS) {
            var program = compiler(level).compileMethod(LOOPS.formatted(body), "m").program();
            var result = CompilerTestVm.pv11().evaluateWithArgs(program, List.of(items, other));
            var success = assertInstanceOf(EvalResult.Success.class, result, level + ": " + body);
            assertEquals(new Term.Const(Constant.integer(BigInteger.valueOf(expected))), success.resultTerm(),
                    level + ": " + body);
        }
    }

    @Test
    void loopBodyReadsTheUserVariableNotTheRemainingList() {
        loop(15, "JulcList<BigInteger> xs__ = other; long n = 0; "
                + "for (var i : items) { n = n + xs__.size(); } return BigInteger.valueOf(n);");
        loop(15, "JulcList<BigInteger> xs__ = other; long n = 0; "
                + "for (var i : items) { if (i.equals(BigInteger.TEN)) { break; } n = n + xs__.size(); } "
                + "return BigInteger.valueOf(n);");
    }

    @Test
    void accumulatorNamedLikeTheListBinderIsTheAccumulator() {
        loop(3, "long xs__ = 0; for (var i : items) { xs__ = xs__ + 1; } return BigInteger.valueOf(xs__);");
    }

    @Test
    void loopBodyReadsTheUserVariableNotTheLoopFunction() {
        loop(6, "BigInteger loop__forEach__0 = BigInteger.TWO; long n = 0; "
                + "for (var i : items) { n = n + loop__forEach__0.longValue(); } return BigInteger.valueOf(n);");
        loop(6, "BigInteger loop__while__0 = BigInteger.TWO; long n = 0; long k = 0; "
                + "while (k < 3) { k = k + 1; n = n + loop__while__0.longValue(); } return BigInteger.valueOf(n);");
    }

    @Test
    void loopBodyReadsTheUserVariableNotTheAccumulatorTuple() {
        loop(3300, "BigInteger __acc_tuple = BigInteger.valueOf(100); long a = 0; long b = 0; "
                + "for (var i : items) { a = a + 1; b = b + __acc_tuple.longValue(); } "
                + "return BigInteger.valueOf(a * 1000 + b);");
        loop(10, "BigInteger acc__forEach = BigInteger.TEN; "
                + "for (var i : items) { if (!acc__forEach.equals(BigInteger.TEN)) { Builtins.error(); } } "
                + "return acc__forEach;");
    }

    // --- validator wrapper and parameter lambdas ---

    @Test
    void datumParamIsTheParamNotTheSpentDatum() {
        String source = """
                @SpendingValidator
                class DatumParam {
                    @Param PlutusData datum__;
                    @Entrypoint
                    static boolean validate(PlutusData datum, PlutusData redeemer, ScriptContext ctx) {
                        return Builtins.equalsData(datum__, datum);
                    }
                }
                """;
        var d = PlutusData.integer(7);
        for (var level : LEVELS) {
            var program = compiler(level).compile(source).program();
            assertTrue(accepts(program.applyParams(d), spendingContext(PlutusData.integer(0), d)), level.toString());
            assertFalse(accepts(program.applyParams(PlutusData.integer(8)), spendingContext(PlutusData.integer(0), d)),
                    level.toString());
        }
    }

    @Test
    void wrapperNamedParamsAreTheParams() {
        String source = """
                import java.math.BigInteger;
                @SpendingValidator
                class WrapperNames {
                    @Param BigInteger redeemer__;
                    @Param PlutusData scriptContextData;
                    @Param PlutusData ctxFields__;
                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        return Builtins.unIData(redeemer).equals(redeemer__)
                                && Builtins.equalsData(scriptContextData, Builtins.iData(BigInteger.ONE))
                                && Builtins.equalsData(ctxFields__, Builtins.iData(BigInteger.TWO));
                    }
                }
                """;
        for (var level : LEVELS) {
            var program = compiler(level).compile(source).program();
            var params = program.applyParams(PlutusData.integer(7), PlutusData.integer(1), PlutusData.integer(2));
            assertTrue(accepts(params, context(PlutusData.integer(7))), level.toString());
            assertFalse(accepts(params, context(PlutusData.integer(8))), level.toString());
            assertFalse(accepts(program.applyParams(PlutusData.integer(7), PlutusData.integer(2), PlutusData.integer(2)),
                    context(PlutusData.integer(7))), level.toString());
        }
    }

    @Test
    void paramNamedLikeAnotherParamsRawLambdaIsThatParam() {
        String source = """
                import java.math.BigInteger;
                @SpendingValidator
                class RawNames {
                    @Param PlutusData cfg__raw;
                    @Param PlutusData cfg;
                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        return Builtins.equalsData(cfg__raw, Builtins.iData(BigInteger.ONE))
                                && Builtins.equalsData(cfg, Builtins.iData(BigInteger.TWO));
                    }
                }
                """;
        for (var level : LEVELS) {
            var program = compiler(level).compile(source).program();
            assertTrue(accepts(program.applyParams(PlutusData.integer(1), PlutusData.integer(2)),
                    context(PlutusData.integer(0))), level.toString());
            assertFalse(accepts(program.applyParams(PlutusData.integer(2), PlutusData.integer(2)),
                    context(PlutusData.integer(0))), level.toString());
        }
    }

    @Test
    void compileMethodTargetNamedLikeADecodedArgument() {
        String source = """
                import java.math.BigInteger;
                class Target {
                    static BigInteger a__dec(BigInteger a) { return a.add(BigInteger.ONE); }
                }
                """;
        for (var level : LEVELS) {
            var program = compiler(level).compileMethod(source, "a__dec").program();
            var result = CompilerTestVm.pv11().evaluateWithArgs(program, List.of(PlutusData.integer(41)));
            var success = assertInstanceOf(EvalResult.Success.class, result, level.toString());
            assertEquals(new Term.Const(Constant.integer(BigInteger.valueOf(42))), success.resultTerm(), level.toString());
        }
    }
}
