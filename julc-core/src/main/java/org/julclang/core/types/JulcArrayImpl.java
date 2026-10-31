package org.julclang.core.types;

import java.util.List;

/**
 * Off-chain implementation of {@link JulcArray} backed by a {@code java.util.List<T>}.
 * Immutable — the delegate list is copied on construction.
 * <p>
 * Elements compare as on-chain, where an array's elements compare by Data equality: a
 * {@code byte[]} element by its content, any other element by its own {@code equals} (so a nested
 * JuLC collection compares deeply). This applies to {@link #equals} and {@link #hashCode}. The
 * elements themselves are not copied: mutating a {@code byte[]} after construction changes the
 * array's equality and hash.
 *
 * @param <T> the element type
 */
public final class JulcArrayImpl<T> implements JulcArray<T> {

    private final List<T> delegate;

    public JulcArrayImpl(List<T> elements) {
        this.delegate = List.copyOf(elements);
    }

    @Override
    public T get(long index) {
        return delegate.get((int) index);
    }

    @Override
    public long length() {
        return delegate.size();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o instanceof JulcArrayImpl<?> that) return ContentEquality.equalLists(delegate, that.delegate);
        return false;
    }

    @Override
    public int hashCode() {
        return ContentEquality.hashList(delegate);
    }

    @Override
    public String toString() {
        return "JulcArray" + delegate;
    }
}
