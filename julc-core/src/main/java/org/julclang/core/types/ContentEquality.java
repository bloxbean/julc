package org.julclang.core.types;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The element, key and value equality of JuLC's off-chain collections (ADR-068 D6). On-chain every
 * JuLC collection compares by Data equality, so a {@code byte[]} compares by content; here it does
 * too ({@link Arrays#equals(byte[], byte[])} and {@link Arrays#hashCode(byte[])}). Every other value
 * compares by its own {@code equals}, so a nested JuLC collection compares by its own, equally
 * deep, equality. Arrays are not copied: mutating one after insertion changes what it is equal to.
 */
final class ContentEquality {

    private ContentEquality() {}

    static boolean equal(Object a, Object b) {
        if (a instanceof byte[] x && b instanceof byte[] y) return Arrays.equals(x, y);
        return Objects.equals(a, b);
    }

    static int hash(Object value) {
        return value instanceof byte[] bytes ? Arrays.hashCode(bytes) : Objects.hashCode(value);
    }

    /** Element-wise {@link #equal}, in order. */
    static boolean equalLists(List<?> a, List<?> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!equal(a.get(i), b.get(i))) return false;
        }
        return true;
    }

    /** {@link List#hashCode()}'s formula over {@link #hash}. */
    static int hashList(List<?> list) {
        int h = 1;
        for (Object element : list) h = 31 * h + hash(element);
        return h;
    }
}
