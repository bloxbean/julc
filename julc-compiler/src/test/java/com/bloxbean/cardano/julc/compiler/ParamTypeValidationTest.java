package com.bloxbean.cardano.julc.compiler;

import com.bloxbean.cardano.julc.compiler.error.CompilerDiagnostic;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that @Param with typed Data subtypes (BytesData, MapData, ListData, IntData)
 * is rejected with a clear error message.
 */
class ParamTypeValidationTest {

    private static final String VALIDATOR_TEMPLATE = """
            @Validator
            class TestValidator {
                @Param %s x;

                @Entrypoint
                static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                    return true;
                }
            }
            """;

    private CompilerException expectCompileError(String paramType) {
        var source = VALIDATOR_TEMPLATE.formatted(paramType);
        return assertThrows(CompilerException.class, () -> new JulcCompiler().compile(source));
    }

    @Test
    void testParamBytesDataRejected() {
        var ex = expectCompileError("PlutusData.BytesData");
        assertTrue(ex.diagnostics().stream().anyMatch(CompilerDiagnostic::isError));
        assertTrue(ex.getMessage().contains("BytesData"));
    }

    @Test
    void testParamMapDataRejected() {
        var ex = expectCompileError("PlutusData.MapData");
        assertTrue(ex.diagnostics().stream().anyMatch(CompilerDiagnostic::isError));
        assertTrue(ex.getMessage().contains("MapData"));
    }

    @Test
    void testParamListDataRejected() {
        var ex = expectCompileError("PlutusData.ListData");
        assertTrue(ex.diagnostics().stream().anyMatch(CompilerDiagnostic::isError));
        assertTrue(ex.getMessage().contains("ListData"));
    }

    @Test
    void testParamIntDataRejected() {
        var ex = expectCompileError("PlutusData.IntData");
        assertTrue(ex.diagnostics().stream().anyMatch(CompilerDiagnostic::isError));
        assertTrue(ex.getMessage().contains("IntData"));
    }

    @Test
    void testParamShortFormBytesDataRejected() {
        var ex = expectCompileError("BytesData");
        assertTrue(ex.diagnostics().stream().anyMatch(CompilerDiagnostic::isError));
        assertTrue(ex.getMessage().contains("BytesData"));
    }

    @Test
    void testParamPlutusDataAccepted() {
        var source = VALIDATOR_TEMPLATE.formatted("PlutusData");
        var result = new JulcCompiler().compile(source);
        assertNotNull(result.program());
        assertFalse(result.hasErrors());
    }

    @Test
    void testParamBigIntegerAccepted() {
        var source = """
                import java.math.BigInteger;

                @Validator
                class TestValidator {
                    @Param BigInteger x;

                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        return x > 0;
                    }
                }
                """;
        var result = new JulcCompiler().compile(source);
        assertNotNull(result.program());
        assertFalse(result.hasErrors());
    }

    @Test
    void testParamByteArrayAccepted() {
        var source = VALIDATOR_TEMPLATE.formatted("byte[]");
        var result = new JulcCompiler().compile(source);
        assertNotNull(result.program());
        assertFalse(result.hasErrors());
    }

    @Test
    void testErrorMessageHasSuggestion() {
        var ex = expectCompileError("PlutusData.BytesData");
        var errorDiag = ex.diagnostics().stream()
                .filter(CompilerDiagnostic::isError)
                .findFirst()
                .orElseThrow();
        assertTrue(errorDiag.hasSuggestion());
        assertTrue(errorDiag.suggestion().contains("PlutusData"));
    }

    @Test
    void testMultipleBadParamsReportsAll() {
        var source = """
                @Validator
                class TestValidator {
                    @Param PlutusData.BytesData a;
                    @Param PlutusData.IntData b;

                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        return true;
                    }
                }
                """;
        var ex = assertThrows(CompilerException.class, () -> new JulcCompiler().compile(source));
        var errors = ex.diagnostics().stream()
                .filter(CompilerDiagnostic::isError)
                .toList();
        assertEquals(2, errors.size(), "Should report both bad @Param fields");
    }
}
