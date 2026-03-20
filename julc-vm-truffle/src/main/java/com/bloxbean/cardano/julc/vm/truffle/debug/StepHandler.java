package com.bloxbean.cardano.julc.vm.truffle.debug;

/**
 * Callback invoked at each step during debugger execution.
 * <p>
 * Use the {@link StepEvent} to inspect the current state (file, line, budget)
 * and control execution (step over, step into, step out, resume, kill).
 */
@FunctionalInterface
public interface StepHandler {

    /**
     * Called when the debugger suspends at a statement.
     *
     * @param event the step event with current state and control methods
     */
    void onStep(StepEvent event);
}
