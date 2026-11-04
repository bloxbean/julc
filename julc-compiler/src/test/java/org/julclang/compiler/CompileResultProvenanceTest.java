package org.julclang.compiler;

import org.julclang.compiler.frontend.javac.JavacEnvironment;
import org.julclang.compiler.frontend.javac.JavacProvenance;
import org.julclang.compiler.frontend.javac.JavacProvenance.Outcome;
import org.julclang.core.flat.UplcFlatEncoder;
import org.julclang.stdlib.StdlibRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Each compilation's result records what javac saw of it (ADR-068 D3, review F401). */
class CompileResultProvenanceTest {

    private static final String JAVA = """
            import java.math.BigInteger;
            class C { static BigInteger f(BigInteger a) { return a.add(BigInteger.ONE); } }
            """;
private static final String VALIDATOR = """
            import org.julclang.ledger.*;
            import org.julclang.stdlib.annotation.*;
            import org.julclang.core.PlutusData;

            @SpendingValidator
            class Always {
                @Entrypoint
                static boolean validate(PlutusData redeemer, ScriptContext ctx) { return true; }
            }
            """;

    private static JulcCompiler compiler() {
        return new JulcCompiler(StdlibRegistry.defaultRegistry(), new CompilerOptions());
    }

    private static void assertRecorded(JavacProvenance provenance) {
        var environment = JavacEnvironment.current();
        assertEquals(new JavacProvenance(environment.javacVersion(), environment.platformDigest(),
                environment.stubsDigest(), null, Outcome.ACCEPTED), provenance);
    }

    @Test
    void anEnforcedCompilationRecordsItsEnvironment() {
        assertRecorded(compiler().compileMethod(JAVA, "f").javacProvenance());
    }

    @Test
    void everyEntryPointRecordsIt(@TempDir Path dir) throws IOException {
        var compiler = compiler();
        assertRecorded(compiler.compile(VALIDATOR).javacProvenance());
        assertRecorded(compiler.compileContract(VALIDATOR).compileResult().javacProvenance());
        Path file = Files.writeString(dir.resolve("Always.java"), VALIDATOR);
        assertRecorded(compiler.compile(file).javacProvenance());
    }

    @Test
    void aResultBuiltOutsideACompilationDoesNotTakeThisProcesssEnvironment() {
        var compiled = compiler().compileMethod(JAVA, "f");
        var rebuilt = new CompileResult(compiled.program(), compiled.diagnostics(), compiled.params(), compiled.pirTerm(),
                compiled.uplcTerm(), compiled.sourceMap(), compiled.target(), compiled.optimizationReport());
        assertSame(JavacProvenance.UNAVAILABLE, rebuilt.javacProvenance());
        assertNull(rebuilt.javacProvenance().javacVersion());
    }

}
