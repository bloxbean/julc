package com.bloxbean.cardano.julc.vm.java.cost;

import com.bloxbean.cardano.julc.core.DefaultFun;
import com.bloxbean.cardano.julc.vm.ExBudget;
import com.bloxbean.cardano.julc.vm.LedgerEvaluationTarget;
import com.bloxbean.cardano.julc.vm.ProtocolFeatureProfile;
import com.bloxbean.cardano.julc.vm.ProtocolFeatureRegistry;
import com.bloxbean.cardano.julc.vm.java.CekValue;
import com.bloxbean.cardano.julc.vm.java.builtins.UnsupportedBuiltinException;

import java.util.List;

/**
 * Tracks CPU and memory consumption during CEK machine evaluation.
 * <p>
 * When a budget limit is provided, throws {@link BudgetExhaustedException}
 * if either dimension is exceeded. When no limit is set (unlimited mode),
 * tracks costs without enforcement.
 */
public final class CostTracker {

    private final MachineCosts machineCosts;
    private final BuiltinCostModel builtinCostModel;
    private final ProtocolFeatureProfile profile;
    private final boolean hasLimit;
    private long cpuRemaining;
    private long memRemaining;
    private long cpuConsumed;
    private long memConsumed;

    /**
     * Create a cost tracker with a budget limit.
     *
     * @param machineCosts    per-step machine costs
     * @param builtinCostModel per-builtin cost functions
     * @param budget          maximum allowed budget (null for unlimited)
     */
    public CostTracker(MachineCosts machineCosts, BuiltinCostModel builtinCostModel, ExBudget budget) {
        this(machineCosts, builtinCostModel,
                ProtocolFeatureRegistry.resolve(LedgerEvaluationTarget.pv10(
                        com.bloxbean.cardano.julc.vm.PlutusLanguage.PLUTUS_V3)), budget);
    }

    /** Create a tracker for a resolved ledger profile. */
    public CostTracker(MachineCosts machineCosts, BuiltinCostModel builtinCostModel,
                       ProtocolFeatureProfile profile, ExBudget budget) {
        this.machineCosts = machineCosts;
        this.builtinCostModel = builtinCostModel;
        this.profile = profile;
        if (budget != null) {
            this.hasLimit = true;
            this.cpuRemaining = budget.cpuSteps();
            this.memRemaining = budget.memoryUnits();
        } else {
            this.hasLimit = false;
            this.cpuRemaining = Long.MAX_VALUE;
            this.memRemaining = Long.MAX_VALUE;
        }
        this.cpuConsumed = 0;
        this.memConsumed = 0;
    }

    /**
     * Charge the cost of a machine step.
     *
     * @param kind the step kind
     * @throws BudgetExhaustedException if budget is exceeded
     */
    public void chargeMachineStep(MachineCosts.StepKind kind) {
        charge(machineCosts.cpuFor(kind), machineCosts.memFor(kind));
    }

    /**
     * Charge the cost of executing a builtin function.
     *
     * @param fun  the builtin function
     * @param args the evaluated arguments
     * @throws UnsupportedBuiltinException if the builtin is unavailable to the profile
     * @throws IncompleteCostModelException if an available builtin has no price
     * @throws BudgetExhaustedException if budget is exceeded
     */
    public void chargeBuiltin(DefaultFun fun, List<CekValue> args) {
        if (!profile.isBuiltinAvailable(fun)) {
            throw new UnsupportedBuiltinException(
                    "Builtin " + fun + " is not available for " + profile.target());
        }
        var costPair = builtinCostModel.get(fun);
        if (costPair == null) {
            throw IncompleteCostModelException.missing(profile, List.of(fun));
        }
        long[] sizes = BuiltinCostModel.argSizes(profile.semanticsVariant(), fun, args);
        long cpu = costPair.cpu().apply(sizes);
        long mem = costPair.mem().apply(sizes);
        charge(cpu, mem);
    }

    /** Get the total budget consumed so far. */
    public ExBudget consumed() {
        return new ExBudget(cpuConsumed, memConsumed);
    }

    /** Total CPU consumed so far (zero-allocation accessor). */
    public long cpuConsumed() { return cpuConsumed; }

    /** Total memory consumed so far (zero-allocation accessor). */
    public long memConsumed() { return memConsumed; }

    /** The immutable profile used for all costing decisions. */
    public ProtocolFeatureProfile profile() { return profile; }

    private void charge(long cpu, long mem) {
        cpuConsumed = CostFunction.satAdd(cpuConsumed, cpu);
        memConsumed = CostFunction.satAdd(memConsumed, mem);
        if (hasLimit) {
            cpuRemaining -= cpu;
            memRemaining -= mem;
            if (cpuRemaining < 0) {
                throw new BudgetExhaustedException(
                        "CPU budget exhausted: consumed " + cpuConsumed +
                        " (limit was " + (cpuConsumed + cpuRemaining) + ")");
            }
            if (memRemaining < 0) {
                throw new BudgetExhaustedException(
                        "Memory budget exhausted: consumed " + memConsumed +
                        " (limit was " + (memConsumed + memRemaining) + ")");
            }
        }
    }
}
