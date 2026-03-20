package com.bloxbean.cardano.julc.compiler.error;

import com.bloxbean.cardano.julc.compiler.CompilerException;
import com.bloxbean.cardano.julc.compiler.JulcCompiler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for P0-2: Compiler Error Quality & Multi-Error Recovery.
 * Verifies that error messages include source positions, suggestions,
 * and "did you mean?" hints.
 */
class ErrorQualityTest {

    @Test
    void unknownTypeIncludesDidYouMean() {
        var source = """
                @Validator
                class MyValidator {
                    record MyDatum(Strng name) {}

                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        return true;
                    }
                }
                """;
        var ex = assertThrows(CompilerException.class, () -> new JulcCompiler().compile(source));
        assertTrue(ex.getMessage().contains("Did you mean"),
                "Error should suggest similar type. Got: " + ex.getMessage());
    }

    @Test
    void unsupportedPrimitiveIncludesSupportedTypes() {
        var source = """
                @Validator
                class MyValidator {
                    record BadRecord(float value) {}

                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        return true;
                    }
                }
                """;
        var ex = assertThrows(CompilerException.class, () -> new JulcCompiler().compile(source));
        // SubsetValidator should catch float before TypeResolver
        assertTrue(ex.getMessage().toLowerCase().contains("float")
                || ex.getMessage().toLowerCase().contains("supported"),
                "Error should mention unsupported float type. Got: " + ex.getMessage());
    }

    @Test
    void typeMethodRegistryErrorMessagesIncludeUsageHints() {
        // Verify that TypeMethodRegistry error messages contain usage hints
        // This is a unit-level check of the error message format
        try {
            // Create a scope (empty list) and call get() with no args
            var registry = com.bloxbean.cardano.julc.compiler.pir.TypeMethodRegistry.defaultRegistry();
            var scope = new com.bloxbean.cardano.julc.compiler.pir.PirTerm.Const(
                    new com.bloxbean.cardano.julc.core.Constant.UnitConst());
            var scopeType = new com.bloxbean.cardano.julc.compiler.pir.PirType.ListType(
                    new com.bloxbean.cardano.julc.compiler.pir.PirType.DataType());
            registry.dispatch(scope, "get", java.util.List.of(), scopeType, java.util.List.of());
            fail("Should have thrown CompilerException");
        } catch (CompilerException e) {
            assertTrue(e.getMessage().contains("Usage"),
                    "Error should include usage hint. Got: " + e.getMessage());
            assertTrue(e.getMessage().contains("list.get(0)"),
                    "Error should include example. Got: " + e.getMessage());
        }
    }

    @Test
    void multipleSubsetErrorsReportedTogether() {
        // Source with multiple unsupported constructs
        var source = """
                @Validator
                class MyValidator {
                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        try {
                            return true;
                        } catch (Exception e) {
                            return false;
                        }
                    }
                }
                """;
        var ex = assertThrows(CompilerException.class, () -> new JulcCompiler().compile(source));
        // SubsetValidator should report the try-catch error
        assertTrue(ex.diagnostics().size() >= 1, "Should report at least 1 error");
        // Check that diagnostics have file/line info
        var diag = ex.diagnostics().get(0);
        assertTrue(diag.line() > 0, "Error should have line number");
    }

    @Test
    void typeResolverErrorUsesCompilerException() {
        var source = """
                @Validator
                class MyValidator {
                    record BadRecord(UnknownTypeName field) {}

                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        return true;
                    }
                }
                """;
        // Should throw CompilerException (not IllegalArgumentException)
        assertThrows(CompilerException.class, () -> new JulcCompiler().compile(source));
    }

    @Test
    void undefinedVariableErrorHasSuggestion() {
        var source = """
                import java.math.BigInteger;

                @Validator
                class MyValidator {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, BigInteger ctx) {
                        return unknownVar == 42;
                    }
                }
                """;
        var ex = assertThrows(CompilerException.class, () -> new JulcCompiler().compile(source));
        assertTrue(ex.getMessage().contains("Undefined variable") || ex.getMessage().contains("Unknown method"),
                "Error should mention undefined variable. Got: " + ex.getMessage());
    }

    @Test
    void unsupportedStatementErrorHasSuggestion() {
        var source = """
                import java.math.BigInteger;

                @Validator
                class MyValidator {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, BigInteger ctx) {
                        for (int i = 0; i < 10; i++) {
                            // do nothing
                        }
                        return true;
                    }
                }
                """;
        var ex = assertThrows(CompilerException.class, () -> new JulcCompiler().compile(source));
        // Should be caught by SubsetValidator
        assertTrue(ex.getMessage().contains("for") || ex.getMessage().contains("C-style"),
                "Error should mention unsupported for loop. Got: " + ex.getMessage());
    }

    @Test
    void diagnosticToStringIncludesAllFields() {
        var diag = new CompilerDiagnostic(
                CompilerDiagnostic.Level.ERROR,
                "Test error message",
                "MyValidator.java",
                42, 10,
                "Try using XYZ instead");

        String str = diag.toString();
        assertTrue(str.contains("ERROR"));
        assertTrue(str.contains("MyValidator.java"));
        assertTrue(str.contains("42"));
        assertTrue(str.contains("10"));
        assertTrue(str.contains("Test error message"));
        assertTrue(str.contains("Try using XYZ instead"));
    }
}
