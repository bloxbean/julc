package com.bloxbean.cardano.julc.vm.scalus;

import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.DefaultFun;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.core.Term;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.julc.vm.ExBudget;
import com.bloxbean.cardano.julc.vm.LedgerEvaluationTarget;
import com.bloxbean.cardano.julc.vm.PlutusLanguage;
import com.bloxbean.cardano.julc.vm.ProtocolFeatureRegistry;
import com.bloxbean.cardano.julc.vm.ProtocolVersion;
import com.bloxbean.cardano.julc.vm.UnsupportedLedgerTargetException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import scalus.cardano.ledger.Language;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScalusConfigurationTest {

    @ParameterizedTest(name = "V3/PV{0} publishes one ready target-bound record")
    @MethodSource("v3Targets")
    void v3ConfigurationIsMappedAndPublishedAtomically(
            int protocolMajor, int parameterCount, String expectedVariant) {
        var provider = new ScalusVmProvider();
        var target = target(PlutusLanguage.PLUTUS_V3, protocolMajor);

        provider.setCostModelParams(ones(parameterCount), target);

        var ready = assertInstanceOf(
                ReadyScalusConfiguration.class, provider.plutusV3Configuration);
        assertEquals(target, ready.target());
        assertEquals(target, ready.profile().target());
        assertEquals(Language.PlutusV3, ready.scalusLanguage());
        assertEquals(protocolMajor, ready.scalusProtocol().version());
        assertEquals(expectedVariant, ready.profile().semanticsVariant().name());
        assertNotNull(ready.machineParams());
        assertSame(ready, provider.configurationForEvaluation(target));
    }

    @Test
    void registryRejectedTargetThrowsAndLeavesReadyStateUntouched() {
        var provider = new ScalusVmProvider();
        var pv11 = LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V3);
        provider.setCostModelParams(ones(350), pv11);
        var before = provider.plutusV3Configuration;
        var pv12 = new LedgerEvaluationTarget(
                PlutusLanguage.PLUTUS_V3, new ProtocolVersion(12, 0));

        assertThrows(UnsupportedLedgerTargetException.class,
                () -> provider.setCostModelParams(ones(350), pv12));

        assertSame(before, provider.plutusV3Configuration);
    }

    @Test
    void normalizationUsesResolvedTargetSchemaAndHaskellPaddingRules() {
        var pv10 = LedgerEvaluationTarget.pv10(PlutusLanguage.PLUTUS_V3);
        var pv10Profile = ProtocolFeatureRegistry.resolve(pv10);
        var pv11 = LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V3);
        var pv11Profile = ProtocolFeatureRegistry.resolve(pv11);

        var shortPv10 = ScalusVmProvider.normalizeCostModelParams(
                new long[]{7, 8}, pv10, pv10Profile);
        assertEquals(297, shortPv10.length);
        assertEquals(7, shortPv10[0]);
        assertEquals(8, shortPv10[1]);
        assertEquals(Long.MAX_VALUE, shortPv10[2]);
        assertEquals(Long.MAX_VALUE, shortPv10[296]);

        var oversizedPv10 = new long[298];
        Arrays.fill(oversizedPv10, 9L);
        oversizedPv10[297] = 123_456L;
        var truncatedPv10 = ScalusVmProvider.normalizeCostModelParams(
                oversizedPv10, pv10, pv10Profile);
        assertEquals(297, truncatedPv10.length);
        assertEquals(9, truncatedPv10[296]);

        var shortPv11 = ScalusVmProvider.normalizeCostModelParams(
                new long[]{11}, pv11, pv11Profile);
        assertEquals(350, shortPv11.length);
        assertEquals(11, shortPv11[0]);
        assertEquals(Long.MAX_VALUE, shortPv11[349]);
    }

    @Test
    void normalizationRejectsProfileFromAnotherTarget() {
        var pv10 = LedgerEvaluationTarget.pv10(PlutusLanguage.PLUTUS_V3);
        var pv11Profile = ProtocolFeatureRegistry.resolve(
                LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V3));

        var error = assertThrows(IllegalArgumentException.class,
                () -> ScalusVmProvider.normalizeCostModelParams(
                        ones(297), pv10, pv11Profile));

        assertTrue(error.getMessage().contains("Profile target does not match"));
    }

    @Test
    void pv11SentinelIsRejectedWithoutReplacingReadyState() {
        var provider = new ScalusVmProvider();
        var target = LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V3);
        provider.setCostModelParams(ones(350), target);
        var before = provider.plutusV3Configuration;
        var sentinel = ones(350);
        sentinel[ScalusVmProvider.V3_PV11_DROP_LIST_CPU_INTERCEPT_INDEX] =
                ScalusVmProvider.SCALUS_MISSING_PARAMETER_SENTINEL;

        var error = assertThrows(IllegalArgumentException.class,
                () -> provider.setCostModelParams(sentinel, target));

        assertTrue(error.getMessage().contains("dropList-cpu-arguments-intercept"));
        assertTrue(error.getMessage().contains("300000000"));
        assertSame(before, provider.plutusV3Configuration);
    }

    @Test
    void shortPv11ArrayUsesLongMaxPaddingInsteadOfScalusSentinel() {
        var provider = new ScalusVmProvider();
        var target = LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V3);
        var shortValues = ones(ScalusVmProvider.V3_PV11_DROP_LIST_CPU_INTERCEPT_INDEX);

        provider.setCostModelParams(shortValues, target);

        assertInstanceOf(ReadyScalusConfiguration.class, provider.plutusV3Configuration);
    }

    @ParameterizedTest(name = "V3/PV{0} supplied AddInteger cost is active")
    @MethodSource("v3Targets")
    void parameterPerturbationChangesOnlyCorrespondingOperation(
            int protocolMajor, int parameterCount, String ignoredVariant) {
        var target = target(PlutusLanguage.PLUTUS_V3, protocolMajor);
        var baseline = new ScalusVmProvider();
        baseline.setCostModelParams(ones(parameterCount), target);
        var baselineAdd = baseline.evaluate(
                additionProgram(), PlutusLanguage.PLUTUS_V3, null);
        var baselineBData = baseline.evaluate(
                bDataProgram(), PlutusLanguage.PLUTUS_V3, null);

        var changedValues = ones(parameterCount);
        changedValues[0] = 101L;
        var changed = new ScalusVmProvider();
        changed.setCostModelParams(changedValues, target);
        var changedAdd = changed.evaluate(
                additionProgram(), PlutusLanguage.PLUTUS_V3, null);
        var changedBData = changed.evaluate(
                bDataProgram(), PlutusLanguage.PLUTUS_V3, null);

        assertTrue(baselineAdd.isSuccess());
        assertTrue(changedAdd.isSuccess());
        assertEquals(baselineAdd.budgetConsumed().cpuSteps() + 100,
                changedAdd.budgetConsumed().cpuSteps());
        assertEquals(baselineAdd.budgetConsumed().memoryUnits(),
                changedAdd.budgetConsumed().memoryUnits());
        assertNotEquals(baselineAdd.budgetConsumed(), changedAdd.budgetConsumed());
        assertEquals(baselineBData.budgetConsumed(), changedBData.budgetConsumed());
    }

    @Test
    void configuredRequestedMajorMismatchProducesStableUnsupportedState() {
        var provider = new ScalusVmProvider();
        var pv10 = LedgerEvaluationTarget.pv10(PlutusLanguage.PLUTUS_V3);
        provider.setCostModelParams(ones(297), pv10);

        var mismatch = assertInstanceOf(
                UnsupportedScalusConfiguration.class,
                provider.configurationForEvaluation(
                        LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V3)));

        assertTrue(mismatch.reason().startsWith(
                ScalusVmProvider.UNSUPPORTED_TARGET_PREFIX));
        assertTrue(mismatch.reason().contains(pv10.toString()));
        assertEquals(11, mismatch.target().protocolVersion().major());
        assertSame(provider.plutusV3Configuration,
                provider.configurationForEvaluation(pv10));
    }

    @Test
    void configuringV1AndV2CannotAlterReadyV3Snapshot() {
        var provider = new ScalusVmProvider();
        provider.setCostModelParams(
                ones(350), LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V3));
        var readyV3 = provider.plutusV3Configuration;

        provider.setCostModelParams(
                ones(332), LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V1));
        provider.setCostModelParams(
                ones(332), LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V2));

        assertSame(readyV3, provider.plutusV3Configuration);
        assertInstanceOf(
                UnsupportedScalusConfiguration.class, provider.plutusV1Configuration);
        assertInstanceOf(
                UnsupportedScalusConfiguration.class, provider.plutusV2Configuration);
        assertEquals(new ExBudget(8, 8), provider.evaluate(
                additionProgram(), PlutusLanguage.PLUTUS_V3, null).budgetConsumed());
    }

    @ParameterizedTest(name = "{0}/PV{1} publishes an unsupported configuration")
    @MethodSource("unsupportedConfiguredTargets")
    void configuredV1AndV2TargetsPublishUnsupportedWithoutThrowing(
            PlutusLanguage language, int protocolMajor, int parameterCount) {
        var provider = new ScalusVmProvider();
        var target = target(language, protocolMajor);

        provider.setCostModelParams(ones(parameterCount), target);

        var configuration = language == PlutusLanguage.PLUTUS_V1
                ? provider.plutusV1Configuration
                : provider.plutusV2Configuration;
        var unsupported = assertInstanceOf(
                UnsupportedScalusConfiguration.class, configuration);
        assertEquals(target, unsupported.target());
        assertEquals(target, unsupported.profile().target());
        assertTrue(unsupported.reason().startsWith(
                ScalusVmProvider.UNSUPPORTED_TARGET_PREFIX));
    }

    @Test
    void configuringAllTransactionLanguagesKeepsV3UsableAndV1V2FailClosed() {
        var provider = new ScalusVmProvider();
        provider.setCostModelParams(
                ones(332), LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V1));
        provider.setCostModelParams(
                ones(332), LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V2));
        provider.setCostModelParams(
                ones(350), LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V3));

        var v3 = provider.evaluate(
                additionProgram(), PlutusLanguage.PLUTUS_V3, null);
        assertTrue(v3.isSuccess());
        assertEquals(new ExBudget(8, 8), v3.budgetConsumed());

        for (var language : List.of(
                PlutusLanguage.PLUTUS_V1, PlutusLanguage.PLUTUS_V2)) {
            var result = provider.evaluate(
                    Program.plutusV1(Term.const_(Constant.unit())), language, null);
            var failure = assertInstanceOf(EvalResult.Failure.class, result);
            assertEquals(ExBudget.ZERO, failure.consumed());
            assertTrue(failure.error().startsWith(
                    ScalusVmProvider.UNSUPPORTED_TARGET_PREFIX));
        }
    }

    private static Stream<Arguments> v3Targets() {
        return Stream.of(
                Arguments.of(10, 297, "C"),
                Arguments.of(11, 350, "E"));
    }

    private static Stream<Arguments> unsupportedConfiguredTargets() {
        return Stream.of(
                Arguments.of(PlutusLanguage.PLUTUS_V1, 10, 166),
                Arguments.of(PlutusLanguage.PLUTUS_V1, 11, 332),
                Arguments.of(PlutusLanguage.PLUTUS_V2, 10, 185),
                Arguments.of(PlutusLanguage.PLUTUS_V2, 11, 332));
    }

    private LedgerEvaluationTarget target(PlutusLanguage language, int protocolMajor) {
        return new LedgerEvaluationTarget(language, new ProtocolVersion(protocolMajor, 0));
    }

    private long[] ones(int count) {
        var values = new long[count];
        Arrays.fill(values, 1L);
        return values;
    }

    private Program additionProgram() {
        return Program.plutusV3(Term.apply(
                Term.apply(Term.builtin(DefaultFun.AddInteger),
                        Term.const_(Constant.integer(2))),
                Term.const_(Constant.integer(3))));
    }

    private Program bDataProgram() {
        return Program.plutusV3(Term.apply(
                Term.builtin(DefaultFun.BData),
                Term.const_(Constant.byteString(new byte[]{1, 2, 3}))));
    }
}
