package com.bloxbean.cardano.julc.vm.java.builtins;

import com.bloxbean.cardano.julc.vm.java.CekValue;

import java.util.List;

import static com.bloxbean.cardano.julc.vm.java.builtins.BuiltinHelper.*;

/**
 * Extended list operation builtins (PV11 Batch 6, CIP-158).
 */
public final class ListExtBuiltins {

    private ListExtBuiltins() {}

    /**
     * DropList: force=1, arity=2 → (n, list)
     * Drops the first n elements from a list.
     * <p>
     * If n <= 0, returns the list unchanged.
     * If n >= list length, returns an empty list.
     */
    public static CekValue dropList(List<CekValue> args) {
        var n = asInteger(args.get(0), "DropList");
        var listConst = asListConst(args.get(1), "DropList");
        if (n.signum() <= 0) {
            return args.get(1); // drop 0 or negative → no change
        }
        // If n is larger than list size (or larger than Integer.MAX_VALUE), return empty
        if (n.bitLength() > 31 || n.intValueExact() >= listConst.values().size()) {
            return mkList(listConst.elemType(), List.of());
        }
        int drop = n.intValueExact();
        return mkList(listConst.elemType(), listConst.values().subList(drop, listConst.values().size()));
    }
}
