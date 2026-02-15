package com.bloxbean.cardano.julc.core.types;

/**
 * Immutable association map interface for on-chain and off-chain use.
 * <p>
 * On-chain: the JuLC compiler resolves {@code JulcMap<K,V>} to {@code MapType(resolve(K), resolve(V))},
 * identical to {@code java.util.Map<K,V>}. All methods are dispatched via TypeMethodRegistry.
 * <p>
 * Off-chain: backed by {@link JulcAssocMap}, which stores key-value pairs as an association list
 * and provides immutable operations (each mutation returns a new map).
 *
 * @param <K> the key type
 * @param <V> the value type
 */
public interface JulcMap<K, V> {

    // --- Factory methods (off-chain only — on-chain use compiler intrinsics) ---

    /** Create an empty map. */
    static <K, V> JulcMap<K, V> empty() {
        return JulcAssocMap.empty();
    }

    /** Create a map with one entry. */
    static <K, V> JulcMap<K, V> of(K k1, V v1) {
        return JulcAssocMap.of(k1, v1);
    }

    /** Create a map with two entries. */
    static <K, V> JulcMap<K, V> of(K k1, V v1, K k2, V v2) {
        return JulcAssocMap.of(k1, v1, k2, v2);
    }

    /** Create a map with three entries. */
    static <K, V> JulcMap<K, V> of(K k1, V v1, K k2, V v2, K k3, V v3) {
        return JulcAssocMap.of(k1, v1, k2, v2, k3, v3);
    }

    // --- Lookup ---

    /** Look up a value by key. Returns null if not found. */
    V get(K key);

    /** Look up a value by key, returning defaultValue if not found. */
    default V getOrDefault(K key, V defaultValue) {
        V v = get(key);
        return v != null ? v : defaultValue;
    }

    /** Check if the map contains the given key. */
    boolean containsKey(K key);

    // --- Modification (returns new map) ---

    /** Insert a key-value pair. If the key already exists, prepends a new entry. */
    JulcMap<K, V> insert(K key, V value);

    /** Delete all entries with the given key. */
    JulcMap<K, V> delete(K key);

    // --- Extraction ---

    /** Return all keys as a list. */
    JulcList<K> keys();

    /** Return all values as a list. */
    JulcList<V> values();

    // --- Query ---

    /** Return the number of entries. */
    long size();

    /** Check if the map is empty. */
    boolean isEmpty();
}
