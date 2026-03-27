package com.bloxbean.cardano.julc.core.types;

/**
 * Immutable array interface for O(1) random access on-chain.
 * <p>
 * <b>PV11 only</b> — Arrays use PV11 Batch 6 builtins (CIP-156) and are available
 * from protocol version 11 onwards. They will not work on PV10 networks.
 * <p>
 * On-chain: the JuLC compiler resolves {@code JulcArray<T>} to {@code ArrayType(resolve(T))}.
 * Instance methods are dispatched via TypeMethodRegistry to native UPLC builtins.
 * <p>
 * Off-chain: backed by {@link JulcArrayImpl}, wrapping a {@code java.util.List<T>}
 * with O(1) index access.
 *
 * @param <T> the element type
 */
public interface JulcArray<T> {

    /** Get element at 0-based index (O(1) on-chain). */
    T get(long index);

    /** Return the number of elements. */
    long length();

    /** Create an array from a list. On-chain: compiles to ListToArray builtin. */
    static <T> JulcArray<T> fromList(JulcList<T> list) {
        var elements = new java.util.ArrayList<T>();
        for (T elem : list) elements.add(elem);
        return new JulcArrayImpl<>(elements);
    }
}
