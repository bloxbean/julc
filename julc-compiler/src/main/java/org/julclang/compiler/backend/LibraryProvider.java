package org.julclang.compiler.backend;

import org.julclang.compiler.pir.PirTerm;

import java.util.List;

/** Experimental neutral library surface: concrete exports with closed implementations. */
public interface LibraryProvider {
    List<LibraryExport> describe(String module);

    PirTerm materialize(String symbol);
}
