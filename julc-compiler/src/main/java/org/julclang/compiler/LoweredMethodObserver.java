package org.julclang.compiler;

import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;

/**
 * Evidence tooling only (ADR-068 I1b): told each method the JavaParser frontend lowers, with its binder, type and term,
 * before any transform of the assembly. The javac frontend does not report through it: a compilation there stops at the
 * first {@code JULC0084}, so the I1 tool asks it for each method on its own. Removed at M6.
 */
@FunctionalInterface
public interface LoweredMethodObserver {
    void lowered(String binder, PirType type, PirTerm body);
}
