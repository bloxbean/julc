package org.julclang.compiler.backend;

import org.julclang.compiler.CompilerTarget;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;

import java.util.Map;
import java.util.Objects;

/** Experimental API revision 1: a closed, specialized, unwrapped function. */
public record FrontendProgram(
        PirTerm term,
        PirType type,
        CompilerTarget target,
        Entrypoint entrypoint,
        Map<String, PirType> namedTypes) {
    public FrontendProgram {
        Objects.requireNonNull(term);
        Objects.requireNonNull(type);
        Objects.requireNonNull(target);
        Objects.requireNonNull(entrypoint);
        namedTypes = Map.copyOf(namedTypes);
    }

    public enum Purpose {
        FUNCTION,
        SPEND,
        MINT
    }

    public record Entrypoint(String symbol, Purpose purpose, String boundary) {
        public Entrypoint {
            Objects.requireNonNull(symbol);
            Objects.requireNonNull(purpose);
            Objects.requireNonNull(boundary);
        }
    }
}
