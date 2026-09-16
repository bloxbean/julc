package org.julclang.compiler;

import org.julclang.core.Constant;
import org.julclang.core.Term;
import org.julclang.core.flat.UplcFlatEncoder;
import org.julclang.compiler.uplc.UplcOptimizer;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.vm.LedgerEvaluationTarget;
import org.julclang.vm.OptimizationCostProfile;
import org.julclang.vm.OptimizationCostProfiles;
import org.julclang.vm.PlutusLanguage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OptimizationConfigurationTest {

    private static final String SOURCE = """
            import org.julclang.stdlib.Builtins;
            class OptimizationSample {
                static boolean validate(long value) {
                    return value + 0 == value;
                }
            }
            """;

    @Test
    void defaultAndExplicitPv11SafeRemainByteIdentical() {
        var defaultResult = new JulcCompiler(StdlibRegistry.defaultRegistry())
                .compileMethod(SOURCE, "validate");
        var explicitResult = compile(OptimizationLevel.PV11_SAFE, null);

        assertArrayEquals(UplcFlatEncoder.encodeProgram(defaultResult.program()),
                UplcFlatEncoder.encodeProgram(explicitResult.program()));
        assertEquals(OptimizationLevel.PV11_SAFE,
                defaultResult.optimizationReport().level());
        assertEquals(defaultResult.optimizationReport(),
                explicitResult.optimizationReport());
    }

    @Test
    void baselineRemainsAnExplicitCompatibilityLevel() {
        var result = compile(OptimizationLevel.BASELINE, null);

        assertEquals(OptimizationLevel.BASELINE, result.optimizationReport().level());
        assertEquals(List.of(), result.optimizationReport().appliedRules());
    }

    @Test
    void pv11SafeNeedsNoCostProfileAndPreservesUnaffectedFixtureBytes() {
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
        assertEquals(OptimizationLevel.DEFAULT,
                OptimizationLevel.forProfileId(OptimizationLevel.DEFAULT_PROFILE_ID));
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

    /**
     * ADR-044: each PIR-to-PIR rule can be switched off on its own so that it can be reviewed
     * and measured in isolation. The identifier must be one of the switchable rule ids exactly;
     * anything else fails closed before compilation instead of leaving the rule enabled.
     */
    @Test
    void individualPirRulesCanBeDisabledAndUnknownRuleIdsFailClosed() {
        assertEquals(List.of("pv11.o8.value-sharing", "pv11.o15.projection-sharing", "pv11.o9.list-to-array",
                        "pv11.o14.value-literal-fold"),
                CompilationContext.switchableOptimizationRules());

        var unknown = assertThrows(CompilerException.class, () -> new JulcCompiler(StdlibRegistry.defaultRegistry(),
                new CompilerOptions().disableOptimizationRule("pv11.o2.case-bool")).compileMethod(SOURCE, "validate"));
        assertEquals("JULC0043", unknown.diagnostics().getFirst().code());
        assertThrows(IllegalArgumentException.class, () -> new CompilerOptions().disableOptimizationRule(" "));

        var sharing = O8ValueSharingFixtures.FIXTURES.getFirst();
        assertEquals("REPEATED", sharing.name());
        var shared = new JulcCompiler(StdlibRegistry.defaultRegistry(), new CompilerOptions()
                .setOptimizationLevel(OptimizationLevel.PV11_SAFE)).compileMethod(sharing.source(), sharing.method());
        var unshared = new JulcCompiler(StdlibRegistry.defaultRegistry(), new CompilerOptions()
                .setOptimizationLevel(OptimizationLevel.PV11_SAFE).disableOptimizationRule("pv11.o8.value-sharing"))
                .compileMethod(sharing.source(), sharing.method());
        assertEquals(List.of("pv11.o8.value-sharing"), shared.optimizationReport().appliedRules().stream()
                .filter(rule -> rule.startsWith("pv11.o8")).toList());
        assertFalse(unshared.optimizationReport().appliedRules().contains("pv11.o8.value-sharing"));
        // With O8 off, the safe profile reproduces the pre-O8 bytes captured at ADR-042's base commit.
        var preO8 = golden("/optimization/o8-pre-change-bytes.txt", "0-PV11_SAFE-false");
        assertArrayEquals(preO8, UplcFlatEncoder.encodeProgram(unshared.program()));
        assertFalse(Arrays.equals(preO8, UplcFlatEncoder.encodeProgram(shared.program())));
        // Disabling a rule the level would not run anyway changes nothing.
        assertArrayEquals(UplcFlatEncoder.encodeProgram(compile(OptimizationLevel.BASELINE, null).program()),
                UplcFlatEncoder.encodeProgram(new JulcCompiler(StdlibRegistry.defaultRegistry(), new CompilerOptions()
                        .setOptimizationLevel(OptimizationLevel.BASELINE).disableOptimizationRule("pv11.o9.list-to-array"))
                        .compileMethod(SOURCE, "validate").program()));
    }

    private static byte[] golden(String resource, String id) {
        try (var input = OptimizationConfigurationTest.class.getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            String hex = new String(input.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(line -> line.startsWith(id + " ")).findFirst().orElseThrow().substring(id.length() + 1);
            return HexFormat.of().parseHex(hex);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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
