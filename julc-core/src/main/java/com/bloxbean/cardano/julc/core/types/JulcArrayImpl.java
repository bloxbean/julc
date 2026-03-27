package com.bloxbean.cardano.julc.core.types;

import java.util.List;
import java.util.Objects;

/**
 * Off-chain implementation of {@link JulcArray} backed by a {@code java.util.List<T>}.
 * Immutable — the delegate list is copied on construction.
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
        if (o instanceof JulcArrayImpl<?> that) return delegate.equals(that.delegate);
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(delegate);
    }

    @Override
    public String toString() {
        return "JulcArray" + delegate;
    }
}
