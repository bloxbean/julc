package org.julclang.compiler;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for verbose compilation mode.
 */
class VerboseCompilationTest {

    private static final String SIMPLE_VALIDATOR = """
            import org.julclang.core.PlutusData;
            import org.julclang.ledger.ScriptContext;
            import org.julclang.stdlib.annotation.Entrypoint;
            import org.julclang.stdlib.annotation.SpendingValidator;
            @SpendingValidator
            class SimpleValidator {
                @Entrypoint
                static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                    return true;
                }
            }
            """;

    @Test
    void testVerboseOffProducesNoOutput() {
        var logs = new ArrayList<String>();
        var opts = new CompilerOptions().setVerbose(false).setLogger(logs::add);
        var compiler = new JulcCompiler(null, opts);
        compiler.compile(SIMPLE_VALIDATOR);
        assertTrue(logs.isEmpty(), "Non-verbose mode should produce no log output");
    }

    /**
     * ADR-068 M5: the stages above are the JavaParser frontend's log, selected explicitly there. The javac frontend, the
     * default, logs the target, the version, the optimization level and the result's size.
     */
    @Test
    void testVerboseOnTheDefaultFrontend() {
        var logs = new ArrayList<String>();
        var compiler = new JulcCompiler(null, new CompilerOptions().setVerbose(true).setLogger(logs::add));
        compiler.compile(SIMPLE_VALIDATOR);
        String joined = String.join("\n", logs);
        assertTrue(joined.contains("Compiler target"), joined);
        assertTrue(joined.contains("Optimization level"), joined);
        assertTrue(joined.contains("Compilation complete (javac frontend)"), joined);
    }

    @Test
    void testCustomLogger() {
        var logs = new ArrayList<String>();
        var opts = new CompilerOptions().setVerbose(true).setLogger(logs::add);
        var compiler = new JulcCompiler(null, opts);
        compiler.compile(SIMPLE_VALIDATOR);

        assertFalse(logs.isEmpty());
        // All log messages should have the [julc] prefix
        assertTrue(logs.stream().allMatch(l -> l.startsWith("[julc]")),
                "All log messages should have [julc] prefix");
    }

}
