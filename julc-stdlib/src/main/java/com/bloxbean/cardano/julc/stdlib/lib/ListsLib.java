package com.bloxbean.cardano.julc.stdlib.lib;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.stdlib.annotation.OnchainLibrary;
import com.bloxbean.cardano.julc.stdlib.Builtins;

import java.math.BigInteger;

/**
 * List operations compiled from Java source to UPLC.
 * <p>
 * Uses {@link JulcList} for type-safe, readable list manipulation.
 * On-chain, JulcList methods are dispatched via TypeMethodRegistry to UPLC builtins.
 * <p>
 * HOF methods (any, all, find, foldl, map, filter, zip) remain as PIR
 * in StdlibRegistry because they require lambda.apply() support.
 */
@OnchainLibrary
public class ListsLib {

    /** Return an empty list. */
    public static JulcList<PlutusData> empty() {
        return JulcList.empty();
    }

    /** Prepend an element to the front of a list. */
    public static JulcList<PlutusData> prepend(JulcList<PlutusData> list, PlutusData element) {
        return list.prepend(element);
    }

    /** Return the number of elements in the list. */
    public static long length(JulcList<PlutusData> list) {
        long count = 0;
        for (PlutusData elem : list) {
            count = count + 1;
        }
        return count;
    }

    /** Return true if the list is empty. */
    public static boolean isEmpty(JulcList<PlutusData> list) {
        return list.isEmpty();
    }

    /** Return the first element of the list. */
    public static PlutusData head(JulcList<PlutusData> list) {
        return list.head();
    }

    /** Return all elements except the first. */
    public static JulcList<PlutusData> tail(JulcList<PlutusData> list) {
        return list.tail();
    }

    /** Reverse a list. */
    public static JulcList<PlutusData> reverse(JulcList<PlutusData> list) {
        JulcList<PlutusData> acc = JulcList.empty();
        for (PlutusData elem : list) {
            acc = acc.prepend(elem);
        }
        return acc;
    }

    /** Concatenate two lists. */
    public static JulcList<PlutusData> concat(JulcList<PlutusData> a, JulcList<PlutusData> b) {
        JulcList<PlutusData> result = b;
        JulcList<PlutusData> reversed = reverse(a);
        for (PlutusData elem : reversed) {
            result = result.prepend(elem);
        }
        return result;
    }

    /** Get element at index n (0-based). */
    public static PlutusData nth(JulcList<PlutusData> list, long n) {
        return list.get(n);
    }

    /** Take the first n elements from a list. */
    public static JulcList<PlutusData> take(JulcList<PlutusData> list, long n) {
        return list.take(n);
    }

    /** Drop the first n elements from a list. */
    public static JulcList<PlutusData> drop(JulcList<PlutusData> list, long n) {
        return list.drop(n);
    }

    /** Return true if list contains target (using EqualsData). */
    public static boolean contains(JulcList<PlutusData> list, PlutusData target) {
        boolean found = false;
        for (PlutusData elem : list) {
            if (Builtins.equalsData(elem, target)) {
                found = true;
            } else {
                found = found;
            }
        }
        return found;
    }

    /** Check if a list of integers contains the given value. Uses EqualsInteger. */
    public static boolean containsInt(JulcList<PlutusData> list, BigInteger target) {
        boolean found = false;
        for (PlutusData elem : list) {
            if (Builtins.unIData(elem).equals(target)) {
                found = true;
            } else {
                found = found;
            }
        }
        return found;
    }

    /** Check if a list of integers contains any duplicate. O(n^2). Delegates to {@link #hasDuplicates}. */
    public static boolean hasDuplicateInts(JulcList<PlutusData> list) {
        return hasDuplicates(list);
    }

    /** Check if a list of bytestrings contains any duplicate. O(n^2). Delegates to {@link #hasDuplicates}. */
    public static boolean hasDuplicateBytes(JulcList<PlutusData> list) {
        return hasDuplicates(list);
    }

    /** Check if a list contains any duplicate element. O(n^2).
     *  For each element, searches the tail using EqualsData. */
    @SuppressWarnings("unchecked")
    public static boolean hasDuplicates(JulcList<PlutusData> list) {
        boolean found = false;
        JulcList<PlutusData> outer = list;
        while (!outer.isEmpty() && !found) {
            PlutusData headElem = outer.head();
            JulcList<PlutusData> rest = outer.tail();
            if (contains(rest, headElem)) {
                found = true;
            }
            outer = rest;
        }
        return found;
    }

    /** Check if a list of bytestrings contains the given target. Uses EqualsByteString. */
    public static boolean containsBytes(JulcList<PlutusData> list, byte[] target) {
        boolean found = false;
        for (PlutusData elem : list) {
            if (Builtins.equalsByteString(Builtins.unBData(elem), target)) {
                found = true;
            } else {
                found = found;
            }
        }
        return found;
    }
}