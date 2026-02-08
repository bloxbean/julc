package com.bloxbean.cardano.plutus.onchain.stdlib;

import com.bloxbean.cardano.plutus.core.PlutusData;

import java.math.BigInteger;

/**
 * On-chain list operations.
 * <p>
 * These are compile-time stubs for IDE support. The actual on-chain implementation
 * is provided by the PlutusCompiler via {@code StdlibRegistry}.
 */
public final class ListsLib {

    private ListsLib() {}

    /** Return true if predicate holds for any element in the list. */
    public static boolean any(PlutusData list, PlutusData predicate) {
        throw new UnsupportedOperationException("On-chain only");
    }

    /** Return true if predicate holds for all elements in the list. */
    public static boolean all(PlutusData list, PlutusData predicate) {
        throw new UnsupportedOperationException("On-chain only");
    }

    /** Return the first element matching the predicate, as Optional (Some/None). */
    public static PlutusData find(PlutusData list, PlutusData predicate) {
        throw new UnsupportedOperationException("On-chain only");
    }

    /** Left fold over a list with an accumulator function and initial value. */
    public static PlutusData foldl(PlutusData f, PlutusData init, PlutusData list) {
        throw new UnsupportedOperationException("On-chain only");
    }

    /** Return the number of elements in the list. */
    public static BigInteger length(PlutusData list) {
        throw new UnsupportedOperationException("On-chain only");
    }

    /** Return true if the list is empty. */
    public static boolean isEmpty(PlutusData list) {
        throw new UnsupportedOperationException("On-chain only");
    }

    /** Return true if the list contains the given element. */
    public static boolean contains(PlutusData list, PlutusData target) {
        throw new UnsupportedOperationException("On-chain only");
    }

    /** Return the first element of the list. */
    public static PlutusData head(PlutusData list) {
        throw new UnsupportedOperationException("On-chain only");
    }
}
