package com.bloxbean.cardano.julc.compiler;

import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.Term;
import com.bloxbean.cardano.julc.core.flat.UplcFlatEncoder;
import com.bloxbean.cardano.julc.compiler.uplc.UplcOptimizer;
import com.bloxbean.cardano.julc.stdlib.StdlibRegistry;
import com.bloxbean.cardano.julc.vm.LedgerEvaluationTarget;
import com.bloxbean.cardano.julc.vm.OptimizationCostProfile;
import com.bloxbean.cardano.julc.vm.OptimizationCostProfiles;
import com.bloxbean.cardano.julc.vm.PlutusLanguage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OptimizationConfigurationTest {

    private static final String SOURCE = """
            import com.bloxbean.cardano.julc.stdlib.Builtins;
            class OptimizationSample {
                static boolean validate(long value) {
                    return value + 0 == value;
                }
            }
            """;

    @Test
    void defaultAndExplicitBaselineRemainByteIdentical() {
        var defaultResult = new JulcCompiler(StdlibRegistry.defaultRegistry())
                .compileMethod(SOURCE, "validate");
        var explicitResult = compile(OptimizationLevel.BASELINE, null);

        assertArrayEquals(UplcFlatEncoder.encodeProgram(defaultResult.program()),
                UplcFlatEncoder.encodeProgram(explicitResult.program()));
        assertEquals(OptimizationLevel.BASELINE,
                defaultResult.optimizationReport().level());
        assertEquals(defaultResult.optimizationReport(),
                explicitResult.optimizationReport());
    }

    @Test
    void pv11SafeIsIndependentFromCostProfileAndInitiallyPreservesBytes() {
        var baseline = compile(OptimizationLevel.BASELINE, null);
        var safe = compile(OptimizationLevel.PV11_SAFE, null);

        assertArrayEquals(UplcFlatEncoder.encodeProgram(baseline.program()),
                UplcFlatEncoder.encodeProgram(safe.program()));
        assertEquals(OptimizationLevel.PV11_SAFE, safe.optimizationReport().level());
        assertEquals(baseline.optimizationReport().appliedRules(),
                safe.optimizationReport().appliedRules());
    }

    @Test
    void costedLevelRequiresExactPinnedProfile() {
        var missing = assertThrows(CompilerException.class,
                () -> compile(OptimizationLevel.PV11_COSTED, null));
        assertEquals("JULC0037", missing.diagnostics().getFirst().code());

        var profile = OptimizationCostProfiles.CARDANO_NODE_11_0_1_PLUTUS_V3_PV11;
        var result = compile(OptimizationLevel.PV11_COSTED, profile);
        assertEquals(profile.profileId(), result.optimizationReport().costProfileId());
        assertEquals(profile.parameterHash(),
                result.optimizationReport().costParameterHash());
    }

    @Test
    void mismatchedProfileFailsBeforeCompilation() {
        var pinned = OptimizationCostProfiles.CARDANO_NODE_11_0_1_PLUTUS_V3_PV11;
        var mismatched = new OptimizationCostProfile(
                "synthetic-v2-pv11",
                LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V2),
                "test-only",
                pinned.parameterHash(),
                pinned.costModelParameters());

        var error = assertThrows(CompilerException.class,
                () -> compile(OptimizationLevel.PV11_COSTED, mismatched));
        assertEquals("JULC0038", error.diagnostics().getFirst().code());
    }

    @Test
    void noneDisablesExistingUplcPassesButNotTargetValidation() {
        var context = CompilationContext.resolve(new CompilerOptions()
                .setOptimizationLevel(OptimizationLevel.NONE));
        var input = Term.force(Term.delay(Term.const_(Constant.integer(1))));
        var result = new UplcOptimizer(context).optimizeWithReport(input);

        assertEquals(input, result.term());
        assertEquals(List.of(), result.appliedPasses());
        assertFalse(context.optimizationLevel().baselineOptimizerEnabled());
    }

    @Test
    void publicIdentifiersAreExactAndFailClosed() {
        assertEquals(OptimizationLevel.PV11_SAFE,
                OptimizationLevel.forProfileId("pv11-safe"));

        var levelError = assertThrows(CompilerException.class,
                () -> OptimizationLevel.forProfileId("PV11_SAFE"));
        assertEquals("JULC0039", levelError.diagnostics().getFirst().code());

        var profileError = assertThrows(CompilerException.class,
                () -> OptimizationConfiguration.apply(
                        new CompilerOptions(), "pv11-costed", "latest"));
        assertEquals("JULC0040", profileError.diagnostics().getFirst().code());
    }

    private static CompileResult compile(
            OptimizationLevel level,
            OptimizationCostProfile profile) {
        var options = new CompilerOptions().setOptimizationLevel(level);
        if (profile != null) {
            options.setOptimizationCostProfile(profile);
        }
        return new JulcCompiler(StdlibRegistry.defaultRegistry(), options)
                .compileMethod(SOURCE, "validate");
    }
}
