package org.julclang.compiler.backend;

import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;

import java.util.Objects;

/**
 * A producer-authored program-level definition with its declared type (ADR-059). The type is
 * declared so that forward references and recursive groups can be verified before linking.
 */
public record Definition(PirType type, PirTerm term) {
    public Definition {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(term, "term");
    }
}
