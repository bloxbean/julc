package org.julclang.compiler;

import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;

/**
 * Evidence tooling only (ADR-068 I1b): told each method a compilation lowers, with its binder, type and term, before any
 * transform of the assembly. Both frontends report through it, so the I1 tool compares them at the same point. Removed
 * at M6.
 */
@FunctionalInterface
public interface LoweredMethodObserver {
    void lowered(String binder, PirType type, PirTerm body);
}
