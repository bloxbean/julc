package com.bloxbean.cardano.julc.compiler.uplc;

import com.bloxbean.cardano.julc.core.BuiltinSemantics;
import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.DefaultFun;
import com.bloxbean.cardano.julc.core.Term;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Multi-pass UPLC optimizer. Runs optimization passes iteratively until fixpoint.
 * <p>
 * Passes (in order):
 * <ol>
 *   <li>Force/Delay cancellation: Force(Delay(t)) → t</li>
 *   <li>Constant folding: Apply(Apply(Builtin(op), Const(a)), Const(b)) → Const(op(a,b))</li>
 *   <li>Dead code elimination: Apply(Lam(x, body), arg) → body when x not free in body
 *       and arg is pure</li>
 *   <li>Beta reduction: Apply(Lam(x, body), arg) → body[x:=arg] when x used exactly once
 *       and arg is a simple value</li>
 *   <li>Eta reduction: Lam(x, Apply(f, Var(1))) → f when x not free in f and f is
 *       observationally function-like</li>
 *   <li>Constr/Case reduction: Case(Constr(tag, fields), branches) → apply branches[tag]
 *       to fields when every field is a value</li>
 * </ol>
 * <p>
 * <b>Soundness.</b> UPLC is strict (call-by-value): every argument is evaluated before
 * a function is applied, and evaluation can error ({@code Error}, partial builtins),
 * diverge, or emit logs ({@code Trace}). Every rewrite in this class must preserve the
 * observable outcome (success/failure, result value, emitted traces). The gates are
 * therefore <i>whitelists over values</i> ({@link #isValue}, {@link #isPure}) — the same
 * approach as Plutus IR's purity analysis — never blacklists of known-effectful shapes.
 * Force handling is arity-aware via {@link BuiltinSemantics}: forcing a builtin more
 * times than its type arity, or applying arguments while forces remain, is a runtime
 * error, so force counts are checked exactly, and unknown builtins are treated as unsafe.
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
                // Check for Apply(Apply(Builtin(op), Const(a)), Const(b)).
                // The builtin must appear bare: every foldable op has type arity 0,
                // so a Force wrapper would make the application error at runtime
                // and folding it away would hide that error.
                if (optFn instanceof Term.Apply(var innerFn, var innerArg)
                        && innerArg instanceof Term.Const(var constA)
                        && optArg instanceof Term.Const(var constB)
                        && innerFn instanceof Term.Builtin(var fun)) {
                    var folded = foldBinaryOp(fun, constA, constB);
                    if (folded != null) yield folded;
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

    /**
     * Fold a binary builtin over two constants. Only total operations may be
     * folded — a partial builtin (e.g. DivideInteger with divisor 0) must keep
     * its runtime error, so it is never added here without an explicit
     * cannot-fail check on the operand values.
     */
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
                // Apply(Lam(x, body), arg) where x not free in body → body.
                // Sound only when arg is pure: under call-by-value the argument
                // is evaluated before the application, so discarding an argument
                // that could error, diverge, or trace would change behavior.
                if (optFn instanceof Term.Lam(var name, var body)) {
                    if (!isFree(1, body) && isPure(optArg)) {
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
                // Lam(x, Apply(f, Var(1))) → f when Var(1) refers to x and x is not
                // free in f. Beyond that, f must be observationally Lam-like
                // (see isEtaReducible): the reduced term is used in place of a
                // lambda value, so it must behave identically under every
                // elimination context (Apply, Force, Case) and its evaluation
                // must be effect-free.
                if (optBody instanceof Term.Apply(var f, var arg)
                        && arg instanceof Term.Var(var v) && v.index() == 1
                        && !isFree(1, f)
                        && isEtaReducible(f)) {
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
                // Case(Constr(tag, fields), branches) → apply branches[tag] to fields.
                // The rewrite moves branch computation before field evaluation, and
                // lets the branch body start running between field applications
                // (CEK evaluates all Constr fields first, then the selected branch,
                // then applies). That reordering is unobservable exactly when every
                // field is a value: field evaluation is then effect-free, so only
                // the branch can error, trace, or diverge — and it does so
                // identically in both forms. The branch itself may be any term.
                // (Gating fields on isPure instead would also be sound and slightly
                // less conservative; isValue keeps this pass independent of the
                // purity certification machinery.)
                if (optScrutinee instanceof Term.Constr(var tag, var fields)
                        && tag >= 0 && tag < optBranches.size()
                        && fields.stream().allMatch(UplcOptimizer::isValue)) {
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

    // ---- Value / purity analysis ----

    /**
     * A builtin application spine: {@code Apply*(Force^forces(Builtin fun), args...)}.
     * Forces must all sit directly on the builtin, below any applications — the CEK
     * machine consumes all forces before accepting value arguments, so any other
     * interleaving is a runtime error and is not recognized as a spine.
     */
    record BuiltinSpine(DefaultFun fun, int forces, List<Term> args) {}

    /**
     * Decompose a term into a builtin application spine, or return null if the
     * term is not of that shape.
     */
    static BuiltinSpine builtinSpine(Term term) {
        var args = new ArrayList<Term>();
        Term cur = term;
        while (cur instanceof Term.Apply(var fn, var arg)) {
            args.add(arg);
            cur = fn;
        }
        int forces = 0;
        while (cur instanceof Term.Force(var inner)) {
            forces++;
            cur = inner;
        }
        if (cur instanceof Term.Builtin(var fun)) {
            Collections.reverse(args);
            return new BuiltinSpine(fun, forces, args);
        }
        return null;
    }

    /**
     * Check if a term is a value under call-by-value UPLC: evaluating it always
     * succeeds without emitting logs, and the work involved is trivial.
     * <ul>
     *   <li>Var, Const, Builtin, Lam, Delay — machine values</li>
     *   <li>Constr — a value iff all fields are values (fields evaluate eagerly)</li>
     *   <li>Force^k(Builtin f) with k ≤ typeArity(f) — partial or full type
     *       instantiation of a builtin is a value; over-forcing errors</li>
     *   <li>a builtin spine with forces == typeArity, fewer args than valueArity,
     *       and all args values — an under-saturated builtin application is a
     *       value (argument checking is deferred to saturation)</li>
     *   <li>everything else (Error, Case, other Apply/Force) — not a value</li>
     * </ul>
     * Unknown builtins are conservatively not values.
     */
    static boolean isValue(Term term) {
        return switch (term) {
            case Term.Var _, Term.Const _, Term.Builtin _, Term.Lam _, Term.Delay _ -> true;
            case Term.Constr(_, var fields) -> fields.stream().allMatch(UplcOptimizer::isValue);
            case Term.Force _, Term.Apply _ -> {
                var spine = builtinSpine(term);
                if (spine == null) yield false;
                var sig = BuiltinSemantics.find(spine.fun());
                if (sig == null) yield false;
                if (spine.args().isEmpty()) {
                    yield spine.forces() <= sig.typeArity();
                }
                yield spine.forces() == sig.typeArity()
                        && spine.args().size() < sig.valueArity()
                        && spine.args().stream().allMatch(UplcOptimizer::isValue);
            }
            default -> false; // Error, Case
        };
    }

    /**
     * Check if discarding a term without evaluating it cannot change observable
     * behavior: the term is guaranteed to evaluate successfully without emitting
     * logs. All values are pure, and so is a fixpoint application of a function
     * (see {@link #isFixpointOfFunction}). Additionally, a <i>saturated</i>
     * application of a total builtin is pure when every argument provably has
     * the declared type: typed positions require a matching constant,
     * polymorphic (ANY) positions require a pure term. Everything else —
     * Error, partial builtins, unknown shapes — is impure.
     */
    static boolean isPure(Term term) {
        if (isValue(term)) return true;
        if (isFixpointOfFunction(term)) return true;
        var lazyIf = lazyIf(term);
        if (lazyIf != null) {
            return argSatisfies(lazyIf.cond(), BuiltinSemantics.ArgType.BOOL)
                    && isPure(lazyIf.thenBody()) && isPure(lazyIf.elseBody());
        }
        var spine = builtinSpine(term);
        if (spine == null) return false;
        var sig = BuiltinSemantics.find(spine.fun());
        if (sig == null || !sig.total()) return false;
        if (spine.forces() != sig.typeArity() || spine.args().size() != sig.valueArity()) {
            return false;
        }
        for (int i = 0; i < spine.args().size(); i++) {
            if (!argSatisfies(spine.args().get(i), sig.argTypes().get(i))) return false;
        }
        return true;
    }

    /**
     * The lazy conditional shape {@code force ((force ifThenElse) cond (delay a) (delay b))}.
     * Evaluating it evaluates the condition, selects one of the two delay
     * values, and forces it — running exactly one branch body. When the
     * condition is provably a boolean and both branch bodies are pure, the
     * whole conditional is pure.
     */
    private record LazyIf(Term cond, Term thenBody, Term elseBody) {}

    private static LazyIf lazyIf(Term term) {
        if (!(term instanceof Term.Force(var inner))) return null;
        var spine = builtinSpine(inner);
        if (spine == null || spine.fun() != DefaultFun.IfThenElse) return null;
        if (spine.forces() != 1 || spine.args().size() != 3) return null;
        if (spine.args().get(1) instanceof Term.Delay(var thenBody)
                && spine.args().get(2) instanceof Term.Delay(var elseBody)) {
            return new LazyIf(spine.args().get(0), thenBody, elseBody);
        }
        return null;
    }

    /**
     * Check that an argument term provably has the declared type. Typed
     * positions accept a matching constant, or a certified pure term whose
     * result type satisfies the expectation — this lets nested constant
     * constructions (e.g. Data literals built from constrData/mkCons/
     * mkNilData/iData, including lazy-if-encoded booleans) certify as pure.
     * Polymorphic (ANY) positions accept any pure term.
     */
    private static boolean argSatisfies(Term arg, BuiltinSemantics.ArgType expected) {
        if (expected == BuiltinSemantics.ArgType.ANY) {
            return isPure(arg);
        }
        if (arg instanceof Term.Const(var c)) {
            return BuiltinSemantics.constantMatches(c, expected);
        }
        return BuiltinSemantics.typeSatisfies(certifiedTypeOf(arg), expected);
    }

    /**
     * The provable runtime type of a pure term, or null. Non-null means the
     * term is certified pure and evaluates to a value of the returned type:
     * a typed constant, a saturated total builtin application with certified
     * arguments, or a lazy conditional whose branches certify to the same type.
     */
    private static BuiltinSemantics.ArgType certifiedTypeOf(Term term) {
        if (term instanceof Term.Const(var c)) {
            return BuiltinSemantics.constantType(c);
        }
        var lazyIf = lazyIf(term);
        if (lazyIf != null) {
            if (!argSatisfies(lazyIf.cond(), BuiltinSemantics.ArgType.BOOL)) return null;
            var thenType = certifiedTypeOf(lazyIf.thenBody());
            var elseType = certifiedTypeOf(lazyIf.elseBody());
            return thenType != null && thenType == elseType ? thenType : null;
        }
        var spine = builtinSpine(term);
        if (spine == null) return null;
        var sig = BuiltinSemantics.find(spine.fun());
        if (sig == null || !sig.total() || sig.resultType() == null) return null;
        if (spine.forces() != sig.typeArity() || spine.args().size() != sig.valueArity()) {
            return null;
        }
        for (int i = 0; i < spine.args().size(); i++) {
            if (!argSatisfies(spine.args().get(i), sig.argTypes().get(i))) return null;
        }
        return sig.resultType();
    }

    /**
     * The Z combinator emitted by {@code UplcGenerator.generateLetRec} for
     * recursive bindings: {@code \f -> (\x -> f (\v -> x x v)) (\x -> f (\v -> x x v))}.
     */
    private static final Term Z_COMBINATOR;

    static {
        var innerBody = Term.lam("v",
                Term.apply(Term.apply(Term.var(2), Term.var(2)), Term.var(1)));
        var branch = Term.lam("x", Term.apply(Term.var(2), innerBody));
        Z_COMBINATOR = Term.lam("f", Term.apply(branch, branch));
    }

    /**
     * Recognize {@code Z (\name -> valueBody)} — the shape every LetRec binding
     * lowers to. Evaluating it performs a fixed number of beta steps over
     * lambda values and then evaluates {@code valueBody} once (under CEK the
     * recursive reference is passed as an un-forced thunk lambda), so when
     * {@code valueBody} is a value the whole application cannot error, diverge,
     * or emit logs — it just builds the recursive closure. That makes an unused
     * recursive binding safely discardable. The proof rests only on the term
     * shape (matched alpha-structurally), not on where the term came from.
     */
    static boolean isFixpointOfFunction(Term term) {
        return term instanceof Term.Apply(var fn, var arg)
                && alphaEquals(fn, Z_COMBINATOR)
                && arg instanceof Term.Lam(var name, var body)
                && isValue(body);
    }

    /**
     * Structural equality on De Bruijn terms ignoring binder display names.
     */
    static boolean alphaEquals(Term a, Term b) {
        return switch (a) {
            case Term.Var(var v) -> b instanceof Term.Var(var w) && v.index() == w.index();
            case Term.Lam(_, var bodyA) -> b instanceof Term.Lam(_, var bodyB)
                    && alphaEquals(bodyA, bodyB);
            case Term.Apply(var fnA, var argA) -> b instanceof Term.Apply(var fnB, var argB)
                    && alphaEquals(fnA, fnB) && alphaEquals(argA, argB);
            case Term.Force(var innerA) -> b instanceof Term.Force(var innerB)
                    && alphaEquals(innerA, innerB);
            case Term.Delay(var innerA) -> b instanceof Term.Delay(var innerB)
                    && alphaEquals(innerA, innerB);
            case Term.Constr(var tagA, var fieldsA) -> b instanceof Term.Constr(var tagB, var fieldsB)
                    && tagA == tagB && fieldsA.size() == fieldsB.size()
                    && java.util.stream.IntStream.range(0, fieldsA.size())
                            .allMatch(i -> alphaEquals(fieldsA.get(i), fieldsB.get(i)));
            case Term.Case(var scrutA, var branchesA) -> b instanceof Term.Case(var scrutB, var branchesB)
                    && alphaEquals(scrutA, scrutB) && branchesA.size() == branchesB.size()
                    && java.util.stream.IntStream.range(0, branchesA.size())
                            .allMatch(i -> alphaEquals(branchesA.get(i), branchesB.get(i)));
            default -> a.equals(b); // Const, Builtin, Error
        };
    }

    /**
     * Check if a term is simple enough to substitute into a single use site
     * (beta reduction). Must be a value whose re-evaluation at the use site is
     * effect-free and near-zero cost: constants, variables, and (partially)
     * type-instantiated builtins. Larger values (Lam, Delay, Constr, partial
     * applications) are excluded to avoid moving code around.
     */
    private static boolean isSimple(Term term) {
        return switch (term) {
            case Term.Const _, Term.Var _, Term.Builtin _ -> true;
            case Term.Force _ -> {
                var spine = builtinSpine(term);
                if (spine == null || !spine.args().isEmpty()) yield false;
                var sig = BuiltinSemantics.find(spine.fun());
                yield sig != null && spine.forces() <= sig.typeArity();
            }
            default -> false;
        };
    }

    /**
     * Check if eta reduction Lam(x, Apply(f, Var(1))) → f is sound for this f.
     * The reduced f replaces a lambda <i>value</i>, so it must:
     * (1) evaluate without effects — be a value;
     * (2) behave like a lambda under every elimination context: applying it
     *     must not over-saturate, and forcing or case-matching it must error
     *     just like forcing/casing a lambda does.
     * That holds for lambdas themselves and for builtin spines that consumed
     * exactly their type arity of forces and still expect at least one value
     * argument. It does NOT hold for Var (may be bound to a Delay/Constr, which
     * force/case eliminate differently), Delay, Constr, or under-forced builtins.
     */
    private static boolean isEtaReducible(Term f) {
        if (f instanceof Term.Lam) return true;
        var spine = builtinSpine(f);
        if (spine == null) return false;
        var sig = BuiltinSemantics.find(spine.fun());
        return sig != null
                && spine.forces() == sig.typeArity()
                && spine.args().size() < sig.valueArity()
                && spine.args().stream().allMatch(UplcOptimizer::isValue);
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
