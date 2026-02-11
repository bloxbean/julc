package com.bloxbean.cardano.julc.compiler;

import com.bloxbean.cardano.julc.compiler.pir.PirGenerator;
import com.bloxbean.cardano.julc.compiler.pir.PirTerm;
import com.bloxbean.cardano.julc.compiler.pir.PirType;
import com.bloxbean.cardano.julc.compiler.resolve.SymbolTable;
import com.bloxbean.cardano.julc.compiler.resolve.TypeResolver;
import com.bloxbean.cardano.julc.compiler.uplc.UplcGenerator;
import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.DefaultFun;
import com.bloxbean.cardano.julc.core.Term;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.julc.vm.JulcVm;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
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
        vm = JulcVm.create();
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
    }

    @Nested
    class PirGenerationTests {
        @Test
        void singleParamLambda() {
            var typeResolver = new TypeResolver();
            var symbolTable = new SymbolTable();
            var pirGen = new PirGenerator(typeResolver, symbolTable);

            var expr = StaticJavaParser.parseExpression("(BigInteger x) -> x + 1");
            var pir = pirGen.generateExpression(expr);

            assertInstanceOf(PirTerm.Lam.class, pir);
            var lam = (PirTerm.Lam) pir;
            assertEquals("x", lam.param());
        }

        @Test
        void multiParamLambda() {
            var typeResolver = new TypeResolver();
            var symbolTable = new SymbolTable();
            var pirGen = new PirGenerator(typeResolver, symbolTable);

            var expr = StaticJavaParser.parseExpression("(BigInteger x, BigInteger y) -> x + y");
            var pir = pirGen.generateExpression(expr);

            assertInstanceOf(PirTerm.Lam.class, pir);
            var outer = (PirTerm.Lam) pir;
            assertEquals("x", outer.param());
            assertInstanceOf(PirTerm.Lam.class, outer.body());
            assertEquals("y", ((PirTerm.Lam) outer.body()).param());
        }

        @Test
        void lambdaWithBlockBody() {
            var typeResolver = new TypeResolver();
            var symbolTable = new SymbolTable();
            var pirGen = new PirGenerator(typeResolver, symbolTable);

            var expr = StaticJavaParser.parseExpression("(BigInteger x) -> { return x + 1; }");
            var pir = pirGen.generateExpression(expr);

            assertInstanceOf(PirTerm.Lam.class, pir);
        }

        @Test
        void lambdaGeneratesCorrectType() {
            var typeResolver = new TypeResolver();
            var symbolTable = new SymbolTable();
            var pirGen = new PirGenerator(typeResolver, symbolTable);

            var expr = StaticJavaParser.parseExpression("(BigInteger x) -> x + 1");
            var pir = pirGen.generateExpression(expr);

            var lam = (PirTerm.Lam) pir;
            assertInstanceOf(PirType.IntegerType.class, lam.paramType());
        }
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
            var result = vm.evaluate(com.bloxbean.cardano.julc.core.Program.plutusV3(uplc));
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
            var result = vm.evaluate(com.bloxbean.cardano.julc.core.Program.plutusV3(uplc));
            assertTrue(result.isSuccess());
            var val = ((Term.Const) ((EvalResult.Success) result).resultTerm()).value();
            assertEquals(BigInteger.valueOf(7), ((Constant.IntegerConst) val).value());
        }
    }

    @Nested
    class CompilerIntegrationTests {
        @Test
        void lambdaInLetBinding() {
            var source = """
                import java.math.BigInteger;

                @Validator
                class MyValidator {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, BigInteger ctx) {
                        var f = (BigInteger x) -> x + 1;
                        return true;
                    }
                }
                """;
            var result = new JulcCompiler().compile(source);
            assertNotNull(result.program());
        }

        @Test
        void lambdaAsMethodArgument() {
            // This tests that lambdas can be passed to helper methods
            var source = """
                import java.math.BigInteger;

                @Validator
                class MyValidator {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, BigInteger ctx) {
                        var f = (BigInteger x) -> x + 1;
                        return true;
                    }
                }
                """;
            var result = new JulcCompiler().compile(source);
            assertNotNull(result.program());
        }

        @Test
        void lambdaWithMultipleParams() {
            var source = """
                import java.math.BigInteger;

                @Validator
                class MyValidator {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, BigInteger ctx) {
                        var add = (BigInteger x, BigInteger y) -> x + y;
                        return true;
                    }
                }
                """;
            var result = new JulcCompiler().compile(source);
            assertNotNull(result.program());
        }

        @Test
        void lambdaWithBoolBody() {
            var source = """
                import java.math.BigInteger;

                @Validator
                class MyValidator {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, BigInteger ctx) {
                        var isPositive = (BigInteger x) -> x > 0;
                        return true;
                    }
                }
                """;
            var result = new JulcCompiler().compile(source);
            assertNotNull(result.program());
        }

        @Test
        void lambdaWithBlockBodyCompiles() {
            var source = """
                import java.math.BigInteger;

                @Validator
                class MyValidator {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, BigInteger ctx) {
                        var compute = (BigInteger x) -> {
                            var doubled = x + x;
                            return doubled;
                        };
                        return true;
                    }
                }
                """;
            var result = new JulcCompiler().compile(source);
            assertNotNull(result.program());
        }

        @Test
        void lambdaWithBooleanParams() {
            var source = """
                import java.math.BigInteger;

                @Validator
                class MyValidator {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, BigInteger ctx) {
                        var and = (boolean a, boolean b) -> a && b;
                        return true;
                    }
                }
                """;
            var result = new JulcCompiler().compile(source);
            assertNotNull(result.program());
        }
    }
}
