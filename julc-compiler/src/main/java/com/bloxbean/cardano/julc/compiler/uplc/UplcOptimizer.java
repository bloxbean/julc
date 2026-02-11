package com.bloxbean.cardano.julc.compiler.uplc;

import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.DefaultFun;
import com.bloxbean.cardano.julc.core.Term;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Multi-pass UPLC optimizer. Runs optimization passes iteratively until fixpoint.
 * <p>
 * Passes (in order):
 * <ol>
 *   <li>Force/Delay cancellation: Force(Delay(t)) → t</li>
 *   <li>Constant folding: Apply(Apply(Builtin(op), Const(a)), Const(b)) → Const(op(a,b))</li>
 *   <li>Dead code elimination: Apply(Lam(x, body), val) → body when x not free in body</li>
 *   <li>Beta reduction: Apply(Lam(x, body), arg) → body[x:=arg] when x used exactly once</li>
 *   <li>Eta reduction: Lam(x, Apply(f, Var(1))) → f when x not free in f</li>
 *   <li>Constr/Case reduction: Case(Constr(tag, fields), branches) → apply branches[tag] to fields</li>
 * </ol>
 */
public class UplcOptimizer {

    private static final int MAX_ITERATIONS = 20;

    /**
     * Optimize a UPLC term by running all passes until fixpoint.
     */
    public Term optimize(Term term) {
        Term current = term;
        for (int i = 0; i < MAX_ITERATIONS; i++) {
            Term optimized = runAllPasses(current);
            if (optimized.equals(current)) {
                break; // Fixpoint reached
            }
            current = optimized;
        }
        return current;
    }

    private Term runAllPasses(Term term) {
        Term t = term;
        t = forceDelayCancel(t);
        t = constantFold(t);
        t = deadCodeElimination(t);
        t = betaReduce(t);
        t = etaReduce(t);
        t = constrCaseReduce(t);
        return t;
    }

    // ---- Pass 1: Force/Delay cancellation ----

    Term forceDelayCancel(Term term) {
        return switch (term) {
            case Term.Force(var inner) -> {
                var optimized = forceDelayCancel(inner);
                if (optimized instanceof Term.Delay(var delayed)) {
                    yield delayed; // Force(Delay(t)) → t
                }
                yield Term.force(optimized);
            }
            case Term.Delay(var inner) -> Term.delay(forceDelayCancel(inner));
            case Term.Lam(var name, var body) -> Term.lam(name, forceDelayCancel(body));
            case Term.Apply(var fn, var arg) -> Term.apply(forceDelayCancel(fn), forceDelayCancel(arg));
            case Term.Constr(var tag, var fields) ->
                    new Term.Constr(tag, fields.stream().map(this::forceDelayCancel).toList());
            case Term.Case(var scrutinee, var branches) ->
                    new Term.Case(forceDelayCancel(scrutinee),
                            branches.stream().map(this::forceDelayCancel).toList());
            default -> term; // Var, Const, Builtin, Error — no children to recurse
        };
    }

    // ---- Pass 2: Constant folding ----

    Term constantFold(Term term) {
        return switch (term) {
            case Term.Apply(var fn, var arg) -> {
                var optFn = constantFold(fn);
                var optArg = constantFold(arg);
                // Check for Apply(Apply(Builtin(op), Const(a)), Const(b))
                if (optFn instanceof Term.Apply(var innerFn, var innerArg)
                        && innerArg instanceof Term.Const(var constA)
                        && optArg instanceof Term.Const(var constB)) {
                    // Strip forces to find the builtin
                    var rawBuiltin = stripForces(innerFn);
                    if (rawBuiltin instanceof Term.Builtin(var fun)) {
                        var folded = foldBinaryOp(fun, constA, constB);
                        if (folded != null) yield folded;
                    }
                }
                yield Term.apply(optFn, optArg);
            }
            case Term.Force(var inner) -> Term.force(constantFold(inner));
            case Term.Delay(var inner) -> Term.delay(constantFold(inner));
            case Term.Lam(var name, var body) -> Term.lam(name, constantFold(body));
            case Term.Constr(var tag, var fields) ->
                    new Term.Constr(tag, fields.stream().map(this::constantFold).toList());
            case Term.Case(var scrutinee, var branches) ->
                    new Term.Case(constantFold(scrutinee),
                            branches.stream().map(this::constantFold).toList());
            default -> term;
        };
    }

    private Term stripForces(Term term) {
        return switch (term) {
            case Term.Force(var inner) -> stripForces(inner);
            default -> term;
        };
    }

    private Term foldBinaryOp(DefaultFun fun, Constant a, Constant b) {
        if (a instanceof Constant.IntegerConst(var va) && b instanceof Constant.IntegerConst(var vb)) {
            return switch (fun) {
                case AddInteger -> Term.const_(Constant.integer(va.add(vb)));
                case SubtractInteger -> Term.const_(Constant.integer(va.subtract(vb)));
                case MultiplyInteger -> Term.const_(Constant.integer(va.multiply(vb)));
                case EqualsInteger -> Term.const_(Constant.bool(va.equals(vb)));
                case LessThanInteger -> Term.const_(Constant.bool(va.compareTo(vb) < 0));
                case LessThanEqualsInteger -> Term.const_(Constant.bool(va.compareTo(vb) <= 0));
                default -> null;
            };
        }
        if (a instanceof Constant.BoolConst(var va) && b instanceof Constant.BoolConst(var vb)) {
            // No direct boolean builtins, but we could add more
            return null;
        }
        if (a instanceof Constant.ByteStringConst(var va) && b instanceof Constant.ByteStringConst(var vb)) {
            if (fun == DefaultFun.EqualsByteString) {
                return Term.const_(Constant.bool(java.util.Arrays.equals(va, vb)));
            }
            if (fun == DefaultFun.AppendByteString) {
                var result = new byte[va.length + vb.length];
                System.arraycopy(va, 0, result, 0, va.length);
                System.arraycopy(vb, 0, result, va.length, vb.length);
                return Term.const_(Constant.byteString(result));
            }
        }
        return null;
    }

    // ---- Pass 3: Dead code elimination ----

    Term deadCodeElimination(Term term) {
        return switch (term) {
            case Term.Apply(var fn, var arg) -> {
                var optFn = deadCodeElimination(fn);
                var optArg = deadCodeElimination(arg);
                // Apply(Lam(x, body), val) where x not free in body → body
                // BUT: preserve side-effecting expressions (Trace)
                if (optFn instanceof Term.Lam(var name, var body)) {
                    if (!isFree(1, body) && !hasSideEffect(optArg)) {
                        // x is not used and arg has no side effects — safe to drop
                        yield shiftDown(body, 1);
                    }
                }
                yield Term.apply(optFn, optArg);
            }
            case Term.Force(var inner) -> Term.force(deadCodeElimination(inner));
            case Term.Delay(var inner) -> Term.delay(deadCodeElimination(inner));
            case Term.Lam(var name, var body) -> Term.lam(name, deadCodeElimination(body));
            case Term.Constr(var tag, var fields) ->
                    new Term.Constr(tag, fields.stream().map(this::deadCodeElimination).toList());
            case Term.Case(var scrutinee, var branches) ->
                    new Term.Case(deadCodeElimination(scrutinee),
                            branches.stream().map(this::deadCodeElimination).toList());
            default -> term;
        };
    }

    // ---- Pass 4: Beta reduction ----

    Term betaReduce(Term term) {
        return switch (term) {
            case Term.Apply(var fn, var arg) -> {
                var optFn = betaReduce(fn);
                var optArg = betaReduce(arg);
                // Apply(Lam(x, body), arg) → body[x:=arg] when x used exactly once
                if (optFn instanceof Term.Lam(var name, var body)) {
                    int useCount = countUses(1, body);
                    if (useCount == 1 && isSimple(optArg)) {
                        yield substitute(body, 1, optArg);
                    }
                }
                yield Term.apply(optFn, optArg);
            }
            case Term.Force(var inner) -> Term.force(betaReduce(inner));
            case Term.Delay(var inner) -> Term.delay(betaReduce(inner));
            case Term.Lam(var name, var body) -> Term.lam(name, betaReduce(body));
            case Term.Constr(var tag, var fields) ->
                    new Term.Constr(tag, fields.stream().map(this::betaReduce).toList());
            case Term.Case(var scrutinee, var branches) ->
                    new Term.Case(betaReduce(scrutinee),
                            branches.stream().map(this::betaReduce).toList());
            default -> term;
        };
    }

    // ---- Pass 5: Eta reduction ----

    Term etaReduce(Term term) {
        return switch (term) {
            case Term.Lam(var name, var body) -> {
                var optBody = etaReduce(body);
                // Lam(x, Apply(f, Var(1))) → f when Var(1) refers to x,
                // x is not free in f, AND f is a value (not an application).
                // In strict/call-by-value UPLC, eta reduction is only safe when
                // f is already a value. Eta-reducing \v. (x x) v → (x x) would
                // change evaluation order and cause the Z-combinator to diverge.
                if (optBody instanceof Term.Apply(var f, var arg)
                        && arg instanceof Term.Var(var v) && v.index() == 1
                        && !isFree(1, f)
                        && isValue(f)) {
                    yield shiftDown(f, 1);
                }
                yield Term.lam(name, optBody);
            }
            case Term.Apply(var fn, var arg) -> Term.apply(etaReduce(fn), etaReduce(arg));
            case Term.Force(var inner) -> Term.force(etaReduce(inner));
            case Term.Delay(var inner) -> Term.delay(etaReduce(inner));
            case Term.Constr(var tag, var fields) ->
                    new Term.Constr(tag, fields.stream().map(this::etaReduce).toList());
            case Term.Case(var scrutinee, var branches) ->
                    new Term.Case(etaReduce(scrutinee),
                            branches.stream().map(this::etaReduce).toList());
            default -> term;
        };
    }

    // ---- Pass 6: Constr/Case reduction ----

    Term constrCaseReduce(Term term) {
        return switch (term) {
            case Term.Case(var scrutinee, var branches) -> {
                var optScrutinee = constrCaseReduce(scrutinee);
                var optBranches = branches.stream().map(this::constrCaseReduce).toList();
                // Case(Constr(tag, fields), branches) → apply branches[tag] to fields
                if (optScrutinee instanceof Term.Constr(var tag, var fields)
                        && tag >= 0 && tag < optBranches.size()) {
                    Term branch = optBranches.get((int) tag);
                    // Apply branch to each field
                    for (var field : fields) {
                        branch = Term.apply(branch, field);
                    }
                    yield branch;
                }
                yield new Term.Case(optScrutinee, optBranches);
            }
            case Term.Apply(var fn, var arg) -> Term.apply(constrCaseReduce(fn), constrCaseReduce(arg));
            case Term.Force(var inner) -> Term.force(constrCaseReduce(inner));
            case Term.Delay(var inner) -> Term.delay(constrCaseReduce(inner));
            case Term.Lam(var name, var body) -> Term.lam(name, constrCaseReduce(body));
            case Term.Constr(var tag, var fields) ->
                    new Term.Constr(tag, fields.stream().map(this::constrCaseReduce).toList());
            default -> term;
        };
    }

    // ---- Side-effect detection ----

    /**
     * Check if a term contains side-effecting operations (e.g., Trace).
     * Side-effecting terms must not be eliminated by dead code elimination.
     */
    static boolean hasSideEffect(Term term) {
        return switch (term) {
            case Term.Builtin(var fun) -> fun == DefaultFun.Trace;
            case Term.Apply(var fn, var arg) -> hasSideEffect(fn) || hasSideEffect(arg);
            case Term.Force(var inner) -> hasSideEffect(inner);
            case Term.Delay(var inner) -> hasSideEffect(inner);
            case Term.Lam(_, var body) -> hasSideEffect(body);
            case Term.Constr(_, var fields) -> fields.stream().anyMatch(UplcOptimizer::hasSideEffect);
            case Term.Case(var scrutinee, var branches) ->
                    hasSideEffect(scrutinee) || branches.stream().anyMatch(UplcOptimizer::hasSideEffect);
            default -> false; // Var, Const, Error
        };
    }

    // ---- De Bruijn utility functions ----

    /**
     * Check if variable at given De Bruijn index is free (used) in the term.
     */
    static boolean isFree(int index, Term term) {
        return switch (term) {
            case Term.Var(var v) -> v.index() == index;
            case Term.Lam(_, var body) -> isFree(index + 1, body);
            case Term.Apply(var fn, var arg) -> isFree(index, fn) || isFree(index, arg);
            case Term.Force(var inner) -> isFree(index, inner);
            case Term.Delay(var inner) -> isFree(index, inner);
            case Term.Constr(_, var fields) -> fields.stream().anyMatch(f -> isFree(index, f));
            case Term.Case(var scrutinee, var branches) ->
                    isFree(index, scrutinee) || branches.stream().anyMatch(b -> isFree(index, b));
            default -> false; // Const, Builtin, Error
        };
    }

    /**
     * Count how many times variable at given De Bruijn index is used in the term.
     */
    static int countUses(int index, Term term) {
        return switch (term) {
            case Term.Var(var v) -> v.index() == index ? 1 : 0;
            case Term.Lam(_, var body) -> countUses(index + 1, body);
            case Term.Apply(var fn, var arg) -> countUses(index, fn) + countUses(index, arg);
            case Term.Force(var inner) -> countUses(index, inner);
            case Term.Delay(var inner) -> countUses(index, inner);
            case Term.Constr(_, var fields) -> fields.stream().mapToInt(f -> countUses(index, f)).sum();
            case Term.Case(var scrutinee, var branches) ->
                    countUses(index, scrutinee) + branches.stream().mapToInt(b -> countUses(index, b)).sum();
            default -> 0;
        };
    }

    /**
     * Check if a term is "simple" enough to inline (constant, variable, or builtin).
     */
    private static boolean isSimple(Term term) {
        return switch (term) {
            case Term.Const _, Term.Var _, Term.Builtin _ -> true;
            case Term.Force(var inner) -> isSimple(inner);
            default -> false;
        };
    }

    /**
     * Check if a term is a value in call-by-value UPLC.
     * Values are already-evaluated forms: Var, Const, Builtin, Lam, Delay.
     * Apply is NOT a value (unevaluated computation) — eta-reducing \x. f x → f
     * when f contains Apply would change evaluation order.
     */
    private static boolean isValue(Term term) {
        return switch (term) {
            case Term.Var _, Term.Const _, Term.Builtin _, Term.Lam _, Term.Delay _ -> true;
            case Term.Force(var inner) -> isValue(inner);
            default -> false;
        };
    }

    /**
     * Substitute variable at given De Bruijn index with a replacement term.
     * Adjusts De Bruijn indices correctly.
     */
    static Term substitute(Term term, int index, Term replacement) {
        return switch (term) {
            case Term.Var(var v) -> {
                if (v.index() == index) {
                    yield replacement;
                } else if (v.index() > index) {
                    // Shift down — the binding is removed
                    yield Term.var(v.index() - 1);
                }
                yield term;
            }
            case Term.Lam(var name, var body) ->
                    Term.lam(name, substitute(body, index + 1, shiftUp(replacement, 1)));
            case Term.Apply(var fn, var arg) ->
                    Term.apply(substitute(fn, index, replacement), substitute(arg, index, replacement));
            case Term.Force(var inner) -> Term.force(substitute(inner, index, replacement));
            case Term.Delay(var inner) -> Term.delay(substitute(inner, index, replacement));
            case Term.Constr(var tag, var fields) ->
                    new Term.Constr(tag, fields.stream()
                            .map(f -> substitute(f, index, replacement)).toList());
            case Term.Case(var scrutinee, var branches) ->
                    new Term.Case(substitute(scrutinee, index, replacement),
                            branches.stream().map(b -> substitute(b, index, replacement)).toList());
            default -> term; // Const, Builtin, Error
        };
    }

    /**
     * Shift all free variable indices >= cutoff up by the given amount.
     */
    static Term shiftUp(Term term, int amount) {
        return shift(term, amount, 1);
    }

    /**
     * Shift all free variable indices >= cutoff down by 1 (for removing a binder).
     */
    static Term shiftDown(Term term, int cutoff) {
        return shift(term, -1, cutoff);
    }

    private static Term shift(Term term, int amount, int cutoff) {
        return switch (term) {
            case Term.Var(var v) -> {
                if (v.index() >= cutoff) {
                    yield Term.var(v.index() + amount);
                }
                yield term;
            }
            case Term.Lam(var name, var body) -> Term.lam(name, shift(body, amount, cutoff + 1));
            case Term.Apply(var fn, var arg) ->
                    Term.apply(shift(fn, amount, cutoff), shift(arg, amount, cutoff));
            case Term.Force(var inner) -> Term.force(shift(inner, amount, cutoff));
            case Term.Delay(var inner) -> Term.delay(shift(inner, amount, cutoff));
            case Term.Constr(var tag, var fields) ->
                    new Term.Constr(tag, fields.stream().map(f -> shift(f, amount, cutoff)).toList());
            case Term.Case(var scrutinee, var branches) ->
                    new Term.Case(shift(scrutinee, amount, cutoff),
                            branches.stream().map(b -> shift(b, amount, cutoff)).toList());
            default -> term; // Const, Builtin, Error
        };
    }
}
