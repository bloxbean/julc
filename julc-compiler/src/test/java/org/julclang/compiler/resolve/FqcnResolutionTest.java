package org.julclang.compiler.resolve;

import org.julclang.compiler.CompilerException;
import org.julclang.compiler.JulcCompiler;
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
                    record MyDatum(java.math.BigInteger value) {}
                    @SpendingValidator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(MyDatum datum, PlutusData redeemer, PlutusData ctx) {
                            return datum.value() > 0;
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
                    record Bid(java.math.BigInteger amount) {}
                    @SpendingValidator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(Bid datum, PlutusData redeemer, PlutusData ctx) {
                            return datum.amount() > 0;
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
                        static boolean isPositive(long x) { return x > 0; }
                    }
                    """;
            var validator = """
                    package com.myapp;
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
                    record Token(java.math.BigInteger amount) {}
                    @SpendingValidator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(Token datum, PlutusData redeemer, PlutusData ctx) {
                            return datum.amount() > 0;
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
                        static boolean check(long x) { return x > 0; }
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
            var ex = assertThrows(CompilerException.class,
                    () -> new JulcCompiler(STDLIB).compile(source1, List.of(source2)));
            assertTrue(ex.getMessage().contains("Duplicate record type"),
                    "Should detect duplicate FQCN. Got: " + ex.getMessage());
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
            var cu = com.github.javaparser.StaticJavaParser.parse("""
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
            var cu = com.github.javaparser.StaticJavaParser.parse("""
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
            var cu = com.github.javaparser.StaticJavaParser.parse("""
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
            var cu = com.github.javaparser.StaticJavaParser.parse("""
                    package com.myapp;
                    class Foo {}
                    """);
            var resolver = new ImportResolver(cu, knownFqcns);
            // Unknown type returns simple name as fallback
            assertEquals("UnknownType", resolver.resolve("UnknownType"));
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
