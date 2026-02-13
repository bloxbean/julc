package com.bloxbean.cardano.julc.compiler;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for verbose compilation mode.
 */
class VerboseCompilationTest {

    private static final String SIMPLE_VALIDATOR = """
            @Validator
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

    @Test
    void testVerboseLogsAllStages() {
        var logs = new ArrayList<String>();
        var opts = new CompilerOptions().setVerbose(true).setLogger(logs::add);
        var compiler = new JulcCompiler(null, opts);
        compiler.compile(SIMPLE_VALIDATOR);

        assertFalse(logs.isEmpty(), "Verbose mode should produce log output");

        var joined = String.join("\n", logs);
        assertTrue(joined.contains("Parsing"), "Should log parsing stage");
        assertTrue(joined.contains("Subset validation passed"), "Should log subset validation");
        assertTrue(joined.contains("Validator"), "Should log validator discovery");
        assertTrue(joined.contains("PIR generation complete"), "Should log PIR generation");
        assertTrue(joined.contains("UPLC optimization complete"), "Should log UPLC optimization");
        assertTrue(joined.contains("Compilation complete"), "Should log completion with size");
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

    @Test
    void testVerboseWithLibraries() {
        var logs = new ArrayList<String>();
        var opts = new CompilerOptions().setVerbose(true).setLogger(logs::add);
        var compiler = new JulcCompiler(null, opts);

        var libSource = """
                @OnchainLibrary
                class MathHelper {
                    static boolean isPositive(long x) {
                        return x > 0;
                    }
                }
                """;
        var validatorSource = """
                @Validator
                class LibValidator {
                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        return MathHelper.isPositive(42);
                    }
                }
                """;
        compiler.compile(validatorSource, List.of(libSource));

        var joined = String.join("\n", logs);
        assertTrue(joined.contains("Compiling 1 library source"), "Should log library compilation");
        assertTrue(joined.contains("Compiled 1 library method"), "Should log compiled library methods");
    }

    @Test
    void testVerboseWithParams() {
        var logs = new ArrayList<String>();
        var opts = new CompilerOptions().setVerbose(true).setLogger(logs::add);
        var compiler = new JulcCompiler(null, opts);

        var source = """
                import java.math.BigInteger;

                @Validator
                class ParamValidator {
                    @Param BigInteger threshold;

                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        return threshold > 0;
                    }
                }
                """;
        compiler.compile(source);

        var joined = String.join("\n", logs);
        assertTrue(joined.contains("@Param"), "Should log @Param detection");
        assertTrue(joined.contains("threshold"), "Should log param field name");
    }
}
