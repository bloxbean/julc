package com.bloxbean.cardano.julc.vm.java;

/**
 * Runtime exception thrown when the CEK machine encounters an error during evaluation.
 */
public class CekEvaluationException extends RuntimeException {

    public CekEvaluationException(String message) {
        super(message);
    }

    public CekEvaluationException(String message, Throwable cause) {
        super(message, cause);
    }
}
