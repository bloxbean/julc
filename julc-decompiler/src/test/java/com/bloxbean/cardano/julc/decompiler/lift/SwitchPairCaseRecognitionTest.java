package com.bloxbean.cardano.julc.decompiler.lift;

import com.bloxbean.cardano.julc.compiler.CompilerOptions;
import com.bloxbean.cardano.julc.compiler.JulcCompiler;
import com.bloxbean.cardano.julc.compiler.OptimizationLevel;
import com.bloxbean.cardano.julc.core.flat.UplcFlatDecoder;
import com.bloxbean.cardano.julc.core.flat.UplcFlatEncoder;
import com.bloxbean.cardano.julc.decompiler.DecompileOptions;
import com.bloxbean.cardano.julc.decompiler.JulcDecompiler;
import com.bloxbean.cardano.julc.stdlib.StdlibRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SwitchPairCaseRecognitionTest {
    @Test
    void freshlyCompiledSwitchSurvivesSerializationAndGenericCaseDecompilation() {
        String source = """
                class SwitchPair {
                    sealed interface Action permits Pay, Cancel {}
                    record Pay(long amount) implements Action {}
                    record Cancel() implements Action {}
                    static long run(Action action) {
                        return switch (action) { case Pay p -> p.amount(); case Cancel c -> 0; };
                    }
                }
                """;
        for (var level : List.of(OptimizationLevel.NONE, OptimizationLevel.BASELINE, OptimizationLevel.PV11_SAFE)) {
            var compiled = new JulcCompiler(StdlibRegistry.defaultRegistry(),
                    new CompilerOptions().setOptimizationLevel(level)).compileMethod(source, "run");
            assertFalse(compiled.hasErrors(), compiled.diagnostics().toString());
            assertEquals(level.pv11SafeRulesEnabled(), compiled.optimizationReport().appliedRules().contains("pv11.o4.case-pair"));
            var decoded = UplcFlatDecoder.decodeProgram(UplcFlatEncoder.encodeProgram(compiled.program()));
            var decompiled = JulcDecompiler.decompile(decoded, DecompileOptions.defaults());
            assertNotNull(decompiled.hir());
            assertFalse(decompiled.javaSource().isBlank());
        }
    }
}
