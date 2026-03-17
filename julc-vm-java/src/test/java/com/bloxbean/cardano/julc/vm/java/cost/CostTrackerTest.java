package com.bloxbean.cardano.julc.vm.java.cost;

import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.DefaultFun;
import com.bloxbean.cardano.julc.vm.ExBudget;
import com.bloxbean.cardano.julc.vm.java.CekValue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CostTrackerTest {

    private CostTracker unlimitedTracker() {
        return new CostTracker(
                DefaultCostModel.defaultMachineCosts(),
                DefaultCostModel.defaultBuiltinCostModel(),
                null);
    }

    private CostTracker limitedTracker(long cpu, long mem) {
        return new CostTracker(
                DefaultCostModel.defaultMachineCosts(),
                DefaultCostModel.defaultBuiltinCostModel(),
                new ExBudget(cpu, mem));
    }

    @Test
    void startsAtZero() {
        var tracker = unlimitedTracker();
        assertEquals(0, tracker.consumed().cpuSteps());
        assertEquals(0, tracker.consumed().memoryUnits());
    }

    @Test
    void chargesMachineSteps() {
        var tracker = unlimitedTracker();
        tracker.chargeMachineStep(MachineCosts.StepKind.STARTUP);
        assertEquals(100, tracker.consumed().cpuSteps());
        assertEquals(100, tracker.consumed().memoryUnits());

        tracker.chargeMachineStep(MachineCosts.StepKind.CONST);
        assertEquals(16100, tracker.consumed().cpuSteps());
        assertEquals(200, tracker.consumed().memoryUnits());
    }

    @Test
    void chargesBuiltins() {
        var tracker = unlimitedTracker();
        // AddInteger with two small integers
        var args = List.<CekValue>of(
                new CekValue.VCon(Constant.integer(42)),
                new CekValue.VCon(Constant.integer(7)));
        tracker.chargeBuiltin(DefaultFun.AddInteger, args);

        var consumed = tracker.consumed();
        assertTrue(consumed.cpuSteps() > 0, "CPU should be positive: " + consumed.cpuSteps());
        assertTrue(consumed.memoryUnits() > 0, "Mem should be positive: " + consumed.memoryUnits());
    }

    @Test
    void budgetExhaustedCpu() {
        var tracker = limitedTracker(50, Long.MAX_VALUE);
        // Startup cost is 100 CPU, which exceeds our 50 limit
        assertThrows(BudgetExhaustedException.class, () ->
                tracker.chargeMachineStep(MachineCosts.StepKind.STARTUP));
    }

    @Test
    void budgetExhaustedMemory() {
        var tracker = limitedTracker(Long.MAX_VALUE, 50);
        assertThrows(BudgetExhaustedException.class, () ->
                tracker.chargeMachineStep(MachineCosts.StepKind.STARTUP));
    }

    @Test
    void unlimitedNeverExhausts() {
        var tracker = unlimitedTracker();
        // Charge many steps — should never throw
        for (int i = 0; i < 10000; i++) {
            tracker.chargeMachineStep(MachineCosts.StepKind.APPLY);
        }
        assertTrue(tracker.consumed().cpuSteps() > 0);
    }

    @Test
    void limitedAllowsSufficientBudget() {
        var tracker = limitedTracker(10_000_000, 10_000_000);
        // A few steps should be fine
        tracker.chargeMachineStep(MachineCosts.StepKind.STARTUP);
        tracker.chargeMachineStep(MachineCosts.StepKind.CONST);
        tracker.chargeMachineStep(MachineCosts.StepKind.VAR);
        assertFalse(tracker.consumed().isExhausted());
    }

    @Test
    void constantIntegerEvalBudget() {
        // Evaluating (con integer 42) should cost startup + const = 16100 CPU, 200 mem
        // This matches the conformance test expectation
        var tracker = unlimitedTracker();
        tracker.chargeMachineStep(MachineCosts.StepKind.STARTUP);
        tracker.chargeMachineStep(MachineCosts.StepKind.CONST);
        assertEquals(16100, tracker.consumed().cpuSteps());
        assertEquals(200, tracker.consumed().memoryUnits());
    }
}
