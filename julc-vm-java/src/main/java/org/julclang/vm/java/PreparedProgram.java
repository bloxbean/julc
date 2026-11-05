package org.julclang.vm.java;

import org.julclang.core.Term;
import org.julclang.vm.EvalOptions;
import org.julclang.vm.EvalResult;
import org.julclang.vm.ExBudget;
import org.julclang.vm.ProtocolFeatureProfile;
import org.julclang.vm.java.cost.BuiltinCostModel;
import org.julclang.vm.java.cost.CostTracker;
import org.julclang.vm.java.cost.MachineCosts;

/**
 * A program checked and costed for one ledger target by {@link JavaVmProvider#prepare}, ready to run any number of
 * times.
 * <p>
 * Immutable and thread-safe: it holds only the term, the protocol profile and the cost models (or the failure that
 * prepared it), and every {@link #evaluate} builds its own {@link CostTracker} and {@link CekMachine}. A configured
 * cost model is captured when the program is prepared; a later {@link JavaVmProvider#setCostModelParams} does not
 * change it.
 */
public final class PreparedProgram {

    private final Term term;
    private final ProtocolFeatureProfile profile;
    private final MachineCosts machineCosts;
    private final BuiltinCostModel builtinCostModel;
    private final EvalResult failure;

    private PreparedProgram(Term term, ProtocolFeatureProfile profile, MachineCosts machineCosts,
                            BuiltinCostModel builtinCostModel, EvalResult failure) {
        this.term = term;
        this.profile = profile;
        this.machineCosts = machineCosts;
        this.builtinCostModel = builtinCostModel;
        this.failure = failure;
    }

    static PreparedProgram ready(Term term, ProtocolFeatureProfile profile, MachineCosts machineCosts,
                                 BuiltinCostModel builtinCostModel) {
        return new PreparedProgram(term, profile, machineCosts, builtinCostModel, null);
    }

    static PreparedProgram failed(EvalResult failure) {
        return new PreparedProgram(null, null, null, null, failure);
    }

    /**
     * Evaluate the program with a fresh cost tracker and CEK machine.
     *
     * @param budget  the budget of this run, or null for unlimited
     * @param options the tracing and source-map options of this run
     * @return the result; the preparation failure, without running, if the program could not be prepared
     */
    public EvalResult evaluate(ExBudget budget, EvalOptions options) {
        if (failure != null) {
            return failure;
        }
        var costTracker = new CostTracker(machineCosts, builtinCostModel, profile, budget);
        var machine = machine(costTracker, options);
        try {
            CekValue result = machine.evaluate(term);
            return JavaVmProvider.successOf(result, costTracker, machine);
        } catch (Exception e) {
            return JavaVmProvider.failureOf(e, costTracker, machine);
        }
    }

    SteppingEvaluation startStepping(ExBudget budget, EvalOptions options) {
        if (failure != null) {
            return SteppingEvaluation.finished(failure);
        }
        var costTracker = new CostTracker(machineCosts, builtinCostModel, profile, budget);
        return SteppingEvaluation.start(machine(costTracker, options), costTracker, term);
    }

    private CekMachine machine(CostTracker costTracker, EvalOptions options) {
        return new CekMachine(costTracker, profile,
                options.sourceMap(), options.tracingEnabled(), options.builtinTraceEnabled());
    }
}
