package com.bloxbean.cardano.julc.vm.java;

import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.DefaultFun;
import com.bloxbean.cardano.julc.core.Term;

import java.util.List;

/**
 * Values in the CEK machine — the results of evaluation.
 * <p>
 * These are the "V" in the CEK (Control, Environment, Kontinuation) machine.
 */
public sealed interface CekValue {

    /** A constant value (integer, bytestring, bool, data, etc.). */
    record VCon(Constant constant) implements CekValue {}

    /** A delayed (thunked) computation. */
    record VDelay(Term body, CekEnvironment env) implements CekValue {}

    /** A lambda closure (function value). */
    record VLam(String paramName, Term body, CekEnvironment env) implements CekValue {}

    /** A constructor value (SOP — Sums of Products, Plutus V3). */
    record VConstr(long tag, List<CekValue> fields) implements CekValue {
        public VConstr {
            fields = List.copyOf(fields);
        }
    }

    /**
     * A partially-applied builtin function.
     * <p>
     * Builtins accumulate forces and arguments until they are fully saturated,
     * at which point they execute.
     */
    record VBuiltin(DefaultFun fun, int forcesRemaining, int argsRemaining,
                    List<CekValue> collectedArgs) implements CekValue {
        public VBuiltin {
            collectedArgs = List.copyOf(collectedArgs);
        }

        /** Create the initial VBuiltin for a builtin function reference. */
        public static VBuiltin initial(DefaultFun fun, int forceCount, int arity) {
            return new VBuiltin(fun, forceCount, arity, List.of());
        }

        /** Apply a force to this builtin. */
        public VBuiltin applyForce() {
            if (forcesRemaining <= 0) {
                throw new CekEvaluationException("Cannot force builtin " + fun + " — no forces remaining");
            }
            return new VBuiltin(fun, forcesRemaining - 1, argsRemaining, collectedArgs);
        }

        /** Apply an argument to this builtin. */
        public VBuiltin applyArg(CekValue arg) {
            if (forcesRemaining > 0) {
                throw new CekEvaluationException(
                        "Cannot apply argument to builtin " + fun +
                        " — " + forcesRemaining + " force(s) still needed");
            }
            if (argsRemaining <= 0) {
                throw new CekEvaluationException("Cannot apply argument to builtin " + fun + " — already saturated");
            }
            var newArgs = new java.util.ArrayList<>(collectedArgs);
            newArgs.add(arg);
            return new VBuiltin(fun, 0, argsRemaining - 1, newArgs);
        }

        /** Check if this builtin is ready to execute (fully saturated). */
        public boolean isReady() {
            return forcesRemaining == 0 && argsRemaining == 0;
        }
    }
}
