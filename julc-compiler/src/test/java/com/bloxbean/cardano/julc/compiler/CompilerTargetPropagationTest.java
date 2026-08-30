package com.bloxbean.cardano.julc.compiler;

import com.bloxbean.cardano.julc.compiler.pir.PirTerm;
import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.core.flat.UplcFlatEncoder;
import com.bloxbean.cardano.julc.vm.LedgerEvaluationTarget;
import com.bloxbean.cardano.julc.vm.PlutusLanguage;
import com.bloxbean.cardano.julc.vm.UplcVersion;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompilerTargetPropagationTest {

    private static final String SIMPLE_VALIDATOR = """
            @SpendingValidator
            class TargetValidator {
                @Entrypoint
                static boolean validate(PlutusData redeemer, ScriptContext context) {
                    return true;
                }
            }
            """;

    @Test
    void defaultAndExplicitPv11ProduceIdenticalProgramsAndBytes() {
        var defaultResult = new JulcCompiler().compile(SIMPLE_VALIDATOR);
        var explicitResult = new JulcCompiler(null, new CompilerOptions()
                .setTarget(CompilerTarget.PLUTUS_V3_PV11))
                .compile(SIMPLE_VALIDATOR);

        assertEquals(defaultResult.program(), explicitResult.program());
        assertArrayEquals(
                UplcFlatEncoder.encodeProgram(defaultResult.program()),
                UplcFlatEncoder.encodeProgram(explicitResult.program()));
        assertSame(CompilerTarget.PLUTUS_V3_PV11, defaultResult.target());
        assertSame(CompilerTarget.PLUTUS_V3_PV11, explicitResult.target());
        assertEquals("1.1.0", defaultResult.program().versionString());
    }

    @Test
    void compilationSnapshotsOptionsAndResolvesTargetExactlyOnce() {
        var options = new CompilerOptions().setVerbose(true);
        var changed = new AtomicBoolean();
        options.setLogger(message -> {
            if (message.contains("Compiler target") && changed.compareAndSet(false, true)) {
                options.setTarget(new CompilerTarget(
                        LedgerEvaluationTarget.pv10(PlutusLanguage.PLUTUS_V3),
                        UplcVersion.V1_1_0));
                options.setSourceMapEnabled(true);
            }
        });
        var compiler = new JulcCompiler(null, options);

        var result = compiler.compile(SIMPLE_VALIDATOR);

        assertTrue(changed.get());
        assertSame(CompilerTarget.PLUTUS_V3_PV11, result.target());
        assertNull(result.sourceMap(), "source-map mutation must not affect an active compilation");

        var nextError = assertThrows(CompilerException.class,
                () -> compiler.compile(SIMPLE_VALIDATOR));
        assertEquals("JULC0031", nextError.diagnostics().getFirst().code());
    }

    @Test
    void unsupportedTargetFailsBeforeInvalidSourceIsParsed() {
        var unsupported = new CompilerTarget(
                LedgerEvaluationTarget.pv10(PlutusLanguage.PLUTUS_V3),
                UplcVersion.V1_1_0);
        var compiler = new JulcCompiler(null, new CompilerOptions().setTarget(unsupported));

        var error = assertThrows(CompilerException.class,
                () -> compiler.compile("this is not Java"));

        assertEquals("JULC0031", error.diagnostics().getFirst().code());
        assertFalse(error.getMessage().contains("Parse error"));
    }

    @Test
    void allCompileEntryPathsUseTheResolvedProgramVersionAndProvenance() {
        var compiler = new JulcCompiler();
        var methodSource = """
                class TargetMethod {
                    static long answer() { return 42; }
                }
                """;

        var detailed = compiler.compileWithDetails(SIMPLE_VALIDATOR);
        var method = compiler.compileMethod(methodSource, "answer");
        Program directPir = compiler.compilePirToProgram(
                new PirTerm.Const(Constant.integer(42)));

        assertSame(CompilerTarget.PLUTUS_V3_PV11, detailed.target());
        assertSame(CompilerTarget.PLUTUS_V3_PV11, method.target());
        assertEquals("1.1.0", detailed.program().versionString());
        assertEquals("1.1.0", method.program().versionString());
        assertEquals("1.1.0", directPir.versionString());
    }

    @Test
    void compatibilityConstructorsRetainPinnedPv11Provenance() {
        var program = Program.plutusV3(new com.bloxbean.cardano.julc.core.Term.Error());

        assertSame(CompilerTarget.PLUTUS_V3_PV11,
                new CompileResult(program, java.util.List.of()).target());
        assertThrows(NullPointerException.class,
                () -> new CompileResult(
                        program, java.util.List.of(), java.util.List.of(),
                        null, null, null, null));
    }

    @Test
    void verboseLogReportsStableTargetBeforePipelineStages() {
        var logs = new ArrayList<String>();
        var compiler = new JulcCompiler(null, new CompilerOptions()
                .setVerbose(true)
                .setLogger(logs::add));

        compiler.compile(SIMPLE_VALIDATOR);

        assertTrue(logs.getFirst().contains(
                "Compiler target: plutus-v3-pv11-uplc-1.1.0"));
    }
}
