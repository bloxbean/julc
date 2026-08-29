package com.bloxbean.cardano.julc.compiler.uplc;

import com.bloxbean.cardano.julc.compiler.CompilationContext;
import com.bloxbean.cardano.julc.compiler.CompilerOptions;
import com.bloxbean.cardano.julc.compiler.JulcCompiler;
import com.bloxbean.cardano.julc.compiler.OptimizationLevel;
import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.DefaultFun;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.core.Term;
import com.bloxbean.cardano.julc.core.flat.UplcFlatEncoder;
import com.bloxbean.cardano.julc.core.text.UplcPrinter;
import com.bloxbean.cardano.julc.stdlib.StdlibRegistry;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class O13ExpModLiteralFoldTest {

    private static final String SOURCE = """
            import com.bloxbean.cardano.julc.stdlib.lib.MathLib;
            import java.math.BigInteger;
            class ExpModLiteralSample {
                static BigInteger literals() {
                    return MathLib.expMod(
                                    BigInteger.valueOf(2), BigInteger.valueOf(5),
                                    BigInteger.valueOf(13))
                            + MathLib.expMod(
                                    BigInteger.valueOf(2), BigInteger.valueOf(-1),
                                    BigInteger.valueOf(5));
                }
            }
            """;

    @Test
    void safeLevelFoldsPositiveAndInvertibleNegativeExponents() {
        assertFold(expMod(2, 5, 13), 6);
        assertFold(expMod(2, -1, 5), 3);
        assertFold(expMod(0, 0, 7), 1);
    }

    @Test
    void runtimeFailureCasesRemainByteIdentical() {
        assertNotFolded(expMod(2, 5, 0));
        assertNotFolded(expMod(2, 5, -7));
        assertNotFolded(expMod(2, -1, 4));
    }

    @Test
    void baselineDoesNotAdoptNewLiteralFold() {
        var term = expMod(2, 5, 13);
        var result = optimizer(OptimizationLevel.BASELINE).optimizeWithReport(term);

        assertEquals(term, result.term());
        assertTrue(result.appliedPasses().stream()
                .noneMatch(rule -> rule.equals(UplcOptimizer.PV11_EXP_MOD_LITERAL_RULE)));
    }

    @Test
    void safeReportUsesStableRuleAndReducesFlatSize() {
        var term = expMod(2, 123, 7919);
        var result = optimizer(OptimizationLevel.PV11_SAFE).optimizeWithReport(term);

        assertTrue(result.appliedPasses().contains(UplcOptimizer.PV11_EXP_MOD_LITERAL_RULE));
        assertTrue(UplcFlatEncoder.encodeProgram(Program.plutusV3(result.term())).length
                < UplcFlatEncoder.encodeProgram(Program.plutusV3(term)).length);
    }

    @Test
    void compilerPipelineAppliesLiteralFold() {
        var candidate = new JulcCompiler(
                StdlibRegistry.defaultRegistry(),
                new CompilerOptions().setOptimizationLevel(OptimizationLevel.PV11_SAFE))
                .compileMethod(SOURCE, "literals");

        assertTrue(candidate.optimizationReport().appliedRules()
                        .contains(UplcOptimizer.PV11_EXP_MOD_LITERAL_RULE),
                UplcPrinter.print(candidate.program().term()) + "\n"
                        + candidate.program().term());
    }

    @Test
    void sharedExpModBuiltinBindingIsFoldedWithoutInliningOtherUses() {
        var term = Term.apply(
                Term.lam("expMod", expMod(Term.var(1), 2, 5, 13)),
                Term.builtin(DefaultFun.ExpModInteger));
        var result = optimizer(OptimizationLevel.PV11_SAFE).optimizeWithReport(term);

        var constant = assertInstanceOf(Term.Const.class, result.term());
        assertEquals(BigInteger.valueOf(6),
                assertInstanceOf(Constant.IntegerConst.class, constant.value()).value());
        assertTrue(result.appliedPasses().contains(UplcOptimizer.PV11_EXP_MOD_LITERAL_RULE));
    }

    @Test
    void aliasTrackingRespectsNestedLambdaDepthAndShadowing() {
        var nestedUse = Term.apply(
                Term.lam("expMod", Term.lam("ignored",
                        expMod(Term.var(2), 2, 5, 13))),
                Term.builtin(DefaultFun.ExpModInteger));
        var nestedResult = optimizer(OptimizationLevel.PV11_SAFE)
                .optimizeWithReport(nestedUse);
        var lambda = assertInstanceOf(Term.Lam.class, nestedResult.term());
        assertInstanceOf(Term.Const.class, lambda.body());

        var shadowedUse = Term.apply(
                Term.lam("expMod", Term.lam("differentFunction",
                        expMod(Term.var(1), 2, 5, 13))),
                Term.builtin(DefaultFun.ExpModInteger));
        assertEquals(shadowedUse,
                optimizer(OptimizationLevel.PV11_SAFE).foldLiteralExpMod(shadowedUse));
    }

    @Test
    void aliasCallWithNonLiteralArgumentRemainsAtRuntime() {
        var term = Term.apply(
                Term.lam("expMod", Term.lam("base",
                        expMod(Term.var(2), Term.var(1), 5, 13))),
                Term.builtin(DefaultFun.ExpModInteger));

        assertEquals(term, optimizer(OptimizationLevel.PV11_SAFE).foldLiteralExpMod(term));
    }

    private static void assertFold(Term term, long expected) {
        var folded = optimizer(OptimizationLevel.PV11_SAFE).foldLiteralExpMod(term);
        var constant = assertInstanceOf(Term.Const.class, folded);
        var integer = assertInstanceOf(Constant.IntegerConst.class, constant.value());
        assertEquals(BigInteger.valueOf(expected), integer.value());
    }

    private static void assertNotFolded(Term term) {
        assertEquals(term, optimizer(OptimizationLevel.PV11_SAFE).foldLiteralExpMod(term));
    }

    private static UplcOptimizer optimizer(OptimizationLevel level) {
        return new UplcOptimizer(CompilationContext.resolve(
                new CompilerOptions().setOptimizationLevel(level)));
    }

    private static Term expMod(long base, long exponent, long modulus) {
        return expMod(Term.builtin(DefaultFun.ExpModInteger),
                Term.const_(Constant.integer(base)), exponent, modulus);
    }

    private static Term expMod(Term function, long base, long exponent, long modulus) {
        return expMod(function, Term.const_(Constant.integer(base)), exponent, modulus);
    }

    private static Term expMod(Term function, Term base, long exponent, long modulus) {
        return Term.apply(
                Term.apply(
                        Term.apply(function, base),
                        Term.const_(Constant.integer(exponent))),
                Term.const_(Constant.integer(modulus)));
    }
}
