package com.bloxbean.cardano.julc.vm.truffle.runtime;

import com.bloxbean.cardano.julc.core.Term;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.frame.MaterializedFrame;

/**
 * Delayed (thunked) computation — captures a call target and lexical environment.
 * <p>
 * When forced, the thunk's call target is invoked with the captured frame.
 * Also holds the original {@link Term.Delay} for result conversion.
 */
public final class UplcDelay implements TruffleObject {

    private final CallTarget callTarget;
    private final MaterializedFrame capturedFrame;
    private final Term.Delay originalTerm;

    public UplcDelay(CallTarget callTarget, MaterializedFrame capturedFrame, Term.Delay originalTerm) {
        this.callTarget = callTarget;
        this.capturedFrame = capturedFrame;
        this.originalTerm = originalTerm;
    }

    public CallTarget getCallTarget() {
        return callTarget;
    }

    public MaterializedFrame getCapturedFrame() {
        return capturedFrame;
    }

    public Term.Delay getOriginalTerm() {
        return originalTerm;
    }

    @Override
    public String toString() {
        return "UplcDelay";
    }
}
