package com.bloxbean.cardano.julc.decompiler.lift;

import com.bloxbean.cardano.julc.core.DefaultFun;
import com.bloxbean.cardano.julc.core.Term;

/**
 * Recognizes IfThenElse patterns in UPLC.
 * <p>
 * The compiler generates:
 * {@code Force(Apply(Apply(Apply(Force(Builtin(IfThenElse)), cond), Delay(then)), Delay(else)))}
 * <p>
 * Using {@link ForceCollapser}, this becomes:
 * A forced IfThenElse builtin with 3 args: [cond, Delay(then), Delay(else)]
 */
public final class IfThenElseRecognizer {

    private IfThenElseRecognizer() {}

    /**
     * Check if a term is the Force wrapping an IfThenElse application.
     * Pattern: Force(Apply(Apply(Apply(Force(Force(Builtin(IfThenElse))), cond), Delay(then)), Delay(else)))
     * The outer Force is needed because IfThenElse returns a thunk.
     */
    public static IfComponents match(Term term) {
        // The outer wrapper is Force(Apply(..., Delay(else)))
        if (!(term instanceof Term.Force outerForce)) {
            return null;
        }

        // Try to match as a forced IfThenElse with 3 args
        var fb = ForceCollapser.matchForcedBuiltin(outerForce.term());
        if (fb != null && fb.fun() == DefaultFun.IfThenElse && fb.args().size() == 3) {
            Term cond = fb.args().get(0);
            Term thenTerm = unwrapDelay(fb.args().get(1));
            Term elseTerm = unwrapDelay(fb.args().get(2));
            return new IfComponents(cond, thenTerm, elseTerm);
        }

        // Alternative pattern: the outer Force applies to the partial application
        // Force(Apply(Apply(Apply(Force(Force(Builtin(IfThenElse))), cond), Delay(then)), Delay(else)))
        // Here ForceCollapser on outerForce.term() should have matched as Apply chain on IfThenElse
        // Let's try manual matching for robustness
        return matchManual(term);
    }

    /**
     * Manual pattern matching for IfThenElse that handles the outer Force wrapping.
     */
    private static IfComponents matchManual(Term term) {
        // Pattern: Force(Apply(Apply(Apply(Force(Force(Builtin(IfThenElse))), cond), Delay(then)), Delay(else)))
        if (!(term instanceof Term.Force f1)) return null;
        if (!(f1.term() instanceof Term.Apply a1)) return null;
        if (!(a1.function() instanceof Term.Apply a2)) return null;
        if (!(a2.function() instanceof Term.Apply a3)) return null;

        // a3.function() should be Force(Force(Builtin(IfThenElse)))
        Term ifBuiltin = a3.function();
        if (!isForceForceBuiltin(ifBuiltin, DefaultFun.IfThenElse)
                && !isForceBuiltin(ifBuiltin, DefaultFun.IfThenElse)) {
            return null;
        }

        Term cond = a3.argument();
        Term thenTerm = unwrapDelay(a2.argument());
        Term elseTerm = unwrapDelay(a1.argument());

        return new IfComponents(cond, thenTerm, elseTerm);
    }

    private static boolean isForceForceBuiltin(Term term, DefaultFun expected) {
        return term instanceof Term.Force f1
                && f1.term() instanceof Term.Force f2
                && f2.term() instanceof Term.Builtin b
                && b.fun() == expected;
    }

    private static boolean isForceBuiltin(Term term, DefaultFun expected) {
        return term instanceof Term.Force f
                && f.term() instanceof Term.Builtin b
                && b.fun() == expected;
    }

    private static Term unwrapDelay(Term term) {
        if (term instanceof Term.Delay d) {
            return d.term();
        }
        return term;
    }

    public record IfComponents(Term condition, Term thenBranch, Term elseBranch) {}
}
