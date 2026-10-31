package org.julclang.compiler.resolve;

import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.JavaSourceParser;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.frontend.javac.JavacGateMode;
import org.julclang.stdlib.StdlibRegistry;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for FQCN (fully qualified class name) type resolution.
 * Verifies that types in different packages with the same simple name
 * are resolved correctly, and that backward compatibility is maintained
 * for packageless inline code.
 */
class FqcnResolutionTest {

    static final StdlibRegistry STDLIB = StdlibRegistry.defaultRegistry();

    @Nested
    class BackwardCompatibility {

        @Test
        void inlineCodeWithoutPackageStillWorks() {
            // Packageless inline code should still work: TxInfo, ScriptContext resolved
            // via implicit ledger import, user records via simple name = FQCN
            var source = """
                    import org.julclang.core.PlutusData;
                    import org.julclang.ledger.ScriptContext;
                    import org.julclang.ledger.TxInfo;
                    import org.julclang.stdlib.annotation.Entrypoint;
                    import org.julclang.stdlib.annotation.SpendingValidator;
                    record Bid(java.math.BigInteger amount) {}
                    @SpendingValidator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            TxInfo txInfo = ctx.txInfo();
                            return true;
                        }
                    }
                    """;
            var result = new JulcCompiler(STDLIB).compile(source);
            assertFalse(result.hasErrors(), "Inline code should compile. Errors: " + result);
            assertNotNull(result.program());
        }

        @Test
        void inlineCodeWithUserRecordStillWorks() {
            // User record in inline code (no package) should resolve correctly
            var source = """
                    import org.julclang.core.PlutusData;
                    import org.julclang.stdlib.annotation.Entrypoint;
                    import org.julclang.stdlib.annotation.SpendingValidator;
                    import java.math.BigInteger;
                    record MyDatum(java.math.BigInteger value) {}
                    @SpendingValidator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(MyDatum datum, PlutusData redeemer, PlutusData ctx) {
                            return datum.value().compareTo(BigInteger.ZERO) > 0;
                        }
                    }
                    """;
            var result = new JulcCompiler(STDLIB).compile(source);
            assertFalse(result.hasErrors(), "Inline code with user record should compile. Errors: " + result);
        }

        @Test
        void ledgerTypesWithoutExplicitImportResolve() {
            // Ledger types (Value, Address, etc.) should resolve without explicit imports
            var source = """
                    package com.myapp;
                    import org.julclang.core.PlutusData;
                    import org.julclang.ledger.ScriptContext;
                    import org.julclang.ledger.TxInfo;
                    import org.julclang.stdlib.annotation.Entrypoint;
                    import org.julclang.stdlib.annotation.SpendingValidator;
                    @SpendingValidator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            TxInfo txInfo = ctx.txInfo();
                            return true;
                        }
                    }
                    """;
            var result = new JulcCompiler(STDLIB).compile(source);
            assertFalse(result.hasErrors(), "Ledger types should resolve via implicit import. Errors: " + result);
        }
    }

    @Nested
    class PackagedCode {

        @Test
        void packagedValidatorCompiles() {
            var source = """
                    package com.myapp;
                    import org.julclang.core.PlutusData;
                    import org.julclang.stdlib.annotation.Entrypoint;
                    import org.julclang.stdlib.annotation.SpendingValidator;
                    import java.math.BigInteger;
                    record Bid(java.math.BigInteger amount) {}
                    @SpendingValidator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(Bid datum, PlutusData redeemer, PlutusData ctx) {
                            return datum.amount().compareTo(BigInteger.ZERO) > 0;
                        }
                    }
                    """;
            var result = new JulcCompiler(STDLIB).compile(source);
            assertFalse(result.hasErrors(), "Packaged validator should compile. Errors: " + result);
        }

        @Test
        void packagedValidatorWithLibraryCompiles() {
            var lib = """
                    package com.mylib;
                    import org.julclang.stdlib.annotation.OnchainLibrary;
                    @OnchainLibrary
                    public class MyUtils {
                        public static boolean isPositive(long x) { return x > 0; }
                    }
                    """;
            var validator = """
                    package com.myapp;
                    import com.mylib.MyUtils;
                    import org.julclang.core.PlutusData;
                    import org.julclang.stdlib.annotation.Entrypoint;
                    import org.julclang.stdlib.annotation.SpendingValidator;
                    @SpendingValidator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            return MyUtils.isPositive(42);
                        }
                    }
                    """;
            var result = new JulcCompiler(STDLIB).compile(validator, List.of(lib));
            assertFalse(result.hasErrors(), "Packaged validator with library should compile. Errors: " + result);
        }
    }

    @Nested
    class SameSimpleNameDifferentPackages {

        @Test
        void sameRecordNameDifferentPackagesCompiles() {
            // Two record types named "Token" in different packages.
            // Library defines its own Token, validator defines its own Token.
            // Each resolves to its own package.
            var lib = """
                    package com.thirdparty;
                    import org.julclang.stdlib.annotation.OnchainLibrary;
                    @OnchainLibrary
                    public class TokenLib {
                        record Token(byte[] id) {}
                        static boolean hasId(long len) { return len > 0; }
                    }
                    """;
            var validator = """
                    package com.myapp;
                    import org.julclang.core.PlutusData;
                    import org.julclang.stdlib.annotation.Entrypoint;
                    import org.julclang.stdlib.annotation.SpendingValidator;
                    import java.math.BigInteger;
                    record Token(java.math.BigInteger amount) {}
                    @SpendingValidator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(Token datum, PlutusData redeemer, PlutusData ctx) {
                            return datum.amount().compareTo(BigInteger.ZERO) > 0;
                        }
                    }
                    """;
            var result = new JulcCompiler(STDLIB).compile(validator, List.of(lib));
            assertFalse(result.hasErrors(), "Same record name in different packages should compile. Errors: " + result);
        }

        @Test
        void sameLibraryClassNameDifferentPackages() {
            // Two library classes named "Utils" in different packages.
            // Validator uses explicit import to disambiguate.
            var lib1 = """
                    package com.a;
                    import org.julclang.stdlib.annotation.OnchainLibrary;
                    @OnchainLibrary
                    public class Utils {
                        public static boolean check(long x) { return x > 0; }
                    }
                    """;
            var lib2 = """
                    package com.b;
                    import org.julclang.stdlib.annotation.OnchainLibrary;
                    @OnchainLibrary
                    public class Utils {
                        static boolean verify(long x) { return x > 0; }
                    }
                    """;
            var validator = """
                    package com.myapp;
                    import com.a.Utils;
                    import org.julclang.core.PlutusData;
                    import org.julclang.stdlib.annotation.Entrypoint;
                    import org.julclang.stdlib.annotation.SpendingValidator;
                    @SpendingValidator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            return Utils.check(42);
                        }
                    }
                    """;
            var result = new JulcCompiler(STDLIB).compile(validator, List.of(lib1, lib2));
            assertFalse(result.hasErrors(), "Same library class name with explicit import should compile. Errors: " + result);
        }
    }

    @Nested
    class CollisionDetection {

        @Test
        void duplicateRecordFqcnThrowsError() {
            // Two records with exact same FQCN → error
            var source1 = """
                    package com.myapp;
                    import org.julclang.core.PlutusData;
                    import org.julclang.stdlib.annotation.Entrypoint;
                    import org.julclang.stdlib.annotation.SpendingValidator;
                    record Bid(java.math.BigInteger amount) {}
                    @SpendingValidator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            return true;
                        }
                    }
                    """;
            var source2 = """
                    package com.myapp;
                    record Bid(byte[] hash) {}
                    """;
            // JuLC's own rule, as a host without the gate sees it.
            var off = new CompilerOptions().setJavacGate(JavacGateMode.OFF);
            var ex = assertThrows(CompilerException.class,
                    () -> new JulcCompiler(STDLIB, off).compile(source1, List.of(source2)));
            assertTrue(ex.getMessage().contains("Duplicate record type"),
                    "Should detect duplicate FQCN. Got: " + ex.getMessage());
            // With the gate, ADR-068 D8 gives the root's com.myapp.Bid precedence and leaves the library unit out.
            var enforced = new JulcCompiler(STDLIB).compileWithDetails(source1, List.of(source2));
            assertFalse(enforced.hasErrors(), enforced.diagnostics().toString());
            // Two library units of equal standing that declare com.myapp.Bid are rejected before javac runs.
            var source3 = """
                    package com.myapp;
                    record Bid(java.math.BigInteger price) {}
                    """;
            var noBid = source1.replace("record Bid(java.math.BigInteger amount) {}\n", "");
            var twice = assertThrows(CompilerException.class,
                    () -> new JulcCompiler(STDLIB).compile(noBid, List.of(source2, source3)));
            assertTrue(twice.getMessage().contains("(compiler.err.duplicate.class)"), twice.getMessage());
        }

        @Test
        void differentFqcnNoCollision() {
            // Library class with same simple name as stdlib class but different FQCN → no collision
            // com.evil.Builtins is a different class from org.julclang.stdlib.Builtins
            var lib = """
                    package com.evil;
                    import org.julclang.stdlib.annotation.OnchainLibrary;
                    @OnchainLibrary
                    public class Builtins {
                        public static boolean fake(long x) { return x > 0; }
                    }
                    """;
            var validator = """
                    package com.myapp;
                    import org.julclang.core.PlutusData;
                    import org.julclang.stdlib.annotation.Entrypoint;
                    import org.julclang.stdlib.annotation.SpendingValidator;
                    @SpendingValidator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            return true;
                        }
                    }
                    """;
            // With FQCN-based resolution, different packages → no collision
            var result = new JulcCompiler(STDLIB).compile(validator, List.of(lib));
            assertFalse(result.hasErrors(), "Different FQCN should not collide: " + result);
        }
    }

    @Nested
    class ImportResolverTests {

        @Test
        void explicitImportResolvesCorrectly() {
            var knownFqcns = java.util.Set.of(
                    "org.julclang.ledger.Value",
                    "com.myapp.Token",
                    "com.thirdparty.Token");
            var cu = JavaSourceParser.parseCompilationUnit("""
                    package com.myapp;
                    import com.thirdparty.Token;
                    class Foo {}
                    """);
            var resolver = new ImportResolver(cu, knownFqcns);
            // Explicit import should win
            assertEquals("com.thirdparty.Token", resolver.resolve("Token"));
            // Value resolves via implicit ledger wildcard
            assertEquals("org.julclang.ledger.Value", resolver.resolve("Value"));
        }

        @Test
        void samePackageResolvesFirst() {
            var knownFqcns = java.util.Set.of(
                    "org.julclang.ledger.Value",
                    "com.myapp.Token");
            var cu = JavaSourceParser.parseCompilationUnit("""
                    package com.myapp;
                    class Foo {}
                    """);
            var resolver = new ImportResolver(cu, knownFqcns);
            // Same-package type resolves without import
            assertEquals("com.myapp.Token", resolver.resolve("Token"));
        }

        @Test
        void ambiguousWildcardThrowsError() {
            var knownFqcns = java.util.Set.of(
                    "com.a.Token",
                    "com.b.Token");
            var cu = JavaSourceParser.parseCompilationUnit("""
                    package com.myapp;
                    import com.a.*;
                    import com.b.*;
                    class Foo {}
                    """);
            var resolver = new ImportResolver(cu, knownFqcns);
            var ex = assertThrows(CompilerException.class, () -> resolver.resolve("Token"));
            assertTrue(ex.getMessage().contains("Ambiguous type"),
                    "Should detect ambiguous type. Got: " + ex.getMessage());
        }

        @Test
        void unknownTypeReturnsFallback() {
            var knownFqcns = java.util.Set.of("org.julclang.ledger.Value");
            var cu = JavaSourceParser.parseCompilationUnit("""
                    package com.myapp;
                    class Foo {}
                    """);
            var resolver = new ImportResolver(cu, knownFqcns);
            // Unknown type returns simple name as fallback
            assertEquals("UnknownType", resolver.resolve("UnknownType"));
        }

        @Test
        void explicitImportWinsOverWildcard() {
            var knownFqcns = java.util.Set.of(
                    "org.julclang.ledger.Value",
                    "com.thirdparty.Value");
            var cu = JavaSourceParser.parseCompilationUnit("""
                    package com.myapp;
                    import com.thirdparty.Value;
                    class Foo {}
                    """);
            assertEquals("com.thirdparty.Value", new ImportResolver(cu, knownFqcns).resolve("Value"));
        }

        @Test
        void memberTypeShadowsImports() {
            var knownFqcns = java.util.Set.of(
                    "org.julclang.ledger.Constitution",
                    "com.thirdparty.Value",
                    "com.myapp.Foo.Constitution",
                    "com.myapp.Foo.Value");
            var cu = JavaSourceParser.parseCompilationUnit("""
                    package com.myapp;
                    import com.thirdparty.Value;
                    class Foo {
                        record Constitution(java.math.BigInteger value) {}
                        record Value(java.math.BigInteger value) {}
                    }
                    """);
            var resolver = new ImportResolver(cu.getType(0), knownFqcns);
            assertEquals("com.myapp.Foo.Constitution", resolver.resolve("Constitution"));
            assertEquals("com.myapp.Foo.Value", resolver.resolve("Value"));
            // Without a scope type only the imports apply
            assertEquals("com.thirdparty.Value", new ImportResolver(cu, knownFqcns).resolve("Value"));
        }

        @Test
        void memberTypesAreScopedToTheEnclosingTypes() {
            var knownFqcns = java.util.Set.of(
                    "org.julclang.ledger.Constitution",
                    "com.myapp.A.Constitution",
                    "com.myapp.A.Inner",
                    "com.myapp.A.Inner.Constitution",
                    "com.myapp.B.Other");
            var cu = JavaSourceParser.parseCompilationUnit("""
                    package com.myapp;
                    class A {
                        record Constitution(java.math.BigInteger value) {}
                        record Inner(java.math.BigInteger value) {
                            record Constitution(byte[] value) {}
                        }
                    }
                    class B { record Other() {} }
                    """);
            var a = cu.getType(0);
            var inner = (com.github.javaparser.ast.body.TypeDeclaration<?>) a.getMember(1);
            assertEquals("com.myapp.A.Constitution", new ImportResolver(a, knownFqcns).resolve("Constitution"));
            assertEquals("com.myapp.A.Inner.Constitution",
                    new ImportResolver(inner, knownFqcns).resolve("Constitution"));
            assertEquals("org.julclang.ledger.Constitution",
                    new ImportResolver(cu.getType(1), knownFqcns).resolve("Constitution"));
        }

        @Test
        void noPackageCodeResolves() {
            var knownFqcns = java.util.Set.of("org.julclang.ledger.TxInfo");
            var resolver = new ImportResolver(knownFqcns);
            // Implicit ledger import should work
            assertEquals("org.julclang.ledger.TxInfo", resolver.resolve("TxInfo"));
        }
    }

    @Nested
    class LibraryMethodResolution {

        @Test
        void libraryMethodsByFqcn() {
            var registry = new LibraryMethodRegistry();
            var pirType = new org.julclang.compiler.pir.PirType.FunType(
                    new org.julclang.compiler.pir.PirType.IntegerType(),
                    new org.julclang.compiler.pir.PirType.BoolType());
            var body = new org.julclang.compiler.pir.PirTerm.Lam("x", pirType,
                    new org.julclang.compiler.pir.PirTerm.Const(
                            org.julclang.core.Constant.bool(true)));
            registry.register("com.mylib.Utils", "check", pirType, body);

            // Lookup by FQCN works
            var resultFqcn = registry.lookup("com.mylib.Utils", "check", List.of(
                    new org.julclang.compiler.pir.PirTerm.Const(
                            org.julclang.core.Constant.integer(42))));
            assertTrue(resultFqcn.isPresent(), "Should find method by FQCN");

            // Lookup by simple name also works (via classNameIndex)
            var resultSimple = registry.lookup("Utils", "check", List.of(
                    new org.julclang.compiler.pir.PirTerm.Const(
                            org.julclang.core.Constant.integer(42))));
            assertTrue(resultSimple.isPresent(), "Should find method by simple name");
        }

        @Test
        void ambiguousLibraryClassThrowsError() {
            var registry = new LibraryMethodRegistry();
            var pirType = new org.julclang.compiler.pir.PirType.FunType(
                    new org.julclang.compiler.pir.PirType.IntegerType(),
                    new org.julclang.compiler.pir.PirType.BoolType());
            var body = new org.julclang.compiler.pir.PirTerm.Lam("x", pirType,
                    new org.julclang.compiler.pir.PirTerm.Const(
                            org.julclang.core.Constant.bool(true)));
            registry.register("com.a.Utils", "check", pirType, body);
            registry.register("com.b.Utils", "verify", pirType, body);

            // Simple name "Utils" is ambiguous — two FQCNs
            var ex = assertThrows(CompilerException.class,
                    () -> registry.lookup("Utils", "check", List.of(
                            new org.julclang.compiler.pir.PirTerm.Const(
                                    org.julclang.core.Constant.integer(42)))));
            assertTrue(ex.getMessage().contains("Ambiguous library class"),
                    "Should detect ambiguous library class. Got: " + ex.getMessage());
        }
    }
}
