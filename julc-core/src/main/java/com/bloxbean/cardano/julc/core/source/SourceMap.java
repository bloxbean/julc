package com.bloxbean.cardano.julc.core.source;

import com.bloxbean.cardano.julc.core.Term;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Maps UPLC {@link Term} objects to their originating Java source locations.
 * <p>
 * Uses an {@link IdentityHashMap} so that lookups are by object identity ({@code ==}),
 * not structural equality. This works because the CekMachine holds references to the
 * exact Term objects from the compiled program.
 * <p>
 * Source maps are a debugging feature. When enabled, the UPLC optimizer is skipped
 * so that Term identity is preserved from compilation through evaluation.
 */
public final class SourceMap {

    /** An empty source map that always returns null. */
    public static final SourceMap EMPTY = new SourceMap(new IdentityHashMap<>());

    private final IdentityHashMap<Term, SourceLocation> positions;

    private SourceMap(IdentityHashMap<Term, SourceLocation> positions) {
        this.positions = positions;
    }

    /**
     * Create a source map from a pre-built identity map.
     * The map is defensively copied.
     */
    public static SourceMap of(Map<Term, SourceLocation> positions) {
        var copy = new IdentityHashMap<Term, SourceLocation>(positions.size());
        copy.putAll(positions);
        return new SourceMap(copy);
    }

    /**
     * Look up the source location for a specific Term (by identity).
     *
     * @param term the UPLC term to look up
     * @return the source location, or null if not mapped
     */
    public SourceLocation lookup(Term term) {
        if (term == null) return null;
        return positions.get(term);
    }

    /** The number of mapped terms. */
    public int size() {
        return positions.size();
    }

    /** Whether this source map has any entries. */
    public boolean isEmpty() {
        return positions.isEmpty();
    }

    /**
     * Convert this source map to a position-based index using DFS traversal order.
     * The resulting map can be serialized to JSON for persistence.
     *
     * @param rootTerm the root of the UPLC term tree that was compiled with this source map
     * @return sparse index → SourceLocation map
     */
    public Map<Integer, SourceLocation> toIndexed(Term rootTerm) {
        return SourceMapSerializer.toIndexed(positions, rootTerm);
    }

    /**
     * Reconstruct a SourceMap from a position-based index and a (deserialized) term tree.
     * The DFS traversal order must match the one used during {@link #toIndexed(Term)}.
     *
     * @param indexed  the sparse index → SourceLocation map
     * @param rootTerm the root of the deserialized UPLC term tree
     * @return a new SourceMap with identity-based lookups for the given term tree
     */
    public static SourceMap reconstruct(Map<Integer, SourceLocation> indexed, Term rootTerm) {
        var identityMap = SourceMapSerializer.fromIndexed(indexed, rootTerm);
        return new SourceMap(identityMap);
    }

    /**
     * Package-private access to the underlying positions map.
     * Used by {@link SourceMapSerializer} for serialization.
     */
    IdentityHashMap<Term, SourceLocation> positions() {
        return positions;
    }

    @Override
    public String toString() {
        return "SourceMap{size=" + positions.size() + "}";
    }
}
