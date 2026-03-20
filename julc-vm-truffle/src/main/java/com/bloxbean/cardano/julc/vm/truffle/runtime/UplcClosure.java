package com.bloxbean.cardano.julc.vm.truffle.runtime;

import com.bloxbean.cardano.julc.core.Term;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.frame.MaterializedFrame;

/**
 * Lambda closure — captures a call target and the lexical environment (materialized frame).
 * <p>
 * When applied, the closure's call target is invoked with the argument value
 * and the captured frame as the lexical scope.
 * <p>
 * Also holds the original {@link Term.Lam} for result conversion.
 */
public final class UplcClosure implements TruffleObject {

    private final CallTarget callTarget;
    private final MaterializedFrame capturedFrame;
    private final Term.Lam originalTerm;

    public UplcClosure(CallTarget callTarget, MaterializedFrame capturedFrame, Term.Lam originalTerm) {
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

    public Term.Lam getOriginalTerm() {
        return originalTerm;
    }

    @Override
    public String toString() {
        return "UplcClosure";
    }
}
