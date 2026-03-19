package com.bloxbean.cardano.julc.vm.truffle.builtin;

import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.DefaultFun;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.vm.java.CekValue;
import com.bloxbean.cardano.julc.vm.java.builtins.BuiltinHelper;
import com.bloxbean.cardano.julc.vm.java.builtins.BuiltinRuntime;
import com.bloxbean.cardano.julc.vm.truffle.UplcContext;
import com.bloxbean.cardano.julc.vm.truffle.runtime.UplcBuiltinDescriptor;
import com.bloxbean.cardano.julc.vm.truffle.runtime.UplcRuntimeException;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Delegates builtin execution to the julc-vm-java implementations.
 * <p>
 * Pass-through builtins (IfThenElse, Trace, ChooseUnit, ChooseList, ChooseData)
 * are handled directly because they return their Truffle-typed arguments as-is —
 * they cannot be routed through the CekValue-based Java implementations since
 * CekValue is a sealed interface.
 * <p>
 * All other builtins operate purely on Constants, so they are delegated to the
 * existing BuiltinRuntime implementations from julc-vm-java.
 * <p>
 * Cost charging uses the same CostTracker from julc-vm-java, guaranteeing
 * exact budget parity.
 */
public final class BuiltinDispatcher {

    private BuiltinDispatcher() {}

    /** Builtins that return one of their arguments without transformation. */
    private static final Set<DefaultFun> PASS_THROUGH_BUILTINS = Set.of(
            DefaultFun.IfThenElse,
            DefaultFun.ChooseUnit,
            DefaultFun.Trace,
            DefaultFun.ChooseList,
            DefaultFun.ChooseData
    );

    /**
     * Execute a fully-saturated builtin descriptor.
     */
    public static Object execute(UplcBuiltinDescriptor descriptor, UplcContext context) {
        DefaultFun fun = descriptor.getFun();
        List<Object> truffleArgs = descriptor.getCollectedArgs();

        // For cost charging, we need CekValue wrappers for all args.
        // Pass-through args that aren't Constants get a sentinel VCon(null)
        // which is fine — cost models only inspect the "selector" arg (arg 0)
        // for these builtins, which is always a Constant.
        List<CekValue> cekArgs = toCekValues(truffleArgs);

        // Handle Trace: extract and record the message BEFORE charging
        if (fun == DefaultFun.Trace) {
            String msg = BuiltinHelper.asString(cekArgs.getFirst(), "Trace");
            context.addTrace(msg);
        }

        // Charge builtin cost — exactly as CekMachine.executeBuiltin() does
        context.getCostTracker().chargeBuiltin(fun, cekArgs);

        // Execute
        if (PASS_THROUGH_BUILTINS.contains(fun)) {
            return executePassThrough(fun, truffleArgs, cekArgs);
        } else {
            BuiltinRuntime runtime = context.getBuiltinTable().getRuntime(fun);
            CekValue result = runtime.execute(cekArgs);
            return fromCekValue(result);
        }
    }

    /**
     * Handle pass-through builtins that return one of their Truffle-typed arguments.
     */
    private static Object executePassThrough(DefaultFun fun, List<Object> truffleArgs,
                                              List<CekValue> cekArgs) {
        return switch (fun) {
            case IfThenElse -> {
                boolean cond = BuiltinHelper.asBool(cekArgs.get(0), "IfThenElse");
                yield cond ? truffleArgs.get(1) : truffleArgs.get(2);
            }
            case ChooseUnit -> {
                BuiltinHelper.checkUnit(cekArgs.get(0), "ChooseUnit");
                yield truffleArgs.get(1);
            }
            case Trace -> truffleArgs.get(1); // Message already recorded above
            case ChooseList -> {
                var listConst = BuiltinHelper.asListConst(cekArgs.get(0), "ChooseList");
                yield listConst.values().isEmpty() ? truffleArgs.get(1) : truffleArgs.get(2);
            }
            case ChooseData -> {
                var data = BuiltinHelper.asData(cekArgs.get(0), "ChooseData");
                yield switch (data) {
                    case PlutusData.ConstrData _ -> truffleArgs.get(1);
                    case PlutusData.MapData _    -> truffleArgs.get(2);
                    case PlutusData.ListData _   -> truffleArgs.get(3);
                    case PlutusData.IntData _    -> truffleArgs.get(4);
                    case PlutusData.BytesData _  -> truffleArgs.get(5);
                };
            }
            default -> throw new UplcRuntimeException("Not a pass-through builtin: " + fun);
        };
    }

    /**
     * Convert Truffle runtime values to CekValue wrappers for cost computation.
     * <p>
     * Constants become VCon. Non-constant values (closures, delays, etc.) that
     * appear as pass-through arguments get a VCon(null) sentinel — the cost model
     * treats null constants as size 1.
     */
    private static List<CekValue> toCekValues(List<Object> truffleArgs) {
        var cekArgs = new ArrayList<CekValue>(truffleArgs.size());
        for (Object arg : truffleArgs) {
            cekArgs.add(toCekValue(arg));
        }
        return cekArgs;
    }

    static CekValue toCekValue(Object truffleValue) {
        if (truffleValue instanceof Constant c) {
            return new CekValue.VCon(c);
        }
        // Non-constant values used as pass-through args.
        // Cost models for pass-through builtins use ConstantCost, so the
        // size of pass-through args doesn't matter. Return a VCon(UnitConst)
        // as a safe sentinel.
        return new CekValue.VCon(Constant.unit());
    }

    static Object fromCekValue(CekValue value) {
        if (value instanceof CekValue.VCon vcon) {
            return vcon.constant();
        }
        // Should not happen for non-pass-through builtin results
        throw new UplcRuntimeException("Unexpected builtin result type: " + value);
    }
}
