package org.julclang.compiler;

import org.julclang.compiler.pir.PirGenerator;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;
import org.julclang.compiler.resolve.SymbolTable;
import org.julclang.compiler.resolve.TypeResolver;
import org.julclang.compiler.uplc.UplcGenerator;
import org.julclang.core.Constant;
import org.julclang.core.Term;
import org.julclang.vm.EvalResult;
import org.julclang.vm.JulcVm;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Task 4.2: Sealed Interface -> Sum Type Compilation
 */
class SealedInterfaceTest {

    static JulcVm vm;

    @BeforeAll
    static void setUp() {
        vm = CompilerTestVm.pv11();
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
    }

    @Nested
    class TypeResolverTests {
    }

    @Nested
    class PirGenerationTests {
    }

    @Nested
    class UplcGenerationTests {
        @Test
        void dataConstrLowersToConstrData() {
            var fields = List.of(new PirType.Field("x", new PirType.IntegerType()));
            var pir = new PirTerm.DataConstr(0,
                    new PirType.RecordType("X", fields),
                    List.of(new PirTerm.Const(Constant.integer(BigInteger.valueOf(42)))));
            var uplc = new UplcGenerator().generate(pir);
            // DataConstr now lowers to ConstrData(tag, MkCons(IData(val), MkNilData()))
            assertInstanceOf(Term.Apply.class, uplc);
        }

        @Test
        void dataConstrTag1LowersCorrectly() {
            var fields = List.of(new PirType.Field("y", new PirType.IntegerType()));
            var pir = new PirTerm.DataConstr(1,
                    new PirType.RecordType("Y", fields),
                    List.of(new PirTerm.Const(Constant.integer(BigInteger.valueOf(99)))));
            var uplc = new UplcGenerator().generate(pir);
            // Should be ConstrData(1, ...) — an Apply chain
            assertInstanceOf(Term.Apply.class, uplc);
        }

        @Test
        void dataConstrEvaluates() {
            var fields = List.of(new PirType.Field("x", new PirType.IntegerType()));
            var pir = new PirTerm.DataConstr(0,
                    new PirType.RecordType("X", fields),
                    List.of(new PirTerm.Const(Constant.integer(BigInteger.valueOf(42)))));
            var uplc = new UplcGenerator().generate(pir);
            var result = vm.evaluate(org.julclang.core.Program.plutusV3(uplc));
            assertTrue(result.isSuccess(), "Expected success: " + result);
        }
    }

    @Nested
    class CompilerIntegrationTests {
        @Test
        void compilesValidatorWithSealedInterface() {
            var source = """
                import java.math.BigInteger;
                import org.julclang.stdlib.annotation.Entrypoint;
                import org.julclang.stdlib.annotation.SpendingValidator;
                import org.julclang.core.PlutusData;

                sealed interface Action permits Mint, Burn {}
                record Mint(int amt) implements Action {}
                record Burn(int amt) implements Action {}

                @SpendingValidator
                class MyValidator {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, PlutusData ctx) {
                        return redeemer.compareTo(BigInteger.ZERO) > 0;
                    }
                }
                """;
            var result = new JulcCompiler().compile(source);
            assertNotNull(result.program());
            assertFalse(result.hasErrors());
        }

        @Test
        void constructsVariantInValidator() {
            var source = """
                import java.math.BigInteger;
                import org.julclang.stdlib.annotation.Entrypoint;
                import org.julclang.stdlib.annotation.SpendingValidator;
                import org.julclang.core.PlutusData;

                sealed interface Action permits Mint, Burn {}
                record Mint(int amt) implements Action {}
                record Burn(int amt) implements Action {}

                @SpendingValidator
                class MyValidator {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, PlutusData ctx) {
                        var action = new Mint(10);
                        return true;
                    }
                }
                """;
            var result = new JulcCompiler().compile(source);
            assertNotNull(result.program());
        }

        @Test
        void differentFieldCountsPerVariant() {
            var source = """
                import java.math.BigInteger;
                import org.julclang.stdlib.annotation.Entrypoint;
                import org.julclang.stdlib.annotation.SpendingValidator;
                import org.julclang.core.PlutusData;

                sealed interface Redeemer permits Pay, Refund, Cancel {}
                record Pay(int amount, int fee) implements Redeemer {}
                record Refund(int reason) implements Redeemer {}
                record Cancel() implements Redeemer {}

                @SpendingValidator
                class MyValidator {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, PlutusData ctx) {
                        return true;
                    }
                }
                """;
            var result = new JulcCompiler().compile(source);
            assertNotNull(result.program());
        }

        @Test
        void variantUsedInHelper() {
            var source = """
                import java.math.BigInteger;
                import org.julclang.stdlib.annotation.Entrypoint;
                import org.julclang.stdlib.annotation.SpendingValidator;
                import org.julclang.core.PlutusData;

                sealed interface Action permits Mint, Burn {}
                record Mint(int amt) implements Action {}
                record Burn(int amt) implements Action {}

                @SpendingValidator
                class MyValidator {
                    static boolean isMint() {
                        var m = new Mint(10);
                        return true;
                    }

                    @Entrypoint
                    static boolean validate(BigInteger redeemer, PlutusData ctx) {
                        return isMint();
                    }
                }
                """;
            var result = new JulcCompiler().compile(source);
            assertNotNull(result.program());
        }
    }
}
