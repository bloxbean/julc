package com.bloxbean.cardano.julc.vm.java;

import com.bloxbean.cardano.julc.core.Term;

/**
 * Runtime exception thrown when the CEK machine encounters an error during evaluation.
 * <p>
 * When source maps are in use, the {@link #failedTerm()} provides the UPLC term
 * that was being evaluated when the error occurred. This can be looked up in a
 * {@link com.bloxbean.cardano.julc.core.source.SourceMap} to find the originating Java source line.
 */
public class CekEvaluationException extends RuntimeException {

    private final Term failedTerm;

    public CekEvaluationException(String message) {
        super(message);
        this.failedTerm = null;
    }

    public CekEvaluationException(String message, Term failedTerm) {
        super(message);
        this.failedTerm = failedTerm;
    }

    public CekEvaluationException(String message, Throwable cause) {
        super(message, cause);
        this.failedTerm = null;
    }

    /**
     * The UPLC term that was being evaluated when this error occurred.
     * May be null if the error was not associated with a specific term.
     */
    public Term failedTerm() {
        return failedTerm;
    }
}
