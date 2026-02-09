package com.bloxbean.cardano.plutus.compiler;

import com.bloxbean.cardano.plutus.compiler.pir.PirGenerator;
import com.bloxbean.cardano.plutus.compiler.pir.PirTerm;
import com.bloxbean.cardano.plutus.compiler.pir.PirType;
import com.bloxbean.cardano.plutus.compiler.resolve.SymbolTable;
import com.bloxbean.cardano.plutus.compiler.resolve.TypeResolver;
import com.bloxbean.cardano.plutus.compiler.uplc.UplcGenerator;
import com.bloxbean.cardano.plutus.core.Constant;
import com.bloxbean.cardano.plutus.core.Term;
import com.bloxbean.cardano.plutus.vm.EvalResult;
import com.bloxbean.cardano.plutus.vm.PlutusVm;
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

    static PlutusVm vm;

    @BeforeAll
    static void setUp() {
        vm = PlutusVm.create();
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
    }

    @Nested
    class TypeResolverTests {
        @Test
        void registersTwoVariantSealedInterface() {
            var typeResolver = new TypeResolver();
            var cu = StaticJavaParser.parse("""
                sealed interface Action permits Mint, Burn {}
                record Mint(int amt) implements Action {}
                record Burn(int amt) implements Action {}
                """);
            for (var rd : cu.findAll(RecordDeclaration.class)) {
                typeResolver.registerRecord(rd);
            }
            for (var iface : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                if (iface.isInterface() && !iface.getPermittedTypes().isEmpty()) {
                    typeResolver.registerSealedInterface(iface);
                }
            }
            var sumType = typeResolver.lookupSumType("Action");
            assertTrue(sumType.isPresent());
            assertEquals(2, sumType.get().constructors().size());
            assertEquals("Mint", sumType.get().constructors().get(0).name());
            assertEquals(0, sumType.get().constructors().get(0).tag());
            assertEquals("Burn", sumType.get().constructors().get(1).name());
            assertEquals(1, sumType.get().constructors().get(1).tag());
        }

        @Test
        void registersThreeVariantSealedInterface() {
            var typeResolver = new TypeResolver();
            var cu = StaticJavaParser.parse("""
                sealed interface Shape permits Circle, Rect, Triangle {}
                record Circle(int radius) implements Shape {}
                record Rect(int w, int h) implements Shape {}
                record Triangle(int a, int b, int c) implements Shape {}
                """);
            for (var rd : cu.findAll(RecordDeclaration.class)) {
                typeResolver.registerRecord(rd);
            }
            for (var iface : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                if (iface.isInterface() && !iface.getPermittedTypes().isEmpty()) {
                    typeResolver.registerSealedInterface(iface);
                }
            }
            var sumType = typeResolver.lookupSumType("Shape");
            assertTrue(sumType.isPresent());
            assertEquals(3, sumType.get().constructors().size());
            assertEquals(2, sumType.get().constructors().get(2).tag());
            assertEquals("Triangle", sumType.get().constructors().get(2).name());
            // Triangle has 3 fields
            assertEquals(3, sumType.get().constructors().get(2).fields().size());
        }

        @Test
        void variantHasCorrectFields() {
            var typeResolver = new TypeResolver();
            var cu = StaticJavaParser.parse("""
                sealed interface Action permits Mint, Burn {}
                record Mint(int amt) implements Action {}
                record Burn(int amt) implements Action {}
                """);
            for (var rd : cu.findAll(RecordDeclaration.class)) {
                typeResolver.registerRecord(rd);
            }
            for (var iface : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                if (iface.isInterface() && !iface.getPermittedTypes().isEmpty()) {
                    typeResolver.registerSealedInterface(iface);
                }
            }
            var sumType = typeResolver.lookupSumType("Action").orElseThrow();
            var mintCtor = sumType.constructors().get(0);
            assertEquals(1, mintCtor.fields().size());
            assertEquals("amt", mintCtor.fields().get(0).name());
            assertInstanceOf(PirType.IntegerType.class, mintCtor.fields().get(0).type());
        }

        @Test
        void lookupSumTypeForVariant() {
            var typeResolver = new TypeResolver();
            var cu = StaticJavaParser.parse("""
                sealed interface Action permits Mint, Burn {}
                record Mint(int amt) implements Action {}
                record Burn(int amt) implements Action {}
                """);
            for (var rd : cu.findAll(RecordDeclaration.class)) {
                typeResolver.registerRecord(rd);
            }
            for (var iface : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                if (iface.isInterface() && !iface.getPermittedTypes().isEmpty()) {
                    typeResolver.registerSealedInterface(iface);
                }
            }
            var sumForMint = typeResolver.lookupSumTypeForVariant("Mint");
            assertTrue(sumForMint.isPresent());
            assertEquals("Action", sumForMint.get().name());

            var sumForBurn = typeResolver.lookupSumTypeForVariant("Burn");
            assertTrue(sumForBurn.isPresent());
            assertEquals("Action", sumForBurn.get().name());
        }

        @Test
        void resolvesSealedInterfaceAsType() {
            var typeResolver = new TypeResolver();
            var cu = StaticJavaParser.parse("""
                sealed interface Action permits Mint, Burn {}
                record Mint(int amt) implements Action {}
                record Burn(int amt) implements Action {}
                """);
            for (var rd : cu.findAll(RecordDeclaration.class)) {
                typeResolver.registerRecord(rd);
            }
            for (var iface : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                if (iface.isInterface() && !iface.getPermittedTypes().isEmpty()) {
                    typeResolver.registerSealedInterface(iface);
                }
            }
            // Parse a method param type
            var methodCu = StaticJavaParser.parse("class X { void f(Action a) {} }");
            var method = methodCu.findAll(com.github.javaparser.ast.body.MethodDeclaration.class).get(0);
            var paramType = typeResolver.resolve(method.getParameter(0).getType());
            assertInstanceOf(PirType.SumType.class, paramType);
        }
    }

    @Nested
    class PirGenerationTests {
        @Test
        void constructorGetsCorrectTag() {
            // Parse and compile: new Mint(42) where Mint is variant 0 of Action
            var typeResolver = new TypeResolver();
            var cu = StaticJavaParser.parse("""
                sealed interface Action permits Mint, Burn {}
                record Mint(int amt) implements Action {}
                record Burn(int amt) implements Action {}
                class X {
                    static void test() {
                        var m = new Mint(42);
                    }
                }
                """);
            for (var rd : cu.findAll(RecordDeclaration.class)) {
                typeResolver.registerRecord(rd);
            }
            for (var iface : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                if (iface.isInterface() && !iface.getPermittedTypes().isEmpty()) {
                    typeResolver.registerSealedInterface(iface);
                }
            }
            var symbolTable = new SymbolTable();
            var pirGen = new PirGenerator(typeResolver, symbolTable);

            var expr = StaticJavaParser.parseExpression("new Mint(42)");
            var pir = pirGen.generateExpression(expr);

            assertInstanceOf(PirTerm.DataConstr.class, pir);
            var dc = (PirTerm.DataConstr) pir;
            assertEquals(0, dc.tag()); // Mint is tag 0
        }

        @Test
        void secondVariantGetsTag1() {
            var typeResolver = new TypeResolver();
            var cu = StaticJavaParser.parse("""
                sealed interface Action permits Mint, Burn {}
                record Mint(int amt) implements Action {}
                record Burn(int amt) implements Action {}
                """);
            for (var rd : cu.findAll(RecordDeclaration.class)) {
                typeResolver.registerRecord(rd);
            }
            for (var iface : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                if (iface.isInterface() && !iface.getPermittedTypes().isEmpty()) {
                    typeResolver.registerSealedInterface(iface);
                }
            }
            var symbolTable = new SymbolTable();
            var pirGen = new PirGenerator(typeResolver, symbolTable);

            var expr = StaticJavaParser.parseExpression("new Burn(99)");
            var pir = pirGen.generateExpression(expr);

            assertInstanceOf(PirTerm.DataConstr.class, pir);
            var dc = (PirTerm.DataConstr) pir;
            assertEquals(1, dc.tag()); // Burn is tag 1
        }

        @Test
        void constructorFieldsGenerated() {
            var typeResolver = new TypeResolver();
            var cu = StaticJavaParser.parse("""
                sealed interface Shape permits Circle, Rect {}
                record Circle(int radius) implements Shape {}
                record Rect(int w, int h) implements Shape {}
                """);
            for (var rd : cu.findAll(RecordDeclaration.class)) {
                typeResolver.registerRecord(rd);
            }
            for (var iface : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                if (iface.isInterface() && !iface.getPermittedTypes().isEmpty()) {
                    typeResolver.registerSealedInterface(iface);
                }
            }
            var symbolTable = new SymbolTable();
            var pirGen = new PirGenerator(typeResolver, symbolTable);

            var expr = StaticJavaParser.parseExpression("new Rect(10, 20)");
            var pir = pirGen.generateExpression(expr);

            assertInstanceOf(PirTerm.DataConstr.class, pir);
            var dc = (PirTerm.DataConstr) pir;
            assertEquals(1, dc.tag()); // Rect is tag 1
            assertEquals(2, dc.fields().size());
        }
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
            var result = vm.evaluate(com.bloxbean.cardano.plutus.core.Program.plutusV3(uplc));
            assertTrue(result.isSuccess(), "Expected success: " + result);
        }
    }

    @Nested
    class CompilerIntegrationTests {
        @Test
        void compilesValidatorWithSealedInterface() {
            var source = """
                import java.math.BigInteger;

                sealed interface Action permits Mint, Burn {}
                record Mint(int amt) implements Action {}
                record Burn(int amt) implements Action {}

                @Validator
                class MyValidator {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, BigInteger ctx) {
                        return redeemer > 0;
                    }
                }
                """;
            var result = new PlutusCompiler().compile(source);
            assertNotNull(result.program());
            assertFalse(result.hasErrors());
        }

        @Test
        void constructsVariantInValidator() {
            var source = """
                import java.math.BigInteger;

                sealed interface Action permits Mint, Burn {}
                record Mint(int amt) implements Action {}
                record Burn(int amt) implements Action {}

                @Validator
                class MyValidator {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, BigInteger ctx) {
                        var action = new Mint(10);
                        return true;
                    }
                }
                """;
            var result = new PlutusCompiler().compile(source);
            assertNotNull(result.program());
        }

        @Test
        void differentFieldCountsPerVariant() {
            var source = """
                import java.math.BigInteger;

                sealed interface Redeemer permits Pay, Refund, Cancel {}
                record Pay(int amount, int fee) implements Redeemer {}
                record Refund(int reason) implements Redeemer {}
                record Cancel() implements Redeemer {}

                @Validator
                class MyValidator {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, BigInteger ctx) {
                        return true;
                    }
                }
                """;
            var result = new PlutusCompiler().compile(source);
            assertNotNull(result.program());
        }

        @Test
        void variantUsedInHelper() {
            var source = """
                import java.math.BigInteger;

                sealed interface Action permits Mint, Burn {}
                record Mint(int amt) implements Action {}
                record Burn(int amt) implements Action {}

                @Validator
                class MyValidator {
                    static boolean isMint() {
                        var m = new Mint(10);
                        return true;
                    }

                    @Entrypoint
                    static boolean validate(BigInteger redeemer, BigInteger ctx) {
                        return isMint();
                    }
                }
                """;
            var result = new PlutusCompiler().compile(source);
            assertNotNull(result.program());
        }
    }
}
