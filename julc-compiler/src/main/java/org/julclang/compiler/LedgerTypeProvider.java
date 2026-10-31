package org.julclang.compiler;

import org.julclang.compiler.backend.LibraryType;
import org.julclang.compiler.pir.PirType;
import java.util.List;
import java.util.Map;

/** Compatibility facade over the same source descriptions used for all Java libraries. */
public final class LedgerTypeProvider {
    private final JavaLibraryProvider provider;

    public LedgerTypeProvider() {
        this(new CompilerOptions());
    }

    /** The ledger's types as the frontend {@code options} selects reads them (ADR-068 D8). */
    public LedgerTypeProvider(CompilerOptions options) {
        provider = new JavaLibraryProvider(List.of(), null, options);
    }

    public Map<String, LibraryType> types() { return provider.types(); }
    public Map<String, PirType> namedDefinitions() { return provider.namedDefinitions(); }
}
