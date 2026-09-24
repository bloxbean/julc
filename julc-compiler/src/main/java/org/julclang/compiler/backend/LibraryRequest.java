package org.julclang.compiler.backend;

import java.util.Objects;

/** A request for a provider-owned, closed implementation (ADR-059). */
public sealed interface LibraryRequest permits LibraryRequest.Export {

    /** A stable description used in diagnostics. */
    String describe();

    /** A concrete public export identified by its qualified symbol. */
    record Export(String symbol) implements LibraryRequest {
        public Export {
            Objects.requireNonNull(symbol, "symbol");
            if (symbol.isBlank()) throw new IllegalArgumentException("Export symbol must not be blank");
        }

        @Override
        public String describe() {
            return symbol;
        }
    }
}
