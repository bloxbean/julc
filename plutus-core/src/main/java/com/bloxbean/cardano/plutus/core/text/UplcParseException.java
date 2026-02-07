package com.bloxbean.cardano.plutus.core.text;

/**
 * Thrown when UPLC text format cannot be parsed.
 */
public class UplcParseException extends RuntimeException {

    private final int position;

    public UplcParseException(String message, int position) {
        super(message + " at position " + position);
        this.position = position;
    }

    public UplcParseException(String message) {
        super(message);
        this.position = -1;
    }

    /** The position in the input where the error occurred, or -1 if unknown. */
    public int position() {
        return position;
    }
}
