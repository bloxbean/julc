package org.julclang.core;

import org.julclang.core.Constant.ArrayConst;
import org.julclang.core.Constant.ListConst;

import java.math.BigInteger;
import java.util.Objects;

/**
 * Pinned Plutus semantics of the PV11 array builtins (CIP-138) over constants:
 * {@code LengthOfArray}, {@code ListToArray} and {@code IndexArray}.
 *
 * <p>{@code IndexArray} fails outside {@code 0 <= index < length}, with one text for an index
 * beyond the machine integer range and another for an in-range integer that is out of
 * bounds; the other two are total. Keeping this in {@code julc-core} gives the VM runtime and
 * the compiler's literal fold (ADR-046, O10) one source of truth, as
 * {@link NativeValueSemantics} does for the Value builtins.</p>
 */
public final class ArraySemantics {

    private ArraySemantics() {}

    /** The pinned failure of an array builtin, with the builtin's exact message. */
    public static final class EvaluationFailure extends RuntimeException {
        public EvaluationFailure(String message) {
            super(message);
        }
    }

    /** {@code LengthOfArray}: the number of elements. Total. */
    public static BigInteger lengthOfArray(ArrayConst array) {
        Objects.requireNonNull(array, "array");
        return BigInteger.valueOf(array.values().size());
    }

    /** {@code ListToArray}: the same elements in the same universe. Total. */
    public static ArrayConst listToArray(ListConst list) {
        Objects.requireNonNull(list, "list");
        return new ArrayConst(list.elemType(), list.values());
    }

    /** {@code IndexArray}: the element at a zero-based index, or the pinned failure. */
    public static Constant indexArray(ArrayConst array, BigInteger index) {
        Objects.requireNonNull(array, "array");
        Objects.requireNonNull(index, "index");
        int i;
        try {
            i = index.intValueExact();
        } catch (ArithmeticException e) {
            throw new EvaluationFailure("IndexArray: index out of range: " + index);
        }
        if (i < 0 || i >= array.values().size()) {
            throw new EvaluationFailure("IndexArray: index " + i
                    + " out of bounds for array of size " + array.values().size());
        }
        return array.values().get(i);
    }
}
