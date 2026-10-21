package org.julclang.stdlib.lib;

import org.julclang.core.types.JulcArray;
import org.julclang.core.types.JulcList;
import org.julclang.stdlib.annotation.OnchainLibrary;

/**
 * Typed array operations for language frontends (ADR-059 generic exports).
 * <p>
 * Every method is a generic template over {@link JulcArray}{@code <T>}: a frontend
 * instantiates it at a concrete element type, and the Java compiler lowers it through the
 * same {@code JulcArray} lowering Java validators use. Java validators call the
 * {@code JulcArray} methods directly.
 * <p>
 * Arrays are PV11 (CIP-138) values: {@link #fromList} is O(n), {@link #get} and
 * {@link #length} are O(1). Elements are Data-encoded; {@code get} decodes one. An array has
 * no Data encoding of its own, so it cannot be a datum, a redeemer or a record field.
 */
@OnchainLibrary
public class TypedArrayLib {

    /** An array of the list's elements, in order. */
    public static <T> JulcArray<T> fromList(JulcList<T> list) {
        return JulcArray.fromList(list);
    }

    /** The element at a 0-based index; evaluation fails outside {@code [0, length)}. */
    public static <T> T get(JulcArray<T> array, long index) {
        return array.get(index);
    }

    /** The number of elements. */
    public static <T> long length(JulcArray<T> array) {
        return array.length();
    }
}
