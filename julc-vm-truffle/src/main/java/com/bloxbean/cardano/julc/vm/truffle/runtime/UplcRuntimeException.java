package com.bloxbean.cardano.julc.vm.truffle.runtime;

import com.bloxbean.cardano.julc.core.Term;
import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.nodes.Node;

/**
 * Runtime exception thrown during Truffle-based UPLC evaluation.
 */
public final class UplcRuntimeException extends AbstractTruffleException {

    private final Term failedTerm;

    public UplcRuntimeException(String message) {
        super(message);
        this.failedTerm = null;
    }

    public UplcRuntimeException(String message, Node location) {
        super(message, location);
        this.failedTerm = null;
    }

    public UplcRuntimeException(String message, Term failedTerm) {
        super(message);
        this.failedTerm = failedTerm;
    }

    public UplcRuntimeException(String message, Term failedTerm, Node location) {
        super(message, location);
        this.failedTerm = failedTerm;
    }

    /** The UPLC term that was being evaluated when this error occurred. */
    public Term getFailedTerm() {
        return failedTerm;
    }
}
