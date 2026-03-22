package com.bloxbean.cardano.julc.crl;

import com.bloxbean.cardano.julc.core.Program;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Shared test helpers for CRL test classes.
 */
final class CrlTestSupport {

    private CrlTestSupport() {}

    /**
     * Compile CRL source to a UPLC Program, asserting success.
     */
    static Program compileCrl(String crlSource) {
        var compiler = new CrlCompiler();
        var result = compiler.compile(crlSource, "test.crl");
        assertFalse(result.hasErrors(),
                () -> "Compilation failed.\nCRL diagnostics: " + result.crlDiagnostics()
                        + "\nGenerated Java:\n" + result.generatedJavaSource()
                        + (result.compileResult() != null
                        ? "\nJulc diagnostics: " + result.compileResult().diagnostics() : ""));
        assertNotNull(result.compileResult(),
                () -> "compileResult is null. Generated Java:\n" + result.generatedJavaSource());
        assertNotNull(result.compileResult().program(),
                () -> "program is null. Julc diagnostics: " + result.compileResult().diagnostics()
                        + "\nGenerated Java:\n" + result.generatedJavaSource());
        return result.compileResult().program();
    }

    /**
     * Load a CRL example file from test resources.
     */
    static String loadCrl(String filename) {
        try (var is = CrlTestSupport.class.getResourceAsStream("/examples/" + filename)) {
            assertNotNull(is, "CRL file not found: " + filename);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
