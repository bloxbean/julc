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

    @Override
    public String toString() {
        return "SourceMap{size=" + positions.size() + "}";
    }
}
