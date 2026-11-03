package org.julclang.tools.debug;

import org.julclang.compiler.CompilerFrontend;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.JulcCompiler;
import org.julclang.core.debug.DebugMetadata;
import org.julclang.stdlib.StdlibRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-068: debug metadata from {@code frontend=JAVAC} is what the source debugger reads. Its source is named by its file
 * name (I4), and the debugger finds a point's source by its id and UTF-16 offsets, never by that name: the runtime index
 * builds over it, and every exact point lands in the one source.
 */
class JavacDebugMetadataTest {

    private static final String SOURCE = """
            import java.math.BigInteger;
            import org.julclang.core.PlutusData;
            import org.julclang.ledger.ScriptContext;
            import org.julclang.stdlib.annotation.Entrypoint;
            import org.julclang.stdlib.annotation.SpendingValidator;

            @SpendingValidator
            class JavacDebugged {
                @Entrypoint
                static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                    BigInteger exact = BigInteger.valueOf(42);
                    return exact.signum() > 0;
                }
            }
            """;

    @Test
    void theDebuggersIndexReadsJavacMetadata() {
        var compiled = new JulcCompiler(StdlibRegistry.defaultRegistry(), new CompilerOptions()
                .setFrontend(CompilerFrontend.JAVAC).setSourceMapEnabled(true)).compileForDebug(SOURCE, List.of());
        var metadata = compiled.debugMetadata();
        assertEquals("JavacDebugged.java", metadata.sources().getFirst().uri());
        new JavaLocalsService(metadata, compiled.compileResult().program());
        var exact = metadata.suspensionPoints().stream().filter(point -> point.context() == DebugMetadata.SourceContext.EXACT).toList();
        assertTrue(!exact.isEmpty(), "a point maps to the source");
        assertTrue(exact.stream().allMatch(point -> point.sourceId().equals("validator")));
        assertTrue(metadata.suspensionPoints().stream().flatMap(point -> point.visibleBindings().stream())
                .anyMatch(visible -> visible.availability() == DebugMetadata.Availability.AVAILABLE));
    }
}
