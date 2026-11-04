package org.julclang.compiler;


import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;
import org.julclang.compiler.uplc.UplcGenerator;
import org.julclang.core.Constant;
import org.julclang.core.DefaultFun;
import org.julclang.core.Term;
import org.julclang.vm.EvalResult;
import org.julclang.vm.JulcVm;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Task 4.4: Lambda Expressions
 */
class LambdaExpressionTest {

    static JulcVm vm;

    @BeforeAll
    static void setUp() {
        vm = CompilerTestVm.pv11();
    }

    @Nested
    class PirGenerationTests {
    }

    @Nested
    class EndToEndTests {
        @Test
        void lambdaEvaluates() {
            // Build: (\x -> x + 1) applied to 5 = 6
            var pir = new PirTerm.App(
                    new PirTerm.Lam("x", new PirType.IntegerType(),
                            new PirTerm.App(
                                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.AddInteger),
                                            new PirTerm.Var("x", new PirType.IntegerType())),
                                    new PirTerm.Const(Constant.integer(BigInteger.ONE)))),
                    new PirTerm.Const(Constant.integer(BigInteger.valueOf(5))));
            var uplc = new UplcGenerator().generate(pir);
            var result = vm.evaluate(org.julclang.core.Program.plutusV3(uplc));
            assertTrue(result.isSuccess());
            var val = ((Term.Const) ((EvalResult.Success) result).resultTerm()).value();
            assertEquals(BigInteger.valueOf(6), ((Constant.IntegerConst) val).value());
        }

        @Test
        void multiParamLambdaEvaluates() {
            // (\x -> \y -> x + y) 3 4 = 7
            var pir = new PirTerm.App(
                    new PirTerm.App(
                            new PirTerm.Lam("x", new PirType.IntegerType(),
                                    new PirTerm.Lam("y", new PirType.IntegerType(),
                                            new PirTerm.App(
                                                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.AddInteger),
                                                            new PirTerm.Var("x", new PirType.IntegerType())),
                                                    new PirTerm.Var("y", new PirType.IntegerType())))),
                            new PirTerm.Const(Constant.integer(BigInteger.valueOf(3)))),
                    new PirTerm.Const(Constant.integer(BigInteger.valueOf(4))));
            var uplc = new UplcGenerator().generate(pir);
            var result = vm.evaluate(org.julclang.core.Program.plutusV3(uplc));
            assertTrue(result.isSuccess());
            var val = ((Term.Const) ((EvalResult.Success) result).resultTerm()).value();
            assertEquals(BigInteger.valueOf(7), ((Constant.IntegerConst) val).value());
        }
    }

    @Nested
    class CompilerIntegrationTests {

        /**
         * ADR-068 M1b: a lambda bound to a {@code var} local is not Java (javac: cannot infer type), and JuLC does
         * not accept a functional-interface type for the local; compiled with the javac gate off (M1b's report).
         */
        private static JulcCompiler dialectCompiler() {
            return new JulcCompiler(null, new CompilerOptions());
        }
    }
}
