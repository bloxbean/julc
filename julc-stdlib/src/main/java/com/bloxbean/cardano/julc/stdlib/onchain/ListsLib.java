package com.bloxbean.cardano.julc.stdlib.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.onchain.annotation.OnchainLibrary;
import com.bloxbean.cardano.julc.onchain.stdlib.Builtins;

/**
 * List operations compiled from Java source to UPLC.
 * <p>
 * HOF methods (any, all, find, foldl, map, filter, zip) remain as PIR
 * in StdlibRegistry because they require lambda.apply() support.
 */
@OnchainLibrary
public class ListsLib {

    /** Return the number of elements in the list. */
    public static long length(PlutusData.ListData list) {
        var count = 0L;
        var current = list;
        while (!Builtins.nullList(current)) {
            count = count + 1;
            current = Builtins.tailList(current);
        }
        return count;
    }

    /** Return true if the list is empty. */
    public static boolean isEmpty(PlutusData.ListData list) {
        return Builtins.nullList(list);
    }

    /** Return the first element of the list. */
    public static PlutusData head(PlutusData.ListData list) {
        return Builtins.headList(list);
    }

    /** Return all elements except the first. */
    public static PlutusData.ListData tail(PlutusData.ListData list) {
        return Builtins.tailList(list);
    }

    /** Reverse a list. */
    public static PlutusData.ListData reverse(PlutusData.ListData list) {
        var acc = Builtins.mkNilData();
        var current = list;
        while (!Builtins.nullList(current)) {
            acc = Builtins.mkCons(Builtins.headList(current), acc);
            current = Builtins.tailList(current);
        }
        return acc;
    }

    /** Concatenate two lists. */
    public static PlutusData.ListData concat(PlutusData.ListData a, PlutusData.ListData b) {
        PlutusData.ListData result = b;
        var reversed = reverse(a);
        var current = reversed;
        while (!Builtins.nullList(current)) {
            result = Builtins.mkCons(Builtins.headList(current), result);
            current = Builtins.tailList(current);
        }
        return result;
    }

    /** Get element at index n (0-based). */
    public static PlutusData nth(PlutusData.ListData list, long n) {
        var current = list;
        var idx = n;
        while (idx > 0) {
            current = Builtins.tailList(current);
            idx = idx - 1;
        }
        return Builtins.headList(current);
    }

    /** Take the first n elements from a list. */
    public static PlutusData.ListData take(PlutusData.ListData list, long n) {
        var acc = Builtins.mkNilData();
        var current = list;
        var cnt = n;
        while (cnt > 0 && !Builtins.nullList(current)) {
            acc = Builtins.mkCons(Builtins.headList(current), acc);
            current = Builtins.tailList(current);
            cnt = cnt - 1;
        }
        return reverse(acc);
    }

    /** Drop the first n elements from a list. */
    public static PlutusData.ListData drop(PlutusData.ListData list, long n) {
        var current = list;
        var cnt = n;
        while (cnt > 0 && !Builtins.nullList(current)) {
            current = Builtins.tailList(current);
            cnt = cnt - 1;
        }
        return current;
    }

    /** Return true if list contains target (using EqualsData). */
    public static boolean contains(PlutusData.ListData list, PlutusData target) {
        var found = false;
        var current = list;
        while (!Builtins.nullList(current)) {
            if (Builtins.equalsData(Builtins.headList(current), target)) {
                found = true;
                current = Builtins.mkNilData();
            } else {
                current = Builtins.tailList(current);
            }
        }
        return found;
    }
}
