package com.bloxbean.cardano.julc.onchain.stdlib;

import com.bloxbean.cardano.julc.core.PlutusData;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * On-chain map (association list) operations.
 * <p>
 * These methods are executable both on-chain (compiled to UPLC via StdlibRegistry)
 * and off-chain (as plain Java for debugging and testing).
 * <p>
 * Maps are represented as {@link PlutusData.MapData} with {@link PlutusData.Pair} entries.
 */
public final class MapLib {

    private MapLib() {}

    /** Look up a key in the map, returning Optional. */
    public static Optional<PlutusData> lookup(PlutusData.MapData map, PlutusData key) {
        for (var pair : map.entries()) {
            if (pair.key().equals(key)) {
                return Optional.of(pair.value());
            }
        }
        return Optional.empty();
    }

    /** Check if a key exists in the map. */
    public static boolean member(PlutusData.MapData map, PlutusData key) {
        return lookup(map, key).isPresent();
    }

    /** Insert a key-value pair (prepends, shadows existing). */
    public static PlutusData.MapData insert(PlutusData.MapData map, PlutusData key, PlutusData value) {
        var entries = new ArrayList<PlutusData.Pair>();
        entries.add(new PlutusData.Pair(key, value));
        entries.addAll(map.entries());
        return new PlutusData.MapData(entries);
    }

    /** Delete a key from the map. */
    public static PlutusData.MapData delete(PlutusData.MapData map, PlutusData key) {
        var result = new ArrayList<PlutusData.Pair>();
        for (var pair : map.entries()) {
            if (!pair.key().equals(key)) {
                result.add(pair);
            }
        }
        return new PlutusData.MapData(result);
    }

    /** Extract all keys. */
    public static PlutusData.ListData keys(PlutusData.MapData map) {
        var result = new ArrayList<PlutusData>();
        for (var pair : map.entries()) {
            result.add(pair.key());
        }
        return new PlutusData.ListData(result);
    }

    /** Extract all values. */
    public static PlutusData.ListData values(PlutusData.MapData map) {
        var result = new ArrayList<PlutusData>();
        for (var pair : map.entries()) {
            result.add(pair.value());
        }
        return new PlutusData.ListData(result);
    }

    /** Convert map to pair list as ListData of ConstrData pairs. */
    public static PlutusData.ListData toList(PlutusData.MapData map) {
        var items = new ArrayList<PlutusData>();
        for (var pair : map.entries()) {
            items.add(new PlutusData.ConstrData(0, List.of(pair.key(), pair.value())));
        }
        return new PlutusData.ListData(items);
    }

    /** Construct a map from a pair list (ListData of ConstrData pairs). */
    public static PlutusData.MapData fromList(PlutusData.ListData list) {
        var entries = new ArrayList<PlutusData.Pair>();
        for (var item : list.items()) {
            if (item instanceof PlutusData.ConstrData c && c.fields().size() >= 2) {
                entries.add(new PlutusData.Pair(c.fields().get(0), c.fields().get(1)));
            }
        }
        return new PlutusData.MapData(entries);
    }

    /** Number of entries in the map. Aligned with on-chain return type (long). */
    public static long size(PlutusData.MapData map) {
        return map.entries().size();
    }
}
