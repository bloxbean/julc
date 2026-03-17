package com.bloxbean.cardano.julc.vm.java;

import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.NamedDeBruijn;
import com.bloxbean.cardano.julc.core.Term;

import java.util.ArrayList;

/**
 * Converts CEK machine values back to UPLC {@link Term}s for result output.
 * <p>
 * Closures (VLam with a non-empty environment) are converted by substituting
 * the captured environment into the body, producing a self-contained term.
 */
public final class ValueConverter {

    private ValueConverter() {}

    /**
     * Convert a CEK value back to a Term.
     */
    public static Term toTerm(CekValue value) {
        return switch (value) {
            case CekValue.VCon vcon -> new Term.Const(vcon.constant());
            case CekValue.VDelay vdelay -> new Term.Delay(substitute(vdelay.body(), vdelay.env(), 0));
            case CekValue.VLam vlam -> new Term.Lam(vlam.paramName(),
                    substitute(vlam.body(), vlam.env(), 1));
            case CekValue.VConstr vc -> {
                var fieldTerms = new ArrayList<Term>();
                for (var field : vc.fields()) {
                    fieldTerms.add(toTerm(field));
                }
                yield new Term.Constr(vc.tag(), fieldTerms);
            }
            case CekValue.VBuiltin vb -> {
                Term t = new Term.Builtin(vb.fun());
                var sig = com.bloxbean.cardano.julc.vm.java.builtins.BuiltinTable.getSignature(vb.fun());
                int forcesApplied = sig.forceCount() - vb.forcesRemaining();
                for (int i = 0; i < forcesApplied; i++) {
                    t = new Term.Force(t);
                }
                for (var arg : vb.collectedArgs()) {
                    t = new Term.Apply(t, toTerm(arg));
                }
                yield t;
            }
        };
    }

    /**
     * Substitute environment bindings into a term.
     * <p>
     * For variables with de Bruijn index pointing into the environment (index > depth),
     * replace with the corresponding value from the environment, converted to a term.
     * For variables within the current scope (index <= depth), keep as-is.
     *
     * @param term  the term to substitute into
     * @param env   the environment providing bindings
     * @param depth the number of lambda/delay binders between the current point and the env scope
     */
    private static Term substitute(Term term, CekEnvironment env, int depth) {
        return switch (term) {
            case Term.Var v -> {
                int idx = v.name().index();
                if (idx > depth) {
                    // This variable references the environment
                    try {
                        CekValue val = env.lookup(idx - depth);
                        yield toTerm(val);
                    } catch (CekEvaluationException e) {
                        // Variable out of range — keep as-is (free variable)
                        yield term;
                    }
                } else {
                    yield term;
                }
            }
            case Term.Lam lam -> new Term.Lam(lam.paramName(),
                    substitute(lam.body(), env, depth + 1));
            case Term.Apply app -> new Term.Apply(
                    substitute(app.function(), env, depth),
                    substitute(app.argument(), env, depth));
            case Term.Force f -> new Term.Force(substitute(f.term(), env, depth));
            case Term.Delay d -> new Term.Delay(substitute(d.term(), env, depth + 1));
            case Term.Const _ -> term;
            case Term.Builtin _ -> term;
            case Term.Error _ -> term;
            case Term.Constr c -> {
                var newFields = new ArrayList<Term>();
                for (var field : c.fields()) {
                    newFields.add(substitute(field, env, depth));
                }
                yield new Term.Constr(c.tag(), newFields);
            }
            case Term.Case cs -> new Term.Case(
                    substitute(cs.scrutinee(), env, depth),
                    cs.branches().stream()
                            .map(b -> substitute(b, env, depth))
                            .toList());
        };
    }

    /**
     * Convert a Constant to a CekValue.
     */
    public static CekValue fromConstant(Constant c) {
        return new CekValue.VCon(c);
    }
}
