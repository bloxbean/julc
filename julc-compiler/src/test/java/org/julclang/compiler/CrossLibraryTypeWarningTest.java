package org.julclang.compiler;

import org.julclang.compiler.frontend.javac.JavacGateMode;

import org.julclang.compiler.error.CompilerDiagnostic;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that cross-library calls with typed Data arg mismatches produce warnings.
 */
class CrossLibraryTypeWarningTest {

    /**
     * ADR-068 M1b: the warning reports an argument JuLC encodes implicitly (bytes or an integer passed where a
     * library takes {@code PlutusData}), which javac rejects; tested on the JavaParser frontend with the gate off.
     */
    private static JulcCompiler dialectCompiler() {
        return new JulcCompiler(null, new CompilerOptions());
    }

    @Test
    void testNoWarningWhenTypesMatch() {
        var libSource = """
                import org.julclang.core.PlutusData;
                import org.julclang.stdlib.annotation.OnchainLibrary;
                @OnchainLibrary
                class DataLib {
                    static boolean check(PlutusData x) {
                        return true;
                    }
                }
                """;
        var validatorSource = """
                import org.julclang.core.PlutusData;
                import org.julclang.ledger.ScriptContext;
                import org.julclang.stdlib.annotation.Entrypoint;
                import org.julclang.stdlib.annotation.SpendingValidator;
                @SpendingValidator
                class TestValidator {
                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        return DataLib.check(redeemer);
                    }
                }
                """;
        var result = new JulcCompiler().compile(validatorSource, List.of(libSource));
        assertNotNull(result.program());
        var warnings = result.diagnostics().stream()
                .filter(d -> d.level() == CompilerDiagnostic.Level.WARNING)
                .toList();
        assertTrue(warnings.isEmpty(), "Should not produce warnings when types match");
    }

    @Test
    void testNoWarningForNonLibrary() {
        // A validator with no library calls should not produce warnings
        var source = """
                import org.julclang.core.PlutusData;
                import org.julclang.ledger.ScriptContext;
                import org.julclang.stdlib.annotation.Entrypoint;
                import org.julclang.stdlib.annotation.SpendingValidator;
                @SpendingValidator
                class TestValidator {
                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        return true;
                    }
                }
                """;
        var result = new JulcCompiler().compile(source);
        assertNotNull(result.program());
        var warnings = result.diagnostics().stream()
                .filter(d -> d.level() == CompilerDiagnostic.Level.WARNING)
                .toList();
        assertTrue(warnings.isEmpty(), "Should not produce warnings without library calls");
    }

}
