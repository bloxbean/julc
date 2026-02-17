package com.bloxbean.cardano.julc.core.types;

/**
 * Immutable list interface for on-chain and off-chain use.
 * <p>
 * On-chain: the JuLC compiler resolves {@code JulcList<T>} to {@code ListType(resolve(T))},
 * identical to {@code java.util.List<T>}. All methods are dispatched via TypeMethodRegistry.
 * <p>
 * Off-chain: backed by {@link JulcArrayList}, which wraps a {@code java.util.List<T>}
 * and provides immutable operations (each mutation returns a new list).
 * <p>
 * Users can migrate from {@code List<T>} to {@code JulcList<T>} at their own pace —
 * the generated UPLC is identical.
 *
 * @param <T> the element type
 */
public interface JulcList<T> extends Iterable<T> {

    // --- Element access ---

    /** Return the first element. */
    T head();

    /** Return the element at the given index (0-based, O(n) traversal). */
    T get(long index);

    // --- Sublisting ---

    /** Return all elements except the first. */
    JulcList<T> tail();

    /** Return the first n elements. */
    JulcList<T> take(long n);

    /** Return all elements after the first n. */
    JulcList<T> drop(long n);

    // --- Construction (returns new list) ---

    /** Prepend an element to the front of this list. */
    JulcList<T> prepend(T element);

    /** Concatenate this list with another. */
    JulcList<T> concat(JulcList<T> other);

    /** Return a reversed copy of this list. */
    JulcList<T> reverse();

    // --- Query ---

    /** Return the number of elements. */
    long size();

    /** Check if the list is empty. */
    boolean isEmpty();

    /** Check if the list contains the given element. */
    boolean contains(T element);

    // --- Higher-order functions ---

    /** Apply a function to each element, returning a new list. */
    <R> JulcList<R> map(java.util.function.Function<T, R> fn);

    /** Keep only elements satisfying the predicate. */
    JulcList<T> filter(java.util.function.Predicate<T> pred);

    /** Return true if any element satisfies the predicate. */
    boolean any(java.util.function.Predicate<T> pred);

    /** Return true if all elements satisfy the predicate. */
    boolean all(java.util.function.Predicate<T> pred);

    /** Return the first element satisfying the predicate, or null if none. */
    T find(java.util.function.Predicate<T> pred);

    // --- Factory methods (off-chain only — on-chain use compiler intrinsics) ---

    /** Create an empty list. */
    static <T> JulcList<T> empty() {
        return new JulcArrayList<>(java.util.List.of());
    }

    /** Create a list from the given elements. */
    @SafeVarargs
    static <T> JulcList<T> of(T... elements) {
        return new JulcArrayList<>(java.util.List.of(elements));
    }
}
