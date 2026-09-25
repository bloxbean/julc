package org.julclang.stdlib.lib;

import org.julclang.core.types.JulcList;
import org.julclang.core.types.JulcMap;
import org.julclang.stdlib.annotation.OnchainLibrary;

import java.util.Optional;

/**
 * Typed association-map operations for language frontends (ADR-059 generic exports).
 * <p>
 * Every method is a generic template over {@link JulcMap}{@code <K,V>}: a frontend
 * instantiates it at concrete key and value types, and the Java compiler lowers it through
 * the same {@code JulcMap} method lowering Java validators use, so the two never diverge.
 * Java validators call the {@code JulcMap} methods directly.
 * <p>
 * Maps are association lists ({@code List<Pair<Data,Data>>}); lookups are O(n) and keys
 * compare by their Data encoding. {@link #insert} replaces an existing key, keeping one
 * entry per key.
 */
@OnchainLibrary
public class TypedMapLib {

    /** The empty map. */
    public static <K, V> JulcMap<K, V> empty() {
        return JulcMap.empty();
    }

    /** The value for {@code key}, if present. */
    public static <K, V> Optional<V> lookup(JulcMap<K, V> map, K key) {
        return map.lookup(key);
    }

    /** Whether {@code key} is present. */
    public static <K, V> boolean member(JulcMap<K, V> map, K key) {
        return map.containsKey(key);
    }

    /** The map with {@code key} bound to {@code value}, replacing any existing binding. */
    public static <K, V> JulcMap<K, V> insert(JulcMap<K, V> map, K key, V value) {
        return map.delete(key).insert(key, value);
    }

    /** The map without {@code key}. */
    public static <K, V> JulcMap<K, V> remove(JulcMap<K, V> map, K key) {
        return map.delete(key);
    }

    /** The keys, in map order. */
    public static <K, V> JulcList<K> keys(JulcMap<K, V> map) {
        return map.keys();
    }

    /** The values, in map order. */
    public static <K, V> JulcList<V> values(JulcMap<K, V> map) {
        return map.values();
    }

    /** The number of entries. */
    public static <K, V> long size(JulcMap<K, V> map) {
        return map.size();
    }

    /** Whether the map has no entries. */
    public static <K, V> boolean isEmpty(JulcMap<K, V> map) {
        return map.isEmpty();
    }
}
