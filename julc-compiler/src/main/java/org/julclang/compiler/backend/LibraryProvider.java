package org.julclang.compiler.backend;

import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;

import java.util.List;
import java.util.Map;

/** Experimental neutral library surface: concrete exports with closed implementations. */
public interface LibraryProvider {
    List<LibraryExport> describe(String module);

    PirTerm materialize(String symbol);

    default Map<String, LibraryType> types() { return Map.of(); }

    default Map<String, PirType> namedDefinitions() {
        return Map.of();
    }

    /** Public exports that cannot currently be materialized, with actionable reasons. */
    default Map<String, String> unsupportedExports() { return Map.of(); }
}
