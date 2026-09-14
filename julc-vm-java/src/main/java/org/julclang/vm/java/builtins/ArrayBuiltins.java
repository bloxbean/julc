package org.julclang.vm.java.builtins;

import org.julclang.core.ArraySemantics;
import org.julclang.core.Constant;
import org.julclang.vm.java.CekValue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import static org.julclang.vm.java.builtins.BuiltinHelper.*;

/**
 * Base Array operation builtins (PV11 Batch 6, CIP-138), plus the
 * future/experimental CIP-156 multi-index operation.
 * <p>
 * The three ledger-valid operations delegate to {@link ArraySemantics}, which the compiler's
 * literal fold (ADR-046) shares with this runtime, so results and failure texts agree.
 */
public final class ArrayBuiltins {

    private ArrayBuiltins() {}

    /**
     * LengthOfArray: force=1, arity=1 → (array)
     * Returns the number of elements in the array.
     */
    public static CekValue lengthOfArray(List<CekValue> args) {
        var ac = asArrayConst(args.get(0), "LengthOfArray");
        return mkInteger(ArraySemantics.lengthOfArray(ac));
    }

    /**
     * ListToArray: force=1, arity=1 → (list)
     * Converts a list to an array (same elements, same type).
     */
    public static CekValue listToArray(List<CekValue> args) {
        var lc = asListConst(args.get(0), "ListToArray");
        return new CekValue.VCon(ArraySemantics.listToArray(lc));
    }

    /**
     * IndexArray: force=1, arity=2 → (array, index)
     * Returns the element at the given index (0-based). O(1) access.
     */
    public static CekValue indexArray(List<CekValue> args) {
        var ac = asArrayConst(args.get(0), "IndexArray");
        var idx = asInteger(args.get(1), "IndexArray");
        try {
            return new CekValue.VCon(ArraySemantics.indexArray(ac, idx));
        } catch (ArraySemantics.EvaluationFailure failure) {
            throw new BuiltinException(failure.getMessage());
        }
    }

    /**
     * MultiIndexArray: force=1, arity=2 → (array, indices_list)
     * Returns a list of elements at the given indices.
     * <p>
     * Experimental runtime support only: tag 101 is not ledger-valid in
     * Plutus V3/PV11.
     * This legacy placeholder uses {@code (array, indices)} rather than the
     * indices-first order proposed by CIP-156 and is not a conformant preview.
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
