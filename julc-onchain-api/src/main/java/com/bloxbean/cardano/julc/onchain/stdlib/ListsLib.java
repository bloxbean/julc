package com.bloxbean.cardano.julc.onchain.stdlib;

import com.bloxbean.cardano.julc.core.PlutusData;

import java.math.BigInteger;

/**
 * Off-chain list HOF stubs.
 * <p>
 * Higher-order functions (any, all, find, foldl, map, filter, zip) remain here
 * because they are PIR-only (not in on-chain Java source). They require lambda support
 * which the compiler implements via PIR term generation in StdlibRegistry.
 * <p>
 * All other list operations (length, isEmpty, head, tail, reverse, concat, nth, take,
 * drop, contains, containsInt, containsBytes, hasDuplicateInts, hasDuplicateBytes,
 * empty, prepend) are in the on-chain {@code com.bloxbean.cardano.julc.stdlib.lib.ListsLib}.
 */
public final class ListsLib {

    private ListsLib() {}

    /** Return true if predicate holds for any element in the list. */
    public static boolean any(PlutusData.ListData list, PlutusData predicate) {
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

    /** Zip two lists into a list of pairs (ConstrData(0, [a, b])). */
    public static PlutusData.ListData zip(PlutusData.ListData a, PlutusData.ListData b) {
        throw new UnsupportedOperationException(
                "ListsLib.zip() is not supported off-chain.");
    }
}
