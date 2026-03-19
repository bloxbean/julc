package com.bloxbean.cardano.julc.bls;

/**
 * Exception thrown when a BLS12-381 operation fails.
 */
public class BlsException extends RuntimeException {

    public BlsException(String message) {
        super(message);
    }

    public BlsException(String message, Throwable cause) {
        super(message, cause);
    }
}
