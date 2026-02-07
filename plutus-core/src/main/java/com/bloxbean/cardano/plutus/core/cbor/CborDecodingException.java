package com.bloxbean.cardano.plutus.core.cbor;

/**
 * Thrown when CBOR data cannot be decoded to PlutusData.
 */
public class CborDecodingException extends RuntimeException {

    public CborDecodingException(String message) {
        super(message);
    }

    public CborDecodingException(String message, Throwable cause) {
        super(message, cause);
    }
}
