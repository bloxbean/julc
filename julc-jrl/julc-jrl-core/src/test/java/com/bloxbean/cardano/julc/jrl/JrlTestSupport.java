package com.bloxbean.cardano.julc.jrl;

import com.bloxbean.cardano.julc.core.Program;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Shared test helpers for JRL test classes.
 */
final class JrlTestSupport {

    private JrlTestSupport() {}

    /**
     * Compile JRL source to a UPLC Program, asserting success.
     */
    static Program compileJrl(String jrlSource) {
        var compiler = new JrlCompiler();
        var result = compiler.compile(jrlSource, "test.jrl");
        assertFalse(result.hasErrors(),
                () -> "Compilation failed.\nJRL diagnostics: " + result.jrlDiagnostics()
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
     * Load a JRL example file from test resources.
     */
    static String loadJrl(String filename) {
        try (var is = JrlTestSupport.class.getResourceAsStream("/examples/" + filename)) {
            assertNotNull(is, "JRL file not found: " + filename);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
