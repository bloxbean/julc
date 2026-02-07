package com.bloxbean.cardano.plutus.vm;

import com.bloxbean.cardano.plutus.core.Constant;
import com.bloxbean.cardano.plutus.core.PlutusData;
import com.bloxbean.cardano.plutus.core.Program;
import com.bloxbean.cardano.plutus.core.Term;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlutusVmTest {

    /**
     * A mock provider for testing the PlutusVm facade.
     */
    static class MockProvider implements PlutusVmProvider {
        int evaluateCallCount = 0;
        int evaluateWithArgsCallCount = 0;
        PlutusLanguage lastLanguage;
        ExBudget lastBudget;
        List<PlutusData> lastArgs;

        @Override
        public EvalResult evaluate(Program program, PlutusLanguage language, ExBudget budget) {
            evaluateCallCount++;
            lastLanguage = language;
            lastBudget = budget;
            return new EvalResult.Success(
                    Term.const_(Constant.unit()),
                    new ExBudget(100, 50),
                    List.of());
        }

        @Override
        public EvalResult evaluateWithArgs(Program program, PlutusLanguage language,
                                           List<PlutusData> args, ExBudget budget) {
            evaluateWithArgsCallCount++;
            lastLanguage = language;
            lastArgs = args;
            lastBudget = budget;
            return new EvalResult.Success(
                    Term.const_(Constant.unit()),
                    new ExBudget(200, 100),
                    List.of("applied"));
        }

        @Override
        public String name() { return "MockVM"; }

        @Override
        public int priority() { return 99; }
    }

    @Test
    void withProviderDefaultsToV3() {
        var mock = new MockProvider();
        var vm = PlutusVm.withProvider(mock);
        assertEquals(PlutusLanguage.PLUTUS_V3, vm.language());
        assertEquals("MockVM", vm.providerName());
    }

    @Test
    void withProviderAndLanguage() {
        var mock = new MockProvider();
        var vm = PlutusVm.withProvider(mock, PlutusLanguage.PLUTUS_V1);
        assertEquals(PlutusLanguage.PLUTUS_V1, vm.language());
    }

    @Test
    void evaluatePassesLanguage() {
        var mock = new MockProvider();
        var vm = PlutusVm.withProvider(mock, PlutusLanguage.PLUTUS_V2);
        var program = Program.plutusV2(Term.error());
        vm.evaluate(program);
        assertEquals(PlutusLanguage.PLUTUS_V2, mock.lastLanguage);
        assertNull(mock.lastBudget); // unlimited
    }

    @Test
    void evaluateWithBudget() {
        var mock = new MockProvider();
        var vm = PlutusVm.withProvider(mock);
        var budget = new ExBudget(10000, 5000);
        vm.evaluate(Program.plutusV3(Term.error()), budget);
        assertEquals(budget, mock.lastBudget);
    }

    @Test
    void evaluateReturnsResult() {
        var mock = new MockProvider();
        var vm = PlutusVm.withProvider(mock);
        var result = vm.evaluate(Program.plutusV3(Term.error()));
        assertTrue(result.isSuccess());
        assertEquals(new ExBudget(100, 50), result.budgetConsumed());
    }

    @Test
    void evaluateWithArgs() {
        var mock = new MockProvider();
        var vm = PlutusVm.withProvider(mock);
        var args = List.of(PlutusData.integer(42), PlutusData.bytes(new byte[]{0x01}));
        var result = vm.evaluateWithArgs(Program.plutusV3(Term.error()), args);
        assertEquals(1, mock.evaluateWithArgsCallCount);
        assertEquals(args, mock.lastArgs);
        assertTrue(result.isSuccess());
        assertEquals(List.of("applied"), result.traces());
    }

    @Test
    void evaluateWithArgsAndBudget() {
        var mock = new MockProvider();
        var vm = PlutusVm.withProvider(mock);
        var budget = new ExBudget(5000, 2000);
        var args = List.of(PlutusData.integer(1));
        vm.evaluateWithArgs(Program.plutusV3(Term.error()), args, budget);
        assertEquals(budget, mock.lastBudget);
        assertEquals(args, mock.lastArgs);
    }

    @Test
    void createWithNoProviderThrows() {
        // ServiceLoader won't find any providers in test environment (unless one is registered)
        // This test verifies the error message is helpful
        assertThrows(IllegalStateException.class, PlutusVm::create);
    }

    @Test
    void plutusLanguageVersions() {
        assertEquals(1, PlutusLanguage.PLUTUS_V1.uplcMajor());
        assertEquals(0, PlutusLanguage.PLUTUS_V1.uplcMinor());
        assertEquals(1, PlutusLanguage.PLUTUS_V3.uplcMajor());
        assertEquals(1, PlutusLanguage.PLUTUS_V3.uplcMinor());
    }
}
