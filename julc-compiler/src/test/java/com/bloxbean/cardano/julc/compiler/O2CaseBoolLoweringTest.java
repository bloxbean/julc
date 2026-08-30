package com.bloxbean.cardano.julc.compiler;

import com.bloxbean.cardano.julc.compiler.uplc.UplcGenerator;
import com.bloxbean.cardano.julc.core.DefaultFun;
import com.bloxbean.cardano.julc.core.Term;
import com.bloxbean.cardano.julc.core.flat.UplcFlatEncoder;
import com.bloxbean.cardano.julc.stdlib.StdlibRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class O2CaseBoolLoweringTest {

    private static final String SOURCE = """
            import java.math.BigInteger;
            class BoolCaseSample {
                static BigInteger choose(boolean condition) {
                    if (condition) return BigInteger.valueOf(7);
                    return BigInteger.valueOf(9);
                }
            }
            """;

    @Test
    void safeLevelUsesFalseThenTrueCaseBranches() {
        var baseline = compile(OptimizationLevel.BASELINE, false);
        var candidate = compile(OptimizationLevel.PV11_SAFE, false);

        assertTrue(builtinCount(baseline.program().term(), DefaultFun.IfThenElse) > 0);
        assertEquals(0, builtinCount(candidate.program().term(), DefaultFun.IfThenElse));
        assertTrue(caseCount(candidate.program().term()) > 0);
        assertFalse(baseline.optimizationReport().appliedRules()
                .contains(UplcGenerator.PV11_CASE_BOOL_RULE));
        assertTrue(candidate.optimizationReport().appliedRules()
                .contains(UplcGenerator.PV11_CASE_BOOL_RULE));
        assertTrue(candidate.scriptSizeBytes() < baseline.scriptSizeBytes());
    }

    @Test
    void defaultAndExplicitSafeRemainByteIdentical() {
        var defaults = new JulcCompiler(StdlibRegistry.defaultRegistry())
                .compileMethod(SOURCE, "choose");
        var safe = compile(OptimizationLevel.PV11_SAFE, false);

        assertArrayEquals(
                UplcFlatEncoder.encodeProgram(defaults.program()),
                UplcFlatEncoder.encodeProgram(safe.program()));
        assertTrue(caseCount(defaults.program().term()) > 0);
        assertEquals(OptimizationLevel.PV11_SAFE,
                defaults.optimizationReport().level());
    }

    @Test
    void typedLoweringRemainsEnabledWithSourceMaps() {
        var candidate = compile(OptimizationLevel.PV11_SAFE, true);

        assertNotNull(candidate.sourceMap());
        assertTrue(caseCount(candidate.program().term()) > 0);
        assertTrue(candidate.optimizationReport().appliedRules()
                .contains(UplcGenerator.PV11_CASE_BOOL_RULE));
    }

    private static CompileResult compile(OptimizationLevel level, boolean sourceMap) {
        return new JulcCompiler(
                StdlibRegistry.defaultRegistry(),
                new CompilerOptions()
                        .setOptimizationLevel(level)
                        .setSourceMapEnabled(sourceMap))
                .compileMethod(SOURCE, "choose");
    }

    private static int caseCount(Term term) {
        return walk(term, null, true);
    }

    private static int builtinCount(Term term, DefaultFun fun) {
        return walk(term, fun, false);
    }

    private static int walk(Term term, DefaultFun fun, boolean cases) {
        return switch (term) {
            case Term.Var _, Term.Const _, Term.Error() -> 0;
            case Term.Builtin(var actual) -> !cases && actual == fun ? 1 : 0;
            case Term.Lam(_, var body) -> walk(body, fun, cases);
            case Term.Delay(var body) -> walk(body, fun, cases);
            case Term.Force(var body) -> walk(body, fun, cases);
            case Term.Apply(var function, var argument) ->
                    walk(function, fun, cases) + walk(argument, fun, cases);
            case Term.Constr(_, var fields) -> fields.stream()
                    .mapToInt(field -> walk(field, fun, cases)).sum();
            case Term.Case(var scrutinee, var branches) ->
                    (cases ? 1 : 0) + walk(scrutinee, fun, cases)
                            + branches.stream().mapToInt(branch -> walk(branch, fun, cases)).sum();
        };
    }
}
