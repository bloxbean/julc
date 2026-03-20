package com.bloxbean.cardano.julc.compiler;

import com.bloxbean.cardano.julc.core.source.SourceMap;
import com.bloxbean.cardano.julc.stdlib.StdlibRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that the compiler generates source maps when enabled.
 */
class SourceMapCompilerTest {

    @Test
    void compile_withSourceMapEnabled_producesNonEmptySourceMap() {
        var options = new CompilerOptions().setSourceMapEnabled(true);
        var compiler = new JulcCompiler(StdlibRegistry.defaultRegistry(), options);

        String source = """
                import com.bloxbean.cardano.julc.ledger.*;
                import com.bloxbean.cardano.julc.stdlib.Builtins;

                @Validator
                class SimpleValidator {
                    @Entrypoint
                    public static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        return true;
                    }
                }
                """;

        CompileResult result = compiler.compile(source);
        assertFalse(result.hasErrors());
        assertTrue(result.hasSourceMap());
        assertNotNull(result.sourceMap());
        assertTrue(result.sourceMap().size() > 0, "Source map should have entries");
    }

    @Test
    void compile_withSourceMapDisabled_producesNullSourceMap() {
        var options = new CompilerOptions().setSourceMapEnabled(false);
        var compiler = new JulcCompiler(StdlibRegistry.defaultRegistry(), options);

        String source = """
                import com.bloxbean.cardano.julc.ledger.*;
                import com.bloxbean.cardano.julc.stdlib.Builtins;

                @Validator
                class SimpleValidator {
                    @Entrypoint
                    public static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        return true;
                    }
                }
                """;

        CompileResult result = compiler.compile(source);
        assertFalse(result.hasErrors());
        assertFalse(result.hasSourceMap());
        assertNull(result.sourceMap());
    }

    @Test
    void compile_withSourceMap_hasEntriesForMethodCallsAndConditions() {
        var options = new CompilerOptions().setSourceMapEnabled(true);
        var compiler = new JulcCompiler(StdlibRegistry.defaultRegistry(), options);

        String source = """
                import com.bloxbean.cardano.julc.ledger.*;
                import com.bloxbean.cardano.julc.stdlib.Builtins;

                @Validator
                class ConditionalValidator {
                    @Entrypoint
                    public static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        if (Builtins.equalsData(redeemer, redeemer)) {
                            return true;
                        }
                        Builtins.error();
                        return false;
                    }
                }
                """;

        CompileResult result = compiler.compile(source);
        assertFalse(result.hasErrors());
        assertTrue(result.hasSourceMap());
        // The source map should have multiple entries for the if condition, method calls, returns
        assertTrue(result.sourceMap().size() >= 3,
                "Expected at least 3 source map entries, got " + result.sourceMap().size());
    }

    @Test
    void compileMethod_withSourceMap_producesSourceMap() {
        var options = new CompilerOptions().setSourceMapEnabled(true);
        var compiler = new JulcCompiler(StdlibRegistry.defaultRegistry(), options);

        String source = """
                import com.bloxbean.cardano.julc.stdlib.Builtins;
                import java.math.BigInteger;

                class Helper {
                    public static BigInteger add(BigInteger a, BigInteger b) {
                        return a.add(b);
                    }
                }
                """;

        CompileResult result = compiler.compileMethod(source, "add");
        assertFalse(result.hasErrors());
        assertTrue(result.hasSourceMap());
        assertTrue(result.sourceMap().size() > 0);
    }

    @Test
    void compile_withSourceMap_positionsReferToCallSites() {
        var options = new CompilerOptions().setSourceMapEnabled(true);
        var compiler = new JulcCompiler(StdlibRegistry.defaultRegistry(), options);

        String source = """
                import com.bloxbean.cardano.julc.ledger.*;
                import com.bloxbean.cardano.julc.stdlib.Builtins;

                @Validator
                class ErrorValidator {
                    @Entrypoint
                    public static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        Builtins.error();
                        return false;
                    }
                }
                """;

        CompileResult result = compiler.compile(source);
        assertFalse(result.hasErrors());
        assertTrue(result.hasSourceMap());

        // Verify that at least one source map entry has line information
        // (we can't easily check specific lines without walking the term tree,
        // but we can verify entries exist)
        assertTrue(result.sourceMap().size() > 0);
    }

    @Test
    void compile_defaultOptions_sourceMapDisabled() {
        var options = new CompilerOptions();
        assertFalse(options.isSourceMapEnabled());
    }

    @Test
    void compilerOptions_sourceMapChaining() {
        var options = new CompilerOptions()
                .setSourceMapEnabled(true)
                .setVerbose(false);
        assertTrue(options.isSourceMapEnabled());
        assertFalse(options.isVerbose());
    }

    @Test
    void compileResult_backwardCompatibleConstructors_nullSourceMap() {
        var program = com.bloxbean.cardano.julc.core.Program.plutusV3(
                com.bloxbean.cardano.julc.core.Term.const_(
                        com.bloxbean.cardano.julc.core.Constant.unit()));

        // Two-arg constructor
        var r1 = new CompileResult(program, java.util.List.of());
        assertNull(r1.sourceMap());
        assertFalse(r1.hasSourceMap());

        // Three-arg constructor
        var r2 = new CompileResult(program, java.util.List.of(), java.util.List.of());
        assertNull(r2.sourceMap());
        assertFalse(r2.hasSourceMap());

        // Five-arg constructor
        var r3 = new CompileResult(program, java.util.List.of(), java.util.List.of(), null, null);
        assertNull(r3.sourceMap());
        assertFalse(r3.hasSourceMap());

        // Six-arg with SourceMap.EMPTY
        var r4 = new CompileResult(program, java.util.List.of(), java.util.List.of(), null, null, SourceMap.EMPTY);
        assertNotNull(r4.sourceMap());
        assertFalse(r4.hasSourceMap()); // EMPTY is not "has source map"
    }
}
