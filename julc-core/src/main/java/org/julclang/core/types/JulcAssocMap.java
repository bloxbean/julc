package org.julclang.core.types;

import org.julclang.core.PlutusData;
import org.julclang.core.PlutusDataConversions;

import java.util.ArrayList;
import java.util.List;

/**
 * Off-chain implementation of {@link JulcMap} using an association list.
 * All operations are immutable — mutations return new instances.
 * <p>
 * Keys and values compare as on-chain, where a map compares by Data equality: a {@code byte[]} by
 * its content, anything else by its own {@code equals} (so a nested JuLC collection compares
 * deeply). This applies to {@link #get}, {@link #lookup}, {@link #getOrDefault},
 * {@link #containsKey}, {@link #delete}, {@link #equals} and {@link #hashCode}. {@link #insert}
 * prepends, so among entries with equal keys the most recently inserted wins, as on-chain. Arrays
 * are held, not copied: mutating a key or value after insertion changes the map's lookups,
 * equality and hash, as with any mutable key.
 *
 * @param <K> the key type
 * @param <V> the value type
 */
public final class JulcAssocMap<K, V> implements JulcMap<K, V> {

    private record Entry<K, V>(K key, V value) {}

    private final List<Entry<K, V>> entries;

    private JulcAssocMap(List<Entry<K, V>> entries) {
        this.entries = List.copyOf(entries);
    }

    /** Create an empty map. */
    public static <K, V> JulcAssocMap<K, V> empty() {
        return new JulcAssocMap<>(List.of());
    }

    /** Create a map with one entry. */
    public static <K, V> JulcAssocMap<K, V> of(K k1, V v1) {
        return new JulcAssocMap<>(List.of(new Entry<>(k1, v1)));
    }

    /** Create a map with two entries. */
    public static <K, V> JulcAssocMap<K, V> of(K k1, V v1, K k2, V v2) {
        return new JulcAssocMap<>(List.of(new Entry<>(k1, v1), new Entry<>(k2, v2)));
    }

    /** Create a map with three entries. */
    public static <K, V> JulcAssocMap<K, V> of(K k1, V v1, K k2, V v2, K k3, V v3) {
        return new JulcAssocMap<>(List.of(new Entry<>(k1, v1), new Entry<>(k2, v2), new Entry<>(k3, v3)));
    }

    @Override
    public V get(K key) {
        for (var entry : entries) {
            if (ContentEquality.equal(entry.key(), key)) {
                return entry.value();
            }
        }
        return null;
    }

    @Override
    public java.util.Optional<V> lookup(K key) {
        return java.util.Optional.ofNullable(get(key));
    }

    @Override
    public boolean containsKey(K key) {
        for (var entry : entries) {
            if (ContentEquality.equal(entry.key(), key)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public JulcMap<K, V> insert(K key, V value) {
        var result = new ArrayList<>(entries);
        result.addFirst(new Entry<>(key, value));
        return new JulcAssocMap<>(result);
    }

    @Override
    public JulcMap<K, V> delete(K key) {
        var result = new ArrayList<Entry<K, V>>();
        for (var entry : entries) {
            if (!ContentEquality.equal(entry.key(), key)) {
                result.add(entry);
            }
        }
        return new JulcAssocMap<>(result);
    }

    @Override
    public JulcList<K> keys() {
        var result = new ArrayList<K>();
        for (var entry : entries) {
            result.add(entry.key());
        }
        return new JulcArrayList<>(result);
    }

    @Override
    public JulcList<V> values() {
        var result = new ArrayList<V>();
        for (var entry : entries) {
            result.add(entry.value());
        }
        return new JulcArrayList<>(result);
    }

    @Override
    public PlutusData.MapData toPlutusData() {
        var result = new ArrayList<PlutusData.Pair>(entries.size());
        for (var entry : entries) {
            result.add(new PlutusData.Pair(
                    PlutusDataConversions.toPlutusData(entry.key()),
                    PlutusDataConversions.toPlutusData(entry.value())));
        }
        return new PlutusData.MapData(result);
    }

    @Override
    public long size() {
        return entries.size();
    }

    @Override
    public boolean isEmpty() {
        return entries.isEmpty();
    }

    @Override
    public Object head() {
        return entries.getFirst();
    }

    @Override
    public JulcMap<K, V> tail() {
        return new JulcAssocMap<>(entries.subList(1, entries.size()));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof JulcAssocMap<?, ?> that) || entries.size() != that.entries.size()) return false;
        for (int i = 0; i < entries.size(); i++) {
            var a = entries.get(i);
            var b = that.entries.get(i);
            if (!ContentEquality.equal(a.key(), b.key()) || !ContentEquality.equal(a.value(), b.value())) return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        int h = 1;
        for (var entry : entries) {
            h = 31 * h + (31 * ContentEquality.hash(entry.key()) + ContentEquality.hash(entry.value()));
        }
        return h;
    }

    @Override
    public String toString() {
        var sb = new StringBuilder("JulcMap{");
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(entries.get(i).key()).append("=").append(entries.get(i).value());
        }
        return sb.append("}").toString();
    }
}
