package com.bloxbean.cardano.julc.vm.java.builtins;

import com.bloxbean.cardano.julc.vm.java.CekValue;

import java.util.List;

/**
 * Functional interface for executing a builtin function.
 * <p>
 * Implementations receive the list of fully-evaluated arguments and return the result value.
 */
@FunctionalInterface
public interface BuiltinRuntime {

    /**
     * Execute the builtin with the given arguments.
     *
     * @param args the fully-evaluated arguments
     * @return the result value
     * @throws BuiltinException if the builtin encounters a runtime error
     */
    CekValue execute(List<CekValue> args) throws BuiltinException;
}
