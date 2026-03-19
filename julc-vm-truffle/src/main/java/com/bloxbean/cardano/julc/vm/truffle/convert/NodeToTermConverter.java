package com.bloxbean.cardano.julc.vm.truffle.convert;

import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.NamedDeBruijn;
import com.bloxbean.cardano.julc.core.Term;
import com.bloxbean.cardano.julc.vm.truffle.runtime.*;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.MaterializedFrame;

import java.util.ArrayList;

/**
 * Converts Truffle runtime values back to UPLC {@link Term}s for the EvalResult.
 * <p>
 * For closures and delays, uses the original Term from the source AST and substitutes
 * captured environment bindings, matching the Java VM's ValueConverter behavior.
 */
public final class NodeToTermConverter {

    private NodeToTermConverter() {}

    /**
     * Convert a Truffle runtime value to a Term.
     */
    public static Term toTerm(Object value) {
        if (value instanceof Constant c) {
            return new Term.Const(c);
        }
        if (value instanceof UplcConstrValue constr) {
            var fieldTerms = new ArrayList<Term>();
            for (Object field : constr.getFields()) {
                fieldTerms.add(toTerm(field));
            }
            return new Term.Constr(constr.getTag(), fieldTerms);
        }
        if (value instanceof UplcClosure closure) {
            Term.Lam original = closure.getOriginalTerm();
            if (original != null) {
                // Substitute captured frame bindings into the body.
                // Depth starts at 1 because we're inside the lambda binder.
                return new Term.Lam(original.paramName(),
                        substitute(original.body(), closure.getCapturedFrame(), 1));
            }
            // Fallback: no original term
            return new Term.Lam("x", new Term.Var(new NamedDeBruijn("x", 1)));
        }
        if (value instanceof UplcDelay delay) {
            Term.Delay original = delay.getOriginalTerm();
            if (original != null) {
                return new Term.Delay(
                        substitute(original.term(), delay.getCapturedFrame(), 0));
            }
            return new Term.Delay(new Term.Error());
        }
        if (value instanceof UplcBuiltinDescriptor bd) {
            Term t = new Term.Builtin(bd.getFun());
            var sig = com.bloxbean.cardano.julc.vm.java.builtins.BuiltinTable.getSignature(bd.getFun());
            int forcesApplied = sig.forceCount() - bd.getForcesRemaining();
            for (int i = 0; i < forcesApplied; i++) {
                t = new Term.Force(t);
            }
            for (Object arg : bd.getCollectedArgs()) {
                t = new Term.Apply(t, toTerm(arg));
            }
            return t;
        }
        throw new UplcRuntimeException("Cannot convert Truffle value to Term: " + value);
    }

    /**
     * Substitute captured frame bindings into a term.
     * <p>
     * Variables with De Bruijn index > depth reference the captured environment.
     * Variables with index <= depth are within the current scope and stay as-is.
     */
    private static Term substitute(Term term, Frame env, int depth) {
        return switch (term) {
            case Term.Var v -> {
                int idx = v.name().index();
                if (idx > depth && env != null) {
                    Object val = lookupFrame(env, idx - depth);
                    if (val != null) {
                        yield toTerm(val);
                    }
                }
                yield term;
            }
            case Term.Lam lam -> new Term.Lam(lam.paramName(),
                    substitute(lam.body(), env, depth + 1));
            case Term.Apply app -> new Term.Apply(
                    substitute(app.function(), env, depth),
                    substitute(app.argument(), env, depth));
            case Term.Force f -> new Term.Force(substitute(f.term(), env, depth));
            case Term.Delay d -> new Term.Delay(substitute(d.term(), env, depth));
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
     * Look up a value in the frame chain at the given depth.
     * Index 1 = slot 0 of the given frame, index 2 = slot 0 of parent frame, etc.
     */
    private static Object lookupFrame(Frame frame, int index) {
        Frame current = frame;
        for (int i = 1; i < index; i++) {
            Object[] args = current.getArguments();
            if (args.length < 2 || !(args[1] instanceof MaterializedFrame parent)) {
                return null; // Out of scope
            }
            current = parent;
        }
        try {
            return current.getObject(0);
        } catch (Exception e) {
            return null;
        }
    }
}
