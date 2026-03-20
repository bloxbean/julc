package com.bloxbean.cardano.julc.vm.java.builtins;

import com.bloxbean.cardano.julc.vm.java.CekValue;

import java.util.List;

import static com.bloxbean.cardano.julc.vm.java.builtins.BuiltinHelper.*;

/**
 * Control flow and tracing builtins.
 * <p>
 * Note: IfThenElse, ChooseUnit, and Trace return their result argument lazily
 * (they take the argument as a value). The CEK machine handles the force/delay
 * wrapping through the signature's forceCount.
 */
public final class ControlBuiltins {

    private ControlBuiltins() {}

    /**
     * IfThenElse: force=1, arity=3 → (bool, thenBranch, elseBranch)
     * Returns thenBranch if true, elseBranch if false.
     */
    public static CekValue ifThenElse(List<CekValue> args) {
        var cond = asBool(args.get(0), "IfThenElse");
        return cond ? args.get(1) : args.get(2);
    }

    /**
     * ChooseUnit: force=1, arity=2 → (unit, value)
     * Evaluates unit, then returns value.
     */
    public static CekValue chooseUnit(List<CekValue> args) {
        checkUnit(args.get(0), "ChooseUnit");
        return args.get(1);
    }

    /**
     * Trace: force=1, arity=2 → (message, value)
     * Records the trace message and returns value.
     * The actual trace recording is handled by the CekMachine.
     */
    public static CekValue trace(List<CekValue> args) {
        // The trace message is extracted by CekMachine before calling this
        return args.get(1);
    }
}
