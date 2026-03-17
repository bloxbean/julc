package com.bloxbean.cardano.julc.vm.java.builtins;

/**
 * Exception thrown when a builtin function is not yet implemented.
 */
public class UnsupportedBuiltinException extends BuiltinException {

    public UnsupportedBuiltinException(String message) {
        super(message);
    }
}
