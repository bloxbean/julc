package org.julclang.compiler;

import org.julclang.compiler.frontend.javac.JavacEnvironment;
import org.julclang.compiler.frontend.javac.JavacGateMode;
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
    private static final String DIALECT = """
            import java.math.BigInteger;
            class C { static BigInteger f(BigInteger a) { return a + BigInteger.ONE; } }
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

    private static JulcCompiler compiler(JavacGateMode gate) {
        return new JulcCompiler(StdlibRegistry.defaultRegistry(), new CompilerOptions().setJavacGate(gate));
    }

    private static void assertRecorded(JavacProvenance provenance, JavacGateMode gate, Outcome outcome) {
        var environment = JavacEnvironment.current();
        assertEquals(new JavacProvenance(environment.javacVersion(), environment.platformDigest(),
                environment.stubsDigest(), gate, outcome), provenance);
    }

    @Test
    void anEnforcedCompilationRecordsItsEnvironment() {
        assertRecorded(compiler(JavacGateMode.ENFORCE).compileMethod(JAVA, "f").javacProvenance(),
                JavacGateMode.ENFORCE, Outcome.ACCEPTED);
    }

    @Test
    void everyEntryPointRecordsIt(@TempDir Path dir) throws IOException {
        var compiler = compiler(JavacGateMode.ENFORCE);
        assertRecorded(compiler.compile(VALIDATOR).javacProvenance(), JavacGateMode.ENFORCE, Outcome.ACCEPTED);
        assertRecorded(compiler.compileContract(VALIDATOR).compileResult().javacProvenance(),
                JavacGateMode.ENFORCE, Outcome.ACCEPTED);
        Path file = Files.writeString(dir.resolve("Always.java"), VALIDATOR);
        assertRecorded(compiler.compile(file).javacProvenance(), JavacGateMode.ENFORCE, Outcome.ACCEPTED);
    }

    @Test
    void reportModeRecordsARejectionItDidNotEnforce() {
        assertRecorded(compiler(JavacGateMode.REPORT).compileMethod(DIALECT, "f").javacProvenance(),
                JavacGateMode.REPORT, Outcome.REJECTED);
    }

    @Test
    void aGateThatDidNotRunSaysSo() {
        assertEquals(JavacProvenance.notRun(JavacGateMode.OFF),
                compiler(JavacGateMode.OFF).compileMethod(JAVA, "f").javacProvenance());
    }

    @Test
    void aResultBuiltOutsideACompilationDoesNotTakeThisProcesssEnvironment() {
        var compiled = compiler(JavacGateMode.ENFORCE).compileMethod(JAVA, "f");
        var rebuilt = new CompileResult(compiled.program(), compiled.diagnostics(), compiled.params(), compiled.pirTerm(),
                compiled.uplcTerm(), compiled.sourceMap(), compiled.target(), compiled.optimizationReport());
        assertSame(JavacProvenance.UNAVAILABLE, rebuilt.javacProvenance());
        assertNull(rebuilt.javacProvenance().javacVersion());
    }

    @Test
    void provenanceDoesNotChangeTheCompiledBytes() {
        byte[] enforced = UplcFlatEncoder.encodeProgram(compiler(JavacGateMode.ENFORCE).compileMethod(JAVA, "f").program());
        for (var gate : List.of(JavacGateMode.REPORT, JavacGateMode.OFF)) {
            assertArrayEquals(enforced, UplcFlatEncoder.encodeProgram(compiler(gate).compileMethod(JAVA, "f").program()),
                    gate.toString());
        }
    }
}
