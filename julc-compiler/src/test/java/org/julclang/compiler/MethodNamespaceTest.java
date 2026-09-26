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
 * ADR-060: as in Java, methods have their own namespace. Call syntax names a method, never a
 * variable, and a bare name is a variable, never a method. Methods used to share one PIR namespace
 * with variables: {@code BigInteger fee = fee(a); return fee(fee);} applied the local to its
 * argument and failed at run time, and a field could not share a method's name.
 */
class MethodNamespaceTest {
    private static final String HEADER = """
            import java.math.BigInteger;
            import org.julclang.core.types.JulcList;
            import org.julclang.stdlib.Builtins;
            class Names {
            """;
    private static final List<OptimizationLevel> LEVELS = List.of(
            OptimizationLevel.NONE, OptimizationLevel.BASELINE, OptimizationLevel.PV11_SAFE);

    private static void returns(Term expected, String members, PlutusData... args) {
        for (var level : LEVELS) {
            var program = new JulcCompiler(StdlibRegistry.defaultRegistry(),
                    new CompilerOptions().setOptimizationLevel(level)).compileMethod(HEADER + members + " }", "m").program();
            var result = CompilerTestVm.pv11().evaluateWithArgs(program, List.of(args));
            var success = assertInstanceOf(EvalResult.Success.class, result, level + ": " + members);
            assertEquals(expected, success.resultTerm(), level + ": " + members);
        }
    }

    private static Term integer(long value) {
        return new Term.Const(Constant.integer(BigInteger.valueOf(value)));
    }

    private static CompilerException rejected(String members) {
        return assertThrows(CompilerException.class, () -> new JulcCompiler(StdlibRegistry.defaultRegistry())
                .compileMethod(HEADER + members + " }", "m"));
    }

    @Test
    void localWithAMethodsNameDoesNotShadowTheMethod() {
        returns(integer(7), """
                static BigInteger fee(BigInteger x) { return x.add(BigInteger.ONE); }
                static BigInteger m(BigInteger a) { BigInteger fee = fee(a); return fee(fee); }""",
                PlutusData.integer(5));
    }

    @Test
    void parameterWithAMethodsNameDoesNotShadowTheMethod() {
        returns(new Term.Const(Constant.bool(true)), """
                static boolean positive(BigInteger v) { return v.signum() > 0; }
                static boolean m(JulcList<BigInteger> positive) { return positive.all(p -> positive(p)); }""",
                PlutusData.list(PlutusData.integer(1), PlutusData.integer(2)));
        returns(new Term.Const(Constant.bool(false)), """
                static boolean positive(BigInteger v) { return v.signum() > 0; }
                static boolean m(JulcList<BigInteger> positive) { return positive.all(p -> positive(p)); }""",
                PlutusData.list(PlutusData.integer(1), PlutusData.integer(-2)));
    }

    @Test
    void fieldAndMethodMayShareAName() {
        returns(integer(17), """
                static final BigInteger fee = BigInteger.TEN;
                static BigInteger fee(BigInteger x) { return x.add(BigInteger.ONE); }
                static BigInteger m(BigInteger a) { return fee.add(fee(a)); }""",
                PlutusData.integer(6));
    }

    @Test
    void paramAndMethodMayShareAName() {
        String source = """
                import java.math.BigInteger;
                @SpendingValidator
                class ParamNamedLikeMethod {
                    @Param BigInteger limit;
                    static BigInteger limit(BigInteger x) { return x.add(BigInteger.ONE); }
                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        return limit(Builtins.unIData(redeemer)).compareTo(limit) <= 0;
                    }
                }
                """;
        for (var level : LEVELS) {
            var program = new JulcCompiler(StdlibRegistry.defaultRegistry(),
                    new CompilerOptions().setOptimizationLevel(level)).compile(source).program();
            var context = PlutusData.constr(0, PlutusData.integer(0), PlutusData.integer(9), PlutusData.integer(0));
            assertTrue(CompilerTestVm.pv11().evaluateWithArgs(program.applyParams(PlutusData.integer(10)),
                    List.of(context)).isSuccess(), level.toString());
            assertFalse(CompilerTestVm.pv11().evaluateWithArgs(program.applyParams(PlutusData.integer(9)),
                    List.of(context)).isSuccess(), level.toString());
        }
    }

    @Test
    void unresolvedMemberAccessIsRejected() {
        // PlutusData has no amount(): javac rejects this. JuLC used to call the helper named amount.
        var helper = rejected("""
                static BigInteger amount(PlutusData d) { return Builtins.unIData(d); }
                static BigInteger m(PlutusData d) { return d.amount(); }""");
        assertEquals("JULC0055", helper.diagnostics().getFirst().code(), helper.getMessage());
        assertTrue(helper.diagnostics().getFirst().line() > 0, "JULC0055 must point at the source: " + helper.getMessage());
        assertTrue(helper.getMessage().contains("Cannot resolve amount"), helper.getMessage());
        // ... and with an argument, the local named like the member.
        var local = rejected("""
                static BigInteger m(PlutusData d, BigInteger scale) { BigInteger times = scale; return d.times(scale); }""");
        assertEquals("JULC0055", local.diagnostics().getFirst().code(), local.getMessage());
    }

    @Test
    void staticFieldInitializerCallingAMethodIsRejected() {
        var error = rejected("""
                static BigInteger base() { return BigInteger.TEN; }
                static final BigInteger FEE = base();
                static BigInteger m(BigInteger a) { return a.add(FEE); }""");
        assertEquals("JULC0057", error.diagnostics().getFirst().code(), error.getMessage());
        assertTrue(error.getMessage().contains("Static field 'FEE' calls method base"), error.getMessage());
    }
}
