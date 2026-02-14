package com.bloxbean.cardano.julc.onchain.stdlib;

import com.bloxbean.cardano.julc.core.PlutusData;

import java.math.BigInteger;
import java.util.List;

/**
 * On-chain list operations.
 * <p>
 * These methods are executable both on-chain (compiled to UPLC via StdlibRegistry)
 * and off-chain (as plain Java for debugging and testing).
 * <p>
 * Off-chain, list arguments are expected to be {@link PlutusData.ListData}.
 * Functions taking predicate arguments ({@code any}, {@code all}, {@code find}, {@code foldl})
 * have limited off-chain support — prefer typed access (e.g., {@code txInfo.signatories().contains()})
 * for debugging.
 */
public final class ListsLib {

    private ListsLib() {}

    private static List<PlutusData> toList(PlutusData.ListData list) {
        return list.items();
    }

    /** Return true if predicate holds for any element in the list. */
    public static boolean any(PlutusData.ListData list, PlutusData predicate) {
        // Off-chain: limited support. Prefer typed access for debugging.
        throw new UnsupportedOperationException(
                "ListsLib.any() with PlutusData predicate is not supported off-chain. "
                + "Use typed list access (e.g., list.stream().anyMatch(...)) for debugging.");
    }

    /** Return true if predicate holds for all elements in the list. */
    public static boolean all(PlutusData.ListData list, PlutusData predicate) {
        throw new UnsupportedOperationException(
                "ListsLib.all() with PlutusData predicate is not supported off-chain. "
                + "Use typed list access for debugging.");
    }

    /** Return the first element matching the predicate, as Optional (Some/None). */
    public static PlutusData find(PlutusData.ListData list, PlutusData predicate) {
        throw new UnsupportedOperationException(
                "ListsLib.find() with PlutusData predicate is not supported off-chain. "
                + "Use typed list access for debugging.");
    }

    /** Left fold over a list with an accumulator function and initial value. */
    public static PlutusData foldl(PlutusData f, PlutusData init, PlutusData.ListData list) {
        throw new UnsupportedOperationException(
                "ListsLib.foldl() with PlutusData function is not supported off-chain. "
                + "Use typed list access for debugging.");
    }

    /** Return an empty list. */
    public static PlutusData.ListData empty() {
        return new PlutusData.ListData(java.util.Collections.emptyList());
    }

    /** Prepend an element to the front of a list. */
    public static PlutusData.ListData prepend(PlutusData.ListData list, PlutusData element) {
        var items = new java.util.ArrayList<>(toList(list));
        items.addFirst(element);
        return new PlutusData.ListData(items);
    }

    /** Return the number of elements in the list. */
    public static BigInteger length(PlutusData.ListData list) {
        return BigInteger.valueOf(toList(list).size());
    }

    /** Return true if the list is empty. */
    public static boolean isEmpty(PlutusData.ListData list) {
        return toList(list).isEmpty();
    }

    /** Return true if the list contains the given element. */
    public static boolean contains(PlutusData.ListData list, PlutusData target) {
        for (PlutusData item : toList(list)) {
            if (item.equals(target)) return true;
        }
        return false;
    }

    /** Return the first element of the list. */
    public static PlutusData head(PlutusData.ListData list) {
        var items = toList(list);
        if (items.isEmpty()) {
            throw new IllegalArgumentException("head of empty list");
        }
        return items.getFirst();
    }

    /** Return the first element of a typed list. */
    @SuppressWarnings("unchecked")
    public static <T> T head(java.util.List<T> list) {
        return list.getFirst();
    }

    /** Return the tail (all elements except the first) of a typed list. */
    public static <T> java.util.List<T> tail(java.util.List<T> list) {
        return list.subList(1, list.size());
    }

    /** Reverse a list. */
    public static PlutusData.ListData reverse(PlutusData.ListData list) {
        var items = new java.util.ArrayList<>(toList(list));
        java.util.Collections.reverse(items);
        return new PlutusData.ListData(items);
    }

    /** Map a function over a list. Off-chain: limited support. */
    public static PlutusData.ListData map(PlutusData.ListData list, PlutusData f) {
        throw new UnsupportedOperationException(
                "ListsLib.map() with PlutusData function is not supported off-chain.");
    }

    /** Filter a list by predicate. Off-chain: limited support. */
    public static PlutusData.ListData filter(PlutusData.ListData list, PlutusData predicate) {
        throw new UnsupportedOperationException(
                "ListsLib.filter() with PlutusData predicate is not supported off-chain.");
    }

    /** Concatenate two lists. */
    public static PlutusData.ListData concat(PlutusData.ListData a, PlutusData.ListData b) {
        var result = new java.util.ArrayList<>(toList(a));
        result.addAll(toList(b));
        return new PlutusData.ListData(result);
    }

    /** Get element at index n (0-based). */
    public static PlutusData nth(PlutusData.ListData list, BigInteger n) {
        return toList(list).get(n.intValueExact());
    }

    /** Take first n elements from a list. */
    public static PlutusData.ListData take(PlutusData.ListData list, BigInteger n) {
        var items = toList(list);
        int count = Math.min(n.intValueExact(), items.size());
        return new PlutusData.ListData(new java.util.ArrayList<>(items.subList(0, count)));
    }

    /** Drop first n elements from a list. */
    public static PlutusData.ListData drop(PlutusData.ListData list, BigInteger n) {
        var items = toList(list);
        int count = Math.min(n.intValueExact(), items.size());
        return new PlutusData.ListData(new java.util.ArrayList<>(items.subList(count, items.size())));
    }

    /** Zip two lists into a list of pairs (ConstrData(0, [a, b])). */
    public static PlutusData.ListData zip(PlutusData.ListData a, PlutusData.ListData b) {
        var listA = toList(a);
        var listB = toList(b);
        int len = Math.min(listA.size(), listB.size());
        var result = new java.util.ArrayList<PlutusData>();
        for (int i = 0; i < len; i++) {
            result.add(new PlutusData.ConstrData(0, java.util.List.of(listA.get(i), listB.get(i))));
        }
        return new PlutusData.ListData(result);
    }

    /** Check if a list of integers contains the given value. */
    public static boolean containsInt(PlutusData.ListData list, BigInteger target) {
        for (PlutusData item : toList(list)) {
            if (((PlutusData.IntData) item).value().equals(target)) {
                return true;
            }
        }
        return false;
    }

    /** Check if a list of integers contains any duplicate. O(n^2). */
    public static boolean hasDuplicateInts(PlutusData.ListData list) {
        var items = toList(list);
        for (int i = 0; i < items.size(); i++) {
            var val = ((PlutusData.IntData) items.get(i)).value();
            for (int j = i + 1; j < items.size(); j++) {
                if (val.equals(((PlutusData.IntData) items.get(j)).value())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Check if a list of bytestrings contains any duplicate. O(n^2). */
    public static boolean hasDuplicateBytes(PlutusData.ListData list) {
        var items = toList(list);
        for (int i = 0; i < items.size(); i++) {
            var val = ((PlutusData.BytesData) items.get(i)).value();
            for (int j = i + 1; j < items.size(); j++) {
                if (java.util.Arrays.equals(val, ((PlutusData.BytesData) items.get(j)).value())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Check if a list of bytestrings contains the given target. Uses byte-level equality. */
    public static boolean containsBytes(PlutusData.ListData list, byte[] target) {
        for (PlutusData item : toList(list)) {
            if (java.util.Arrays.equals(((PlutusData.BytesData) item).value(), target)) {
                return true;
            }
        }
        return false;
    }
}
