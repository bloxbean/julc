package com.bloxbean.cardano.julc.decompiler.lift;

import com.bloxbean.cardano.julc.core.Term;
import com.bloxbean.cardano.julc.decompiler.hir.HirTerm;

/**
 * Recognizes Let bindings in UPLC.
 * <p>
 * The compiler generates: {@code Apply(Lam(name, body), value)}
 * which is equivalent to: {@code let name = value in body}
 */
public final class LetRecognizer {

    private LetRecognizer() {}

    /**
     * Check if a term is a Let binding pattern: Apply(Lam(name, body), value).
     */
    public static boolean isLet(Term term) {
        return term instanceof Term.Apply app
                && app.function() instanceof Term.Lam;
    }

    /**
     * Extract the components of a Let binding.
     * Returns null if the term doesn't match.
     */
    public static LetComponents match(Term term) {
        if (term instanceof Term.Apply app && app.function() instanceof Term.Lam lam) {
            return new LetComponents(lam.paramName(), app.argument(), lam.body());
        }
        return null;
    }

    public record LetComponents(String name, Term value, Term body) {}
}
