package org.julclang.compiler.pir;

/** Test support: reads an assembled term from another package. */
public final class DelayedMethodProbe {

    private DelayedMethodProbe() {
    }

    /** Whether {@code term} binds {@code name} to a function of a unit. */
    public static boolean delays(PirTerm term, String name) {
        if (term instanceof PirTerm.Let let && let.name().equals(name) && let.value() instanceof PirTerm.Lam lam && lam.param().equals("#_")) {
            return true;
        }
        var found = new boolean[1];
        PirHelpers.mapChildren(term, child -> {
            found[0] |= delays(child, name);
            return child;
        });
        return found[0];
    }
}
