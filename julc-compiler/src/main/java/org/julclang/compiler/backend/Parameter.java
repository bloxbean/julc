package org.julclang.compiler.backend;

import org.julclang.compiler.pir.PirType;

import java.util.Objects;

/**
 * A deployment parameter in ABI order (ADR-059). Every handler receives all parameters as
 * leading arguments; the name is recorded in the ABI and never used as a binder.
 */
public record Parameter(String name, PirType type) {
    public Parameter {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
    }
}
