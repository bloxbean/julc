package org.julclang.vm.java.builtins;

import org.julclang.vm.java.CekEvaluationException;

/**
 * Exception thrown when a builtin function encounters a runtime error.
 */
public class BuiltinException extends CekEvaluationException {

    public BuiltinException(String message) {
        super(message);
    }

    public BuiltinException(String message, Throwable cause) {
        super(message, cause);
    }
}
