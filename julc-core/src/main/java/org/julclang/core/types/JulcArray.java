package org.julclang.core.types;

/**
 * Immutable array interface for O(1) random access on-chain.
 * <p>
 * <b>PV11 only</b> — Arrays use PV11 Batch 6 builtins (CIP-138) and are available
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

    /**
     * Create an array from the given elements (ADR-046).
     * <p>
     * On-chain this is {@code JulcList.of(elements).toArray()}: each element is Data-encoded
     * as the declared element type requires and the list is converted with
     * {@code ListToArray}. At the default {@code pv11-safe} level an array whose elements are
     * all literals becomes one UPLC array constant, and a {@code get} or {@code length} on it
     * with a literal index folds to its result. Declare the element type
     * ({@code JulcArray<BigInteger> t = JulcArray.of(...)}) so that {@code get} decodes it.
     */
    @SafeVarargs
    static <T> JulcArray<T> of(T... elements) {
        return new JulcArrayImpl<>(java.util.List.of(elements));
    }
}
