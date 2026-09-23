package org.julclang.tools.debug;

import org.julclang.core.Program;
import org.julclang.core.Term;
import org.julclang.core.debug.DebugMetadata;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Validated runtime binding of a debug sidecar to one decoded term tree. */
final class DebugRuntimeIndex {
    record Lookup(DebugMetadata.SuspensionPoint point, String unavailableReason) {}

    final DebugMetadata metadata;
    final Map<String, DebugMetadata.Binding> bindings;
    final Map<String, DebugMetadata.TypeInfo> types;
    final Map<String, DebugMetadata.Layout> layouts;
    final Map<String, DebugMetadata.Scope> scopes;
    private final IdentityHashMap<Term, List<DebugMetadata.SuspensionPoint>> points = new IdentityHashMap<>();

    DebugRuntimeIndex(DebugMetadata metadata, Program decodedProgram) {
        metadata.validateArtifact(decodedProgram);
        this.metadata = metadata;
        bindings = index(metadata.bindings(), DebugMetadata.Binding::id);
        types = index(metadata.types(), DebugMetadata.TypeInfo::id);
        layouts = index(metadata.layouts(), DebugMetadata.Layout::id);
        scopes = index(metadata.scopes(), DebugMetadata.Scope::id);
        var occurrences = DebugMetadata.occurrences(decodedProgram.term());
        var byOccurrence = new LinkedHashMap<Integer, DebugMetadata.SuspensionPoint>();
        metadata.suspensionPoints().forEach(point -> byOccurrence.put(point.occurrenceId(), point));
        for (int i = 0; i < occurrences.size(); i++) {
            var point = byOccurrence.get(i);
            if (point != null) points.computeIfAbsent(occurrences.get(i), ignored -> new ArrayList<>()).add(point);
        }
    }

    Lookup lookup(Term term) {
        if (term == null) return new Lookup(null, "no current compute term");
        var candidates = points.get(term);
        if (candidates == null || candidates.isEmpty()) return new Lookup(null, "current term is not in the sidecar");
        if (candidates.size() != 1) return new Lookup(null, "shared term identity has ambiguous suspension metadata");
        var point = candidates.getFirst();
        if (point.context() != DebugMetadata.SourceContext.EXACT) {
            return new Lookup(null, point.unavailableReason() == null
                    ? "source context is not exact" : point.unavailableReason());
        }
        return new Lookup(point, null);
    }

    private static <T> Map<String, T> index(List<T> values, java.util.function.Function<T, String> id) {
        var result = new LinkedHashMap<String, T>();
        values.forEach(value -> result.put(id.apply(value), value));
        return Map.copyOf(result);
    }
}
