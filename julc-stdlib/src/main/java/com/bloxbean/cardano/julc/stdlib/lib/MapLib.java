package com.bloxbean.cardano.julc.stdlib.lib;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.onchain.annotation.OnchainLibrary;
import com.bloxbean.cardano.julc.onchain.stdlib.Builtins;

/**
 * Map (association list) operations compiled from Java source to UPLC.
 * <p>
 * In Plutus, maps are {@code List<Pair<Data, Data>>} (association lists).
 * These are NOT hash maps — lookups are O(n).
 */
@OnchainLibrary
public class MapLib {

    /** Look up a key. Returns Constr(0, [value]) (Some) or Constr(1, []) (None). */
    public static PlutusData.ConstrData lookup(PlutusData.MapData map, PlutusData key) {
        var pairs = Builtins.unMapData(map);
        var result = Builtins.constrData(1, Builtins.mkNilData());
        PlutusData current = pairs;
        while (!Builtins.nullList(current)) {
            var pair = Builtins.headList(current);
            if (Builtins.equalsData(Builtins.fstPair(pair), key)) {
                var fields = Builtins.mkCons(Builtins.sndPair(pair), Builtins.mkNilData());
                result = Builtins.constrData(0, fields);
                current = Builtins.mkNilPairData();
            } else {
                current = Builtins.tailList(current);
            }
        }
        return result;
    }

    /** Check if key exists in map. */
    public static boolean member(PlutusData.MapData map, PlutusData key) {
        var found = false;
        var pairs = Builtins.unMapData(map);
        PlutusData current = pairs;
        while (!Builtins.nullList(current)) {
            var pair = Builtins.headList(current);
            if (Builtins.equalsData(Builtins.fstPair(pair), key)) {
                found = true;
                current = Builtins.mkNilPairData();
            } else {
                current = Builtins.tailList(current);
            }
        }
        return found;
    }

    /** Insert key-value pair (prepends; shadows existing). */
    public static PlutusData.MapData insert(PlutusData.MapData map, PlutusData key, PlutusData value) {
        var pair = Builtins.mkPairData(key, value);
        var pairs = Builtins.unMapData(map);
        var newPairs = Builtins.mkCons(pair, pairs);
        return Builtins.mapData(newPairs);
    }

    /** Delete a key from the map. */
    public static PlutusData.MapData delete(PlutusData.MapData map, PlutusData key) {
        var pairs = Builtins.unMapData(map);
        PlutusData acc = Builtins.mkNilPairData();
        PlutusData current = pairs;
        while (!Builtins.nullList(current)) {
            var pair = Builtins.headList(current);
            if (Builtins.equalsData(Builtins.fstPair(pair), key)) {
                acc = acc;
            } else {
                acc = Builtins.mkCons(pair, acc);
            }
            current = Builtins.tailList(current);
        }
        // Reverse to maintain original order
        var result = Builtins.mkNilPairData();
        var rev = acc;
        while (!Builtins.nullList(rev)) {
            result = Builtins.mkCons(Builtins.headList(rev), result);
            rev = Builtins.tailList(rev);
        }
        return Builtins.mapData(result);
    }

    /** Extract all keys from a map as a list. */
    public static PlutusData.ListData keys(PlutusData.MapData map) {
        var pairs = Builtins.unMapData(map);
        PlutusData.ListData acc = Builtins.mkNilData();
        PlutusData current = pairs;
        while (!Builtins.nullList(current)) {
            var pair = Builtins.headList(current);
            acc = Builtins.mkCons(Builtins.fstPair(pair), acc);
            current = Builtins.tailList(current);
        }
        return ListsLib.reverse(acc);
    }

    /** Extract all values from a map as a list. */
    public static PlutusData.ListData values(PlutusData.MapData map) {
        var pairs = Builtins.unMapData(map);
        PlutusData.ListData acc = Builtins.mkNilData();
        PlutusData current = pairs;
        while (!Builtins.nullList(current)) {
            var pair = Builtins.headList(current);
            acc = Builtins.mkCons(Builtins.sndPair(pair), acc);
            current = Builtins.tailList(current);
        }
        return ListsLib.reverse(acc);
    }

    /** Convert map to pair list (UnMapData). */
    public static PlutusData.MapData toList(PlutusData.MapData map) {
        return Builtins.unMapData(map);
    }

    /** Construct map from pair list (MapData). */
    public static PlutusData.MapData fromList(PlutusData list) {
        return Builtins.mapData(list);
    }

    /** Count entries in the map. */
    public static long size(PlutusData.MapData map) {
        var pairs = Builtins.unMapData(map);
        var count = 0L;
        PlutusData current = pairs;
        while (!Builtins.nullList(current)) {
            count = count + 1;
            current = Builtins.tailList(current);
        }
        return count;
    }
}
