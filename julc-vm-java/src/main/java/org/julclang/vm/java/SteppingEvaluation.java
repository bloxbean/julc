package org.julclang.vm.java;

import org.julclang.core.Term;
import org.julclang.vm.EvalResult;
import org.julclang.vm.java.cost.CostTracker;

/**
 * A program evaluated one CEK machine transition at a time.
 * <p>
 * Created by {@link JavaVmProvider#startStepping}. Between steps the {@link #machine()} can be inspected (current
 * term, environment, frames) and the {@link #costTracker()} reports the budget consumed so far. When evaluation
 * finishes, successfully or not, {@link #result()} holds the same {@link EvalResult} a normal evaluation returns.
 */
public final class SteppingEvaluation {

    private final CekMachine machine;
    private final CostTracker costTracker;
    private EvalResult result;
    private long steps;

    private SteppingEvaluation(CekMachine machine, CostTracker costTracker, EvalResult result) {
        this.machine = machine;
        this.costTracker = costTracker;
        this.result = result;
    }

    static SteppingEvaluation finished(EvalResult result) {
        return new SteppingEvaluation(null, null, result);
    }

    static SteppingEvaluation start(CekMachine machine, CostTracker costTracker, Term term) {
        var evaluation = new SteppingEvaluation(machine, costTracker, null);
        try {
            machine.start(term);
        } catch (Exception e) {
            evaluation.result = JavaVmProvider.failureOf(e, costTracker, machine);
        }
        return evaluation;
    }

    /**
     * Perform one machine transition.
     *
     * @return {@code true} while evaluation continues; {@code false} once {@link #result()} is available
     */
    public boolean step() {
        if (result != null) {
            return false;
        }
        steps++;
        try {
            if (machine.step()) {
                return true;
            }
            result = JavaVmProvider.successOf(machine.result(), costTracker, machine);
        } catch (Exception e) {
            result = JavaVmProvider.failureOf(e, costTracker, machine);
        }
        return false;
    }

    /** Whether evaluation has finished. */
    public boolean isFinished() {
        return result != null;
    }

    /** Transitions performed so far, including the one that finished evaluation. */
    public long steps() {
        return steps;
    }

    /** The final result, or {@code null} while evaluation continues. */
    public EvalResult result() {
        return result;
    }

    /** The machine, for inspection between steps; {@code null} if the program was rejected before evaluation. */
    public CekMachine machine() {
        return machine;
    }

    /** Budget consumed so far; {@code null} if the program was rejected before evaluation. */
    public CostTracker costTracker() {
        return costTracker;
    }
}
