package com.bloxbean.cardano.julc.vm.scalus;

import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.DefaultFun;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.core.Term;
import com.bloxbean.cardano.julc.vm.EvalOptions;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.julc.vm.ExBudget;
import com.bloxbean.cardano.julc.vm.LedgerEvaluationTarget;
import com.bloxbean.cardano.julc.vm.PlutusLanguage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScalusBudgetEnforcementTest {

    private static final LedgerEvaluationTarget V3_PV11 =
            LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V3);

    @ParameterizedTest(name = "{0}: below/exact/above and axis limits")
    @MethodSource("budgetCases")
    void enforcesPinnedBudgetBoundariesOnBothDimensions(
            String name, boolean candidate, Program program, ExBudget required) {
        var provider = providerFor(candidate);

        var unlimited = evaluate(provider, candidate, program, null);
        assertSuccessWithBudget(unlimited, required);

        var exact = evaluate(provider, candidate, program, required);
        assertEquals(unlimited, exact, "exact limit must preserve the counting result");

        var above = evaluate(provider, candidate, program,
                new ExBudget(required.cpuSteps() + 1, required.memoryUnits() + 1));
        assertEquals(unlimited, above, "above limit must preserve the counting result");

        var belowLimit = new ExBudget(
                required.cpuSteps() - 1, required.memoryUnits() - 1);
        var below = assertBudgetExhausted(
                evaluate(provider, candidate, program, belowLimit), required);
        assertTrue(below.consumed().cpuSteps() > belowLimit.cpuSteps(),
                "both-short limit: consumed CPU must exceed the CPU limit");
        assertTrue(below.consumed().memoryUnits() > belowLimit.memoryUnits(),
                "both-short limit: consumed memory must exceed the memory limit");

        var memoryLimit = new ExBudget(
                required.cpuSteps(), required.memoryUnits() - 1);
        var memoryExhausted = assertBudgetExhausted(
                evaluate(provider, candidate, program, memoryLimit), required);
        assertEquals(required.cpuSteps(), memoryExhausted.consumed().cpuSteps(),
                "memory orientation: CPU must fit exactly");
        assertTrue(memoryExhausted.consumed().memoryUnits() > memoryLimit.memoryUnits(),
                "memory orientation: consumed memory must exceed the memory limit");

        var cpuLimit = new ExBudget(
                required.cpuSteps() - 1, required.memoryUnits());
        var cpuExhausted = assertBudgetExhausted(
                evaluate(provider, candidate, program, cpuLimit), required);
        assertTrue(cpuExhausted.consumed().cpuSteps() > cpuLimit.cpuSteps(),
                "CPU orientation: consumed CPU must exceed the CPU limit");
        assertEquals(required.memoryUnits(), cpuExhausted.consumed().memoryUnits(),
                "CPU orientation: memory must fit exactly");
    }

    @ParameterizedTest(name = "{0}: CEK Error remains Failure")
    @MethodSource("routes")
    void cekErrorWithAmpleBudgetIsNotBudgetExhaustion(
            String name, boolean candidate, ExBudget ignoredArgumentBudget,
            ExBudget errorBudget) {
        var provider = providerFor(candidate);
        var ample = new ExBudget(
                errorBudget.cpuSteps() + 1, errorBudget.memoryUnits() + 1);

        var result = evaluate(
                provider, candidate, Program.plutusV3(new Term.Error()), ample);

        var failure = assertInstanceOf(EvalResult.Failure.class, result);
        assertEquals("Error evaluated", failure.error());
        assertEquals(errorBudget, failure.consumed());
    }

    @ParameterizedTest(name = "{0}: argument-bearing path restricts execution")
    @MethodSource("routes")
    void enforcesBudgetOnArgumentBearingOverloads(
            String name, boolean candidate, ExBudget required,
            ExBudget ignoredErrorBudget) {
        var provider = providerFor(candidate);
        var identity = Program.plutusV3(Term.lam("arg", Term.var(1)));
        var args = List.of(PlutusData.integer(42));

        var unlimited = evaluateWithArgs(provider, candidate, identity, args, null);
        assertSuccessWithBudget(unlimited, required);

        var exact = evaluateWithArgs(provider, candidate, identity, args, required);
        assertEquals(unlimited, exact);

        var cpuShort = new ExBudget(
                required.cpuSteps() - 1, required.memoryUnits());
        var exhausted = assertBudgetExhausted(
                evaluateWithArgs(provider, candidate, identity, args, cpuShort),
                required);
        assertTrue(exhausted.consumed().cpuSteps() > cpuShort.cpuSteps(),
                "argument path: consumed CPU must exceed the CPU limit");
    }

    @Test
    void configuredLanguageOnlyPathMatchesTransactionEvaluatorBudgetShape() {
        var provider = providerFor(true);
        var program = Program.plutusV3(Term.lam("arg", Term.var(1)));
        var args = List.of(PlutusData.integer(42));
        var required = new ExBudget(5, 5);

        var counted = provider.evaluateWithArgs(
                program, PlutusLanguage.PLUTUS_V3, args, null);
        assertSuccessWithBudget(counted, required);

        var txBudgetTooSmall = new ExBudget(4, 5);
        var exhausted = assertBudgetExhausted(provider.evaluateWithArgs(
                program, PlutusLanguage.PLUTUS_V3, args, txBudgetTooSmall), required);
        assertTrue(exhausted.consumed().cpuSteps() > txBudgetTooSmall.cpuSteps(),
                "transaction-shaped path: consumed CPU must exceed the tx budget");

        var sufficient = provider.evaluateWithArgs(
                program, PlutusLanguage.PLUTUS_V3, args, required);
        assertEquals(counted, sufficient);
    }

    @Test
    void paddedCostsSaturateWrappedScalusCountersWithAndWithoutLimit() {
        var provider = new ScalusVmProvider();
        var stalePv10Values = new long[297];
        Arrays.fill(stalePv10Values, 1L);
        provider.setCostModelParams(stalePv10Values, V3_PV11);

        var unlimited = assertInstanceOf(EvalResult.Success.class,
                provider.evaluateWithArgs(
                        expModIdentityProgram(), PlutusLanguage.PLUTUS_V3,
                        List.of(PlutusData.integer(42)), null));
        assertEquals(Long.MAX_VALUE, unlimited.consumed().cpuSteps());
        assertEquals(Long.MAX_VALUE, unlimited.consumed().memoryUnits());

        var result = provider.evaluateWithArgs(
                expModIdentityProgram(), PlutusLanguage.PLUTUS_V3,
                List.of(PlutusData.integer(42)), new ExBudget(100, 100));

        var exhausted = assertInstanceOf(EvalResult.BudgetExhausted.class, result);
        assertEquals(Long.MAX_VALUE, exhausted.consumed().cpuSteps());
        assertEquals(Long.MAX_VALUE, exhausted.consumed().memoryUnits());
    }

    private static Stream<Arguments> budgetCases() {
        return Stream.of(
                Arguments.of("legacy machine-heavy", false,
                        machineHeavyProgram(), new ExBudget(400_100, 2_600)),
                Arguments.of("legacy builtin-heavy", false,
                        builtinHeavyProgram(), new ExBudget(1_998_596, 5_024)),
                Arguments.of("candidate machine-heavy", true,
                        machineHeavyProgram(), new ExBudget(26, 26)),
                Arguments.of("candidate builtin-heavy", true,
                        builtinHeavyProgram(), new ExBudget(74, 74)));
    }

    private static Stream<Arguments> routes() {
        return Stream.of(
                Arguments.of("legacy V3", false,
                        new ExBudget(64_100, 500), new ExBudget(100, 100)),
                Arguments.of("candidate V3/PV11", true,
                        new ExBudget(5, 5), new ExBudget(1, 1)));
    }

    private static ScalusVmProvider providerFor(boolean configured) {
        var provider = new ScalusVmProvider();
        if (configured) {
            var values = new long[350];
            Arrays.fill(values, 1L);
            provider.setCostModelParams(values, V3_PV11);
        }
        return provider;
    }

    private static EvalResult evaluate(
            ScalusVmProvider provider, boolean candidate,
            Program program, ExBudget budget) {
        return candidate
                ? provider.evaluateCandidate(
                program, V3_PV11, budget, EvalOptions.DEFAULT)
                : provider.evaluate(program, PlutusLanguage.PLUTUS_V3, budget);
    }

    private static EvalResult evaluateWithArgs(
            ScalusVmProvider provider, boolean candidate, Program program,
            List<PlutusData> args, ExBudget budget) {
        return candidate
                ? provider.evaluateCandidate(
                program, V3_PV11, args, budget, EvalOptions.DEFAULT)
                : provider.evaluateWithArgs(
                program, PlutusLanguage.PLUTUS_V3, args, budget);
    }

    private static Program machineHeavyProgram() {
        Term term = Term.const_(Constant.integer(42));
        for (int i = 0; i < 8; i++) {
            term = Term.apply(Term.lam("x" + i, Term.var(1)), term);
        }
        return Program.plutusV3(term);
    }

    private static Program builtinHeavyProgram() {
        Term term = Term.const_(Constant.integer(0));
        for (int i = 1; i <= 12; i++) {
            term = Term.apply(
                    Term.apply(Term.builtin(DefaultFun.AddInteger), term),
                    Term.const_(Constant.integer(i)));
        }
        return Program.plutusV3(term);
    }

    private static Program expModIdentityProgram() {
        var expMod = Term.apply(
                Term.apply(
                        Term.apply(Term.builtin(DefaultFun.ExpModInteger),
                                Term.const_(Constant.integer(2))),
                        Term.const_(Constant.integer(8))),
                Term.const_(Constant.integer(17)));
        return Program.plutusV3(Term.lam("ignored", expMod));
    }

    private static EvalResult.Success assertSuccessWithBudget(
            EvalResult result, ExBudget expected) {
        var success = assertInstanceOf(EvalResult.Success.class, result,
                () -> "Expected success, got " + result);
        assertEquals(expected, success.consumed());
        return success;
    }

    private static EvalResult.BudgetExhausted assertBudgetExhausted(
            EvalResult result, ExBudget expectedConsumed) {
        var exhausted = assertInstanceOf(EvalResult.BudgetExhausted.class, result,
                () -> "Expected budget exhaustion, got " + result);
        assertEquals(expectedConsumed, exhausted.consumed());
        assertNull(exhausted.failedTerm(),
                "Scalus does not expose the term whose budget charge failed");
        return exhausted;
    }
}
