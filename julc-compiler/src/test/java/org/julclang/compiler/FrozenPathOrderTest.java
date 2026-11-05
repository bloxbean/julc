package org.julclang.compiler;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Enumeration;
import java.net.URL;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the shared back half must not change on the default frontend: the order of a failure, and the log. */
class FrozenPathOrderTest {

    private static final String METHOD = "class P { static long m() { return 1; } }";

    private static final class Scanned extends RuntimeException {
    }

    /** A loader that says when it is asked for the libraries, by throwing. */
    private static final class Watched extends ClassLoader {
        Watched() {
            super(FrozenPathOrderTest.class.getClassLoader());
        }

        @Override
        public Enumeration<URL> getResources(String name) {
            throw new Scanned();
        }
    }

    /** Libraries are found first, so a failure there is reported ahead of a missing source map or a bad rule. */
    @Test
    void debugCompilationFindsItsLibrariesFirst() {
        assertThrows(Scanned.class, () -> new JulcCompiler(null, new CompilerOptions()).compileForDebug(METHOD, new Watched()));
        var badRule = new CompilerOptions().setSourceMapEnabled(true).disableOptimizationRule("no-such-rule");
        assertThrows(Scanned.class, () -> new JulcCompiler(null, badRule).compileForDebug(METHOD, new Watched()));
    }

    @Test
    void aMissingSourceMapIsReportedBeforeAnythingIsCompiled() {
        assertThrows(IllegalStateException.class, () -> new JulcCompiler(null, new CompilerOptions()).compileForDebug(METHOD));
    }

    /** The two lines are a validator's: {@code compileMethod} has never written them. */
    @Test
    void compileMethodWritesNoBackendStageLines() {
        var lines = new ArrayList<String>();
        new JulcCompiler(null, new CompilerOptions().setVerbose(true).setLogger(lines::add)).compileMethod(METHOD, "m");
        assertFalse(lines.isEmpty(), "the log is on");
        assertTrue(lines.stream().noneMatch(line -> line.contains("UPLC optimization complete")), lines::toString);
        assertTrue(lines.stream().noneMatch(line -> line.contains("Source map generated")), lines::toString);
        var withMap = new ArrayList<String>();
        new JulcCompiler(null, new CompilerOptions().setVerbose(true).setSourceMapEnabled(true).setLogger(withMap::add))
                .compileMethod(METHOD, "m");
        assertTrue(withMap.stream().noneMatch(line -> line.contains("Source map generated")), withMap::toString);
    }

    @Test
    void aValidatorStillWritesThem() {
        var lines = new ArrayList<String>();
        new JulcCompiler(null, new CompilerOptions().setVerbose(true).setLogger(lines::add)).compile("""
                import org.julclang.core.PlutusData;
                import org.julclang.ledger.ScriptContext;
                import org.julclang.stdlib.annotation.*;

                @SpendingValidator
                class V {
                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) { return true; }
                }
                """);
        assertEquals(1, lines.stream().filter(line -> line.contains("UPLC optimization complete")).count(), lines::toString);
    }
}
