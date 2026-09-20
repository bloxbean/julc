package org.julclang.compiler;

import org.julclang.compiler.backend.LibraryType;
import org.julclang.compiler.pir.PirType;
import java.util.List;
import java.util.Map;

/** Compatibility facade over the same source descriptions used for all Java libraries. */
public final class LedgerTypeProvider {
    private final JavaLibraryProvider provider =
            new JavaLibraryProvider(List.of(), null, new CompilerOptions());

    public Map<String, LibraryType> types() { return provider.types(); }
    public Map<String, PirType> namedDefinitions() { return provider.namedDefinitions(); }
}
