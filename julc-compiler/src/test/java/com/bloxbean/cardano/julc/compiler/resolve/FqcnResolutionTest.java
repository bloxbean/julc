package com.bloxbean.cardano.julc.compiler.resolve;

import com.bloxbean.cardano.julc.compiler.CompilerException;
import com.bloxbean.cardano.julc.compiler.JulcCompiler;
import com.bloxbean.cardano.julc.stdlib.StdlibRegistry;
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
                    @Validator
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
                    @Validator
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
                    @Validator
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
                    @Validator
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
                    import com.bloxbean.cardano.julc.stdlib.annotation.OnchainLibrary;
                    @OnchainLibrary
                    public class MyUtils {
                        static boolean isPositive(long x) { return x > 0; }
                    }
                    """;
            var validator = """
                    package com.myapp;
                    @Validator
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
                    import com.bloxbean.cardano.julc.stdlib.annotation.OnchainLibrary;
                    @OnchainLibrary
                    public class TokenLib {
                        record Token(byte[] id) {}
                        static boolean hasId(long len) { return len > 0; }
                    }
                    """;
            var validator = """
                    package com.myapp;
                    record Token(java.math.BigInteger amount) {}
                    @Validator
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
                    import com.bloxbean.cardano.julc.stdlib.annotation.OnchainLibrary;
                    @OnchainLibrary
                    public class Utils {
                        static boolean check(long x) { return x > 0; }
                    }
                    """;
            var lib2 = """
                    package com.b;
                    import com.bloxbean.cardano.julc.stdlib.annotation.OnchainLibrary;
                    @OnchainLibrary
                    public class Utils {
                        static boolean verify(long x) { return x > 0; }
                    }
                    """;
            var validator = """
                    package com.myapp;
                    import com.a.Utils;
                    @Validator
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
                    @Validator
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
        void stdlibCollisionThrowsError() {
            // Library class with same name as stdlib class (e.g., "Builtins") → error
            var lib = """
                    package com.evil;
                    import com.bloxbean.cardano.julc.stdlib.annotation.OnchainLibrary;
                    @OnchainLibrary
                    public class Builtins {
                        static boolean fake(long x) { return x > 0; }
                    }
                    """;
            var validator = """
                    package com.myapp;
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            return true;
                        }
                    }
                    """;
            var ex = assertThrows(CompilerException.class,
                    () -> new JulcCompiler(STDLIB).compile(validator, List.of(lib)));
            assertTrue(ex.getMessage().contains("same simple name") || ex.getMessage().contains("shadow"),
                    "Should detect stdlib collision. Got: " + ex.getMessage());
        }
    }

    @Nested
    class ImportResolverTests {

        @Test
        void explicitImportResolvesCorrectly() {
            var knownFqcns = java.util.Set.of(
                    "com.bloxbean.cardano.julc.ledger.Value",
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
            assertEquals("com.bloxbean.cardano.julc.ledger.Value", resolver.resolve("Value"));
        }

        @Test
        void samePackageResolvesFirst() {
            var knownFqcns = java.util.Set.of(
                    "com.bloxbean.cardano.julc.ledger.Value",
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
            var knownFqcns = java.util.Set.of("com.bloxbean.cardano.julc.ledger.Value");
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
            var knownFqcns = java.util.Set.of("com.bloxbean.cardano.julc.ledger.TxInfo");
            var resolver = new ImportResolver(knownFqcns);
            // Implicit ledger import should work
            assertEquals("com.bloxbean.cardano.julc.ledger.TxInfo", resolver.resolve("TxInfo"));
        }
    }

    @Nested
    class LibraryMethodResolution {

        @Test
        void libraryMethodsByFqcn() {
            var registry = new LibraryMethodRegistry();
            var pirType = new com.bloxbean.cardano.julc.compiler.pir.PirType.FunType(
                    new com.bloxbean.cardano.julc.compiler.pir.PirType.IntegerType(),
                    new com.bloxbean.cardano.julc.compiler.pir.PirType.BoolType());
            var body = new com.bloxbean.cardano.julc.compiler.pir.PirTerm.Lam("x", pirType,
                    new com.bloxbean.cardano.julc.compiler.pir.PirTerm.Const(
                            com.bloxbean.cardano.julc.core.Constant.bool(true)));
            registry.register("com.mylib.Utils", "check", pirType, body);

            // Lookup by FQCN works
            var resultFqcn = registry.lookup("com.mylib.Utils", "check", List.of(
                    new com.bloxbean.cardano.julc.compiler.pir.PirTerm.Const(
                            com.bloxbean.cardano.julc.core.Constant.integer(42))));
            assertTrue(resultFqcn.isPresent(), "Should find method by FQCN");

            // Lookup by simple name also works (via classNameIndex)
            var resultSimple = registry.lookup("Utils", "check", List.of(
                    new com.bloxbean.cardano.julc.compiler.pir.PirTerm.Const(
                            com.bloxbean.cardano.julc.core.Constant.integer(42))));
            assertTrue(resultSimple.isPresent(), "Should find method by simple name");
        }

        @Test
        void ambiguousLibraryClassThrowsError() {
            var registry = new LibraryMethodRegistry();
            var pirType = new com.bloxbean.cardano.julc.compiler.pir.PirType.FunType(
                    new com.bloxbean.cardano.julc.compiler.pir.PirType.IntegerType(),
                    new com.bloxbean.cardano.julc.compiler.pir.PirType.BoolType());
            var body = new com.bloxbean.cardano.julc.compiler.pir.PirTerm.Lam("x", pirType,
                    new com.bloxbean.cardano.julc.compiler.pir.PirTerm.Const(
                            com.bloxbean.cardano.julc.core.Constant.bool(true)));
            registry.register("com.a.Utils", "check", pirType, body);
            registry.register("com.b.Utils", "verify", pirType, body);

            // Simple name "Utils" is ambiguous — two FQCNs
            var ex = assertThrows(CompilerException.class,
                    () -> registry.lookup("Utils", "check", List.of(
                            new com.bloxbean.cardano.julc.compiler.pir.PirTerm.Const(
                                    com.bloxbean.cardano.julc.core.Constant.integer(42)))));
            assertTrue(ex.getMessage().contains("Ambiguous library class"),
                    "Should detect ambiguous library class. Got: " + ex.getMessage());
        }
    }
}
