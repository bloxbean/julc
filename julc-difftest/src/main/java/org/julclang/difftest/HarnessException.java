package org.julclang.difftest;

/**
 * A fault in the harness or its input, never an observation: javac rejected a generated program
 * (ADR-063 H6), or a value has no Data mapping.
 */
public class HarnessException extends RuntimeException {

    public HarnessException(String message) {
        super(message);
    }

    public HarnessException(String message, Throwable cause) {
        super(message, cause);
    }
}
