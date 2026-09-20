package org.julclang.compiler.backend;

import org.julclang.compiler.pir.PirType;
import java.util.List;

/** A concrete export. Generic schemes are deliberately not erased to Data. */
public record LibraryExport(String symbol, int arity, PirType type,
                            List<LibraryType.Reference> parameters,
                            LibraryType.Reference result) {
    public LibraryExport {
        parameters = List.copyOf(parameters);
        if (result != null && parameters.size() != arity)
            throw new IllegalArgumentException("Source signature arity mismatch: " + symbol);
    }

    public LibraryExport(String symbol, int arity, PirType type) {
        this(symbol, arity, type, List.of(), null);
    }
}
