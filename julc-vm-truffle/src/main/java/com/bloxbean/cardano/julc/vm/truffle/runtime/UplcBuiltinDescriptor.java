package com.bloxbean.cardano.julc.vm.truffle.runtime;

import com.bloxbean.cardano.julc.core.DefaultFun;
import com.bloxbean.cardano.julc.vm.java.CekValue;
import com.oracle.truffle.api.interop.TruffleObject;

import java.util.List;

/**
 * Tracks partial application of a builtin function.
 * <p>
 * Builtins accumulate forces and arguments until fully saturated.
 * This mirrors {@link CekValue.VBuiltin} from the Java VM but stores
 * arguments as Truffle runtime values (which are converted to CekValue
 * only when the builtin is executed, for cost computation).
 */
public final class UplcBuiltinDescriptor implements TruffleObject {

    private final DefaultFun fun;
    private final int forcesRemaining;
    private final int argsRemaining;
    private final List<Object> collectedArgs;

    private UplcBuiltinDescriptor(DefaultFun fun, int forcesRemaining, int argsRemaining,
                                   List<Object> collectedArgs) {
        this.fun = fun;
        this.forcesRemaining = forcesRemaining;
        this.argsRemaining = argsRemaining;
        this.collectedArgs = collectedArgs; // caller ensures immutability
    }

    /** Create the initial descriptor for a builtin reference. */
    public static UplcBuiltinDescriptor initial(DefaultFun fun, int forceCount, int arity) {
        return new UplcBuiltinDescriptor(fun, forceCount, arity, List.of());
    }

    /** Apply a force to this builtin. */
    public UplcBuiltinDescriptor applyForce() {
        if (forcesRemaining <= 0) {
            throw new UplcRuntimeException("Cannot force builtin " + fun + " — no forces remaining");
        }
        return new UplcBuiltinDescriptor(fun, forcesRemaining - 1, argsRemaining, collectedArgs);
    }

    /** Apply an argument to this builtin. */
    public UplcBuiltinDescriptor applyArg(Object arg) {
        if (forcesRemaining > 0) {
            throw new UplcRuntimeException(
                    "Cannot apply argument to builtin " + fun +
                    " — " + forcesRemaining + " force(s) still needed");
        }
        if (argsRemaining <= 0) {
            throw new UplcRuntimeException("Cannot apply argument to builtin " + fun + " — already saturated");
        }
        // Single copy: build new immutable list directly
        var newArgs = new Object[collectedArgs.size() + 1];
        for (int i = 0; i < collectedArgs.size(); i++) {
            newArgs[i] = collectedArgs.get(i);
        }
        newArgs[collectedArgs.size()] = arg;
        return new UplcBuiltinDescriptor(fun, 0, argsRemaining - 1, List.of(newArgs));
    }

    /** Check if this builtin is ready to execute (fully saturated). */
    public boolean isReady() {
        return forcesRemaining == 0 && argsRemaining == 0;
    }

    public DefaultFun getFun() {
        return fun;
    }

    public int getForcesRemaining() {
        return forcesRemaining;
    }

    public int getArgsRemaining() {
        return argsRemaining;
    }

    public List<Object> getCollectedArgs() {
        return collectedArgs;
    }

    @Override
    public String toString() {
        return "UplcBuiltinDescriptor(" + fun + ", forces=" + forcesRemaining +
               ", args=" + argsRemaining + ")";
    }
}
