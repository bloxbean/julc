package com.bloxbean.cardano.julc.vm.java.builtins;

import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.vm.java.CekValue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import static com.bloxbean.cardano.julc.vm.java.builtins.BuiltinHelper.*;

/**
 * Array operation builtins (PV11 Batch 6, CIP-156).
 */
public final class ArrayBuiltins {

    private ArrayBuiltins() {}

    /**
     * LengthOfArray: force=1, arity=1 → (array)
     * Returns the number of elements in the array.
     */
    public static CekValue lengthOfArray(List<CekValue> args) {
        var ac = asArrayConst(args.get(0), "LengthOfArray");
        return mkInteger(ac.values().size());
    }

    /**
     * ListToArray: force=1, arity=1 → (list)
     * Converts a list to an array (same elements, same type).
     */
    public static CekValue listToArray(List<CekValue> args) {
        var lc = asListConst(args.get(0), "ListToArray");
        return mkArray(lc.elemType(), lc.values());
    }

    /**
     * IndexArray: force=1, arity=2 → (array, index)
     * Returns the element at the given index (0-based). O(1) access.
     */
    public static CekValue indexArray(List<CekValue> args) {
        var ac = asArrayConst(args.get(0), "IndexArray");
        var idx = asInteger(args.get(1), "IndexArray");
        int i;
        try {
            i = idx.intValueExact();
        } catch (ArithmeticException e) {
            throw new BuiltinException("IndexArray: index out of range: " + idx);
        }
        if (i < 0 || i >= ac.values().size()) {
            throw new BuiltinException("IndexArray: index " + i +
                    " out of bounds for array of size " + ac.values().size());
        }
        return new CekValue.VCon(ac.values().get(i));
    }

    /**
     * MultiIndexArray: force=1, arity=2 → (array, indices_list)
     * Returns a list of elements at the given indices.
     */
    public static CekValue multiIndexArray(List<CekValue> args) {
        var ac = asArrayConst(args.get(0), "MultiIndexArray");
        var indicesList = asListConst(args.get(1), "MultiIndexArray");
        var result = new ArrayList<Constant>();
        for (var idxConst : indicesList.values()) {
            if (!(idxConst instanceof Constant.IntegerConst ic)) {
                throw new BuiltinException("MultiIndexArray: indices must be integers");
            }
            int i;
            try {
                i = ic.value().intValueExact();
            } catch (ArithmeticException e) {
                throw new BuiltinException("MultiIndexArray: index out of range: " + ic.value());
            }
            if (i < 0 || i >= ac.values().size()) {
                throw new BuiltinException("MultiIndexArray: index " + i +
                        " out of bounds for array of size " + ac.values().size());
            }
            result.add(ac.values().get(i));
        }
        return mkList(ac.elemType(), result);
    }
}
