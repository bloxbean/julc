package com.bloxbean.cardano.plutus.core.flat;

/**
 * Thrown when FLAT binary data cannot be decoded.
 */
public class FlatDecodingException extends RuntimeException {

    public FlatDecodingException(String message) {
        super(message);
    }

    public FlatDecodingException(String message, Throwable cause) {
        super(message, cause);
    }
}
