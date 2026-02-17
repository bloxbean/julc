package com.bloxbean.cardano.julc.core.types;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/**
 * Off-chain implementation of {@link JulcList} backed by a {@code java.util.List<T>}.
 * All operations are immutable — mutations return new instances.
 *
 * @param <T> the element type
 */
public final class JulcArrayList<T> implements JulcList<T> {

    private final List<T> delegate;

    public JulcArrayList(List<T> elements) {
        this.delegate = List.copyOf(elements);
    }

    @Override
    public T head() {
        if (delegate.isEmpty()) throw new RuntimeException("head on empty list");
        return delegate.getFirst();
    }

    @Override
    public T get(long index) {
        return delegate.get((int) index);
    }

    @Override
    public JulcList<T> tail() {
        if (delegate.isEmpty()) throw new RuntimeException("tail on empty list");
        return new JulcArrayList<>(delegate.subList(1, delegate.size()));
    }

    @Override
    public JulcList<T> take(long n) {
        int count = (int) Math.min(n, delegate.size());
        return new JulcArrayList<>(delegate.subList(0, count));
    }

    @Override
    public JulcList<T> drop(long n) {
        int skip = (int) Math.min(n, delegate.size());
        return new JulcArrayList<>(delegate.subList(skip, delegate.size()));
    }

    @Override
    public JulcList<T> prepend(T element) {
        var result = new ArrayList<T>(delegate.size() + 1);
        result.add(element);
        result.addAll(delegate);
        return new JulcArrayList<>(result);
    }

    @Override
    public JulcList<T> concat(JulcList<T> other) {
        var result = new ArrayList<T>(delegate);
        for (T elem : other) {
            result.add(elem);
        }
        return new JulcArrayList<>(result);
    }

    @Override
    public JulcList<T> reverse() {
        var result = new ArrayList<T>(delegate.size());
        for (int i = delegate.size() - 1; i >= 0; i--) {
            result.add(delegate.get(i));
        }
        return new JulcArrayList<>(result);
    }

    @Override
    public long size() {
        return delegate.size();
    }

    @Override
    public boolean isEmpty() {
        return delegate.isEmpty();
    }

    @Override
    public boolean contains(T element) {
        return delegate.contains(element);
    }

    @Override
    public <R> JulcList<R> map(java.util.function.Function<T, R> fn) {
        var result = new ArrayList<R>(delegate.size());
        for (T elem : delegate) {
            result.add(fn.apply(elem));
        }
        return new JulcArrayList<>(result);
    }

    @Override
    public JulcList<T> filter(java.util.function.Predicate<T> pred) {
        var result = new ArrayList<T>();
        for (T elem : delegate) {
            if (pred.test(elem)) result.add(elem);
        }
        return new JulcArrayList<>(result);
    }

    @Override
    public boolean any(java.util.function.Predicate<T> pred) {
        for (T elem : delegate) {
            if (pred.test(elem)) return true;
        }
        return false;
    }

    @Override
    public boolean all(java.util.function.Predicate<T> pred) {
        for (T elem : delegate) {
            if (!pred.test(elem)) return false;
        }
        return true;
    }

    @Override
    public T find(java.util.function.Predicate<T> pred) {
        for (T elem : delegate) {
            if (pred.test(elem)) return elem;
        }
        return null;
    }

    @Override
    public Iterator<T> iterator() {
        return delegate.iterator();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o instanceof JulcArrayList<?> that) return delegate.equals(that.delegate);
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(delegate);
    }

    @Override
    public String toString() {
        return "JulcList" + delegate;
    }
}
