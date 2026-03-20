package com.bloxbean.cardano.julc.vm.truffle.debug;

import com.bloxbean.cardano.julc.vm.truffle.UplcContext;
import com.oracle.truffle.api.debug.SuspendedEvent;
import com.oracle.truffle.api.source.SourceSection;

/**
 * Wraps a Truffle {@link SuspendedEvent} to provide a Java-source-level
 * debugging experience for UPLC programs.
 * <p>
 * Each step event exposes:
 * <ul>
 *   <li>The Java source file and line being executed</li>
 *   <li>CPU and memory budget consumed so far</li>
 *   <li>Stepping controls (stepOver, stepInto, stepOut, resume, kill)</li>
 * </ul>
 */
public final class StepEvent {

    private final SuspendedEvent suspendedEvent;
    private final UplcContext uplcContext;

    StepEvent(SuspendedEvent suspendedEvent, UplcContext uplcContext) {
        this.suspendedEvent = suspendedEvent;
        this.uplcContext = uplcContext;
    }

    /**
     * The Java source file name at this suspension point.
     */
    public String fileName() {
        SourceSection ss = suspendedEvent.getSourceSection();
        if (ss == null || ss.getSource() == null) return null;
        return ss.getSource().getName();
    }

    /**
     * The 1-based line number in the Java source file.
     */
    public int line() {
        SourceSection ss = suspendedEvent.getSourceSection();
        if (ss == null) return -1;
        return ss.getStartLine();
    }

    /**
     * Total CPU steps consumed up to this point.
     */
    public long cpuConsumed() {
        return uplcContext.getCostTracker().cpuConsumed();
    }

    /**
     * Total memory units consumed up to this point.
     */
    public long memConsumed() {
        return uplcContext.getCostTracker().memConsumed();
    }

    // --- Stepping control ---

    /**
     * Step over to the next Java source line (skips into function calls).
     */
    public void stepOver() {
        suspendedEvent.prepareStepOver(1);
    }

    /**
     * Step into the next function call.
     */
    public void stepInto() {
        suspendedEvent.prepareStepInto(1);
    }

    /**
     * Step out of the current function.
     */
    public void stepOut() {
        suspendedEvent.prepareStepOut(1);
    }

    /**
     * Resume execution (continue to next breakpoint or completion).
     */
    public void resume() {
        suspendedEvent.prepareContinue();
    }

    /**
     * Kill execution — aborts the program.
     */
    public void kill() {
        suspendedEvent.prepareKill();
    }

    /**
     * The underlying Truffle suspended event (for advanced use / IDE integration).
     */
    public SuspendedEvent getSuspendedEvent() {
        return suspendedEvent;
    }

    @Override
    public String toString() {
        return "StepEvent{" + fileName() + ":" + line() +
                " cpu=" + cpuConsumed() + " mem=" + memConsumed() + "}";
    }
}
