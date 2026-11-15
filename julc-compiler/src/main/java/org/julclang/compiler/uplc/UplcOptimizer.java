package org.julclang.compiler.uplc;

import org.julclang.compiler.CompilationContext;
import org.julclang.core.BuiltinSemantics;
import org.julclang.core.Constant;
import org.julclang.core.DefaultFun;
import org.julclang.core.ExpModIntegerSemantics;
import org.julclang.core.Term;
import org.julclang.core.recursion.Trampoline;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * Multi-pass UPLC optimizer. Runs optimization passes iteratively until fixpoint.
 * <p>
 * Passes (in order):
 * <ol>
 *   <li>Force/Delay cancellation: Force(Delay(t)) → t</li>
 *   <li>Constant folding: Apply(Apply(Builtin(op), Const(a)), Const(b)) → Const(op(a,b))</li>
 *   <li>Target-aware literal folding, including successful all-literal PV11 ExpMod calls</li>
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

    public static final String PV11_EXP_MOD_LITERAL_RULE =
            "pv11.o13.exp-mod-literal-fold";

    /** ADR-076 D2: repeated forced builtins and Z combinators bound once at the root ({@link ClosedValueSharing}). */
    public static final String SHARED_CLOSED_VALUES_RULE = "pv11.o17.shared-closed-values";
    private static final int MAX_ITERATIONS = 20;
    private final CompilationContext context;

    public UplcOptimizer() {
        this(CompilationContext.pv11Defaults());
    }

    public UplcOptimizer(CompilationContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    /** Optimized term plus the stable identities of passes that changed it. */
    public record OptimizationResult(Term term, List<String> appliedPasses) {
        public OptimizationResult {
            Objects.requireNonNull(term, "term");
            appliedPasses = List.copyOf(appliedPasses);
        }
    }

    /**
     * Optimize a UPLC term by running all passes until fixpoint.
     */
    public Term optimize(Term term) {
        return optimizeWithReport(term).term();
    }

    public OptimizationResult optimizeWithReport(Term term) {
        if (!context.optimizationLevel().baselineOptimizerEnabled()) {
            return new OptimizationResult(term, List.of());
        }
        Term current = term;
        var appliedPasses = new LinkedHashSet<String>();
        for (int i = 0; i < MAX_ITERATIONS; i++) {
            Term optimized = runAllPasses(current, appliedPasses);
            if (optimized.equals(current)) {
                break; // Fixpoint reached
            }
            current = optimized;
        }
        // After the fixpoint, so that no pass inlines a shared value back into its uses.
        if (context.optimizationLevel().pv11SafeRulesEnabled() && context.ruleEnabled(SHARED_CLOSED_VALUES_RULE)) {
            current = applyPass(SHARED_CLOSED_VALUES_RULE, current, ClosedValueSharing::share, appliedPasses);
        }
        return new OptimizationResult(current, List.copyOf(appliedPasses));
    }

    private Term runAllPasses(Term term, LinkedHashSet<String> appliedPasses) {
        Term t = term;
        t = applyPass("force-delay-cancel", t, this::forceDelayCancel, appliedPasses);
        t = applyPass("constant-fold", t, this::constantFold, appliedPasses);
        if (context.optimizationLevel().pv11SafeRulesEnabled()) {
            t = applyPass(PV11_EXP_MOD_LITERAL_RULE, t,
                    this::foldLiteralExpMod, appliedPasses);
        }
        t = applyPass("dead-code-elimination", t, this::deadCodeElimination, appliedPasses);
        t = applyPass("beta-reduce", t, this::betaReduce, appliedPasses);
        t = applyPass("eta-reduce", t, this::etaReduce, appliedPasses);
        t = applyPass("constr-case-reduce", t, this::constrCaseReduce, appliedPasses);
        return t;
    }

    private static Term applyPass(
            String passName,
            Term input,
            UnaryOperator<Term> pass,
            LinkedHashSet<String> appliedPasses) {
        var output = pass.apply(input);
        if (!output.equals(input)) {
            appliedPasses.add(passName);
        }
        return output;
    }

    // ---- Stack safety (issue #327, ADR-074) ----
    //
    // A script nests as deeply as it is long (each binding a validator makes is a Lam inside an Apply), so every walk
    // below keeps its pending work on the heap: a rewrite returns a Trampoline and is run once, at its entry point.
    // Each rewrite visits children in the order the recursive form did, and each pass is a pure function of its
    // input, so the result is the same term.

    /**
     * {@code term} rebuilt from its children, each rewritten by {@code map} one after another: a Lam's body, an
     * Apply's function then argument, a Force's or Delay's term, a Constr's fields, a Case's scrutinee then branches.
     * A Var, Const, Builtin or Error is returned as it is.
     */
    private static Trampoline<Term> mapChildren(Term term, Function<Term, Trampoline<Term>> map) {
        return switch (term) {
            case Term.Lam(var name, var body) -> map.apply(body).map(newBody -> Term.lam(name, newBody));
            case Term.Apply(var fn, var arg) -> map.apply(fn).then(newFn -> map.apply(arg)
                    .map(newArg -> Term.apply(newFn, newArg)));
            case Term.Force(var inner) -> map.apply(inner).map(Term::force);
            case Term.Delay(var inner) -> map.apply(inner).map(Term::delay);
            case Term.Constr(var tag, var fields) -> Trampoline.each(fields, map)
                    .map(newFields -> (Term) new Term.Constr(tag, newFields));
            case Term.Case(var scrutinee, var branches) -> map.apply(scrutinee).then(newScrutinee ->
                    Trampoline.each(branches, map).map(newBranches -> (Term) new Term.Case(newScrutinee, newBranches)));
            default -> Trampoline.done(term); // Var, Const, Builtin, Error — no children
        };
    }

    // ---- Pass 1: Force/Delay cancellation ----

    Term forceDelayCancel(Term term) {
        return forceDelayCancelWalk(term).run();
    }

    private Trampoline<Term> forceDelayCancelWalk(Term term) {
        return Trampoline.defer(() -> switch (term) {
            case Term.Force(var inner) -> forceDelayCancelWalk(inner).map(optimized ->
                    optimized instanceof Term.Delay(var delayed)
                            ? delayed // Force(Delay(t)) → t
                            : Term.force(optimized));
            default -> mapChildren(term, this::forceDelayCancelWalk);
        });
    }

    // ---- Pass 2: Constant folding ----

    Term constantFold(Term term) {
        return constantFoldWalk(term).run();
    }

    private Trampoline<Term> constantFoldWalk(Term term) {
        return Trampoline.defer(() -> switch (term) {
            case Term.Apply(var fn, var arg) -> constantFoldWalk(fn).then(optFn -> constantFoldWalk(arg).map(optArg -> {
                // Check for Apply(Apply(Builtin(op), Const(a)), Const(b)).
                // The builtin must appear bare: every foldable op has type arity 0,
                // so a Force wrapper would make the application error at runtime
                // and folding it away would hide that error.
                if (optFn instanceof Term.Apply(var innerFn, var innerArg)
                        && innerArg instanceof Term.Const(var constA)
                        && optArg instanceof Term.Const(var constB)
                        && innerFn instanceof Term.Builtin(var fun)) {
                    var folded = foldBinaryOp(fun, constA, constB);
                    if (folded != null) return folded;
                }
                return Term.apply(optFn, optArg);
            }));
            default -> mapChildren(term, this::constantFoldWalk);
        });
    }

    /**
     * Fold a binary builtin over two constants. Only total operations may be
     * folded — a partial builtin (e.g. DivideInteger with divisor 0) must keep
     * its runtime error, so it is never added here without an explicit
     * cannot-fail check on the operand values.
     */
    private Term foldBinaryOp(DefaultFun fun, Constant a, Constant b) {
        if (!context.resolvedTarget().featureProfile().isBuiltinAvailable(fun)) {
            return null;
        }
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

    /**
     * Fold successful all-literal ExpModInteger calls. Failure cases retain the
     * runtime builtin so their operation-specific failure text and point remain
     * observable. Since a successful result is in [0, modulus), replacing the
     * complete three-literal application cannot increase embedded integer size.
     */
    Term foldLiteralExpMod(Term term) {
        return foldLiteralExpMod(term, Set.of());
    }

    private Term foldLiteralExpMod(Term term, Set<Integer> expModAliases) {
        return foldLiteralExpModWalk(term, expModAliases).run();
    }

    private Trampoline<Term> foldLiteralExpModWalk(Term term, Set<Integer> expModAliases) {
        return Trampoline.defer(() -> {
            Trampoline<Term> mapped = switch (term) {
                case Term.Apply(Term.Lam(var name, var body), Term.Builtin(var fun))
                        when fun == DefaultFun.ExpModInteger -> {
                    var withBinding = new LinkedHashSet<>(shiftAliases(expModAliases));
                    withBinding.add(1);
                    yield foldLiteralExpModWalk(body, Set.copyOf(withBinding)).map(newBody -> Term.apply(
                            Term.lam(name, newBody),
                            Term.builtin(DefaultFun.ExpModInteger)));
                }
                case Term.Lam(var name, var body) -> foldLiteralExpModWalk(body, shiftAliases(expModAliases))
                        .map(newBody -> Term.lam(name, newBody));
                default -> mapChildren(term, child -> foldLiteralExpModWalk(child, expModAliases));
            };
            return mapped.map(rewritten -> foldExpModCall(rewritten, expModAliases));
        });
    }

    /** {@code mapped}, or the literal its successful all-literal ExpModInteger call evaluates to. */
    private Term foldExpModCall(Term mapped, Set<Integer> expModAliases) {
        if (!context.resolvedTarget().featureProfile()
                .isBuiltinAvailable(DefaultFun.ExpModInteger)) {
            return mapped;
        }
        var application = applicationSpine(mapped);
        if (application == null
                || !isExpModHead(application.head(), expModAliases)
                || application.args().size() != 3
                || !(application.args().get(0) instanceof Term.Const(
                        Constant.IntegerConst(var base)))
                || !(application.args().get(1) instanceof Term.Const(
                        Constant.IntegerConst(var exponent)))
                || !(application.args().get(2) instanceof Term.Const(
                        Constant.IntegerConst(var modulus)))) {
            return mapped;
        }
        try {
            var result = ExpModIntegerSemantics.evaluate(base, exponent, modulus);
            return Term.const_(Constant.integer(result));
        } catch (ExpModIntegerSemantics.EvaluationFailure referenceFailure) {
            return mapped;
        }
    }

    private static Set<Integer> shiftAliases(Set<Integer> aliases) {
        if (aliases.isEmpty()) return aliases;
        var shifted = new LinkedHashSet<Integer>();
        aliases.forEach(index -> shifted.add(index + 1));
        return Set.copyOf(shifted);
    }

    private static boolean isExpModHead(Term head, Set<Integer> aliases) {
        return head instanceof Term.Builtin(var fun) && fun == DefaultFun.ExpModInteger
                || head instanceof Term.Var(var name) && aliases.contains(name.index());
    }

    private record ApplicationSpine(Term head, List<Term> args) {}

    private static ApplicationSpine applicationSpine(Term term) {
        var reversed = new ArrayList<Term>();
        var head = term;
        while (head instanceof Term.Apply(var function, var argument)) {
            reversed.add(argument);
            head = function;
        }
        if (reversed.isEmpty()) return null;
        Collections.reverse(reversed);
        return new ApplicationSpine(head, List.copyOf(reversed));
    }

    // ---- Pass 4: Dead code elimination ----

    Term deadCodeElimination(Term term) {
        return deadCodeEliminationWalk(term).run();
    }

    private Trampoline<Term> deadCodeEliminationWalk(Term term) {
        return Trampoline.defer(() -> switch (term) {
            case Term.Apply(var fn, var arg) -> deadCodeEliminationWalk(fn).then(optFn ->
                    deadCodeEliminationWalk(arg).map(optArg -> {
                        // Apply(Lam(x, body), arg) where x not free in body → body.
                        // Sound only when arg is pure: under call-by-value the argument
                        // is evaluated before the application, so discarding an argument
                        // that could error, diverge, or trace would change behavior.
                        if (optFn instanceof Term.Lam(var name, var body)) {
                            if (!isFree(1, body) && isPure(optArg)) {
                                return shiftDown(body, 1);
                            }
                        }
                        return Term.apply(optFn, optArg);
                    }));
            default -> mapChildren(term, this::deadCodeEliminationWalk);
        });
    }

    // ---- Pass 5: Beta reduction ----

    Term betaReduce(Term term) {
        return betaReduceWalk(term).run();
    }

    private Trampoline<Term> betaReduceWalk(Term term) {
        return Trampoline.defer(() -> switch (term) {
            case Term.Apply(var fn, var arg) -> betaReduceWalk(fn).then(optFn -> betaReduceWalk(arg).map(optArg -> {
                // Apply(Lam(x, body), arg) → body[x:=arg] when x used exactly once
                if (optFn instanceof Term.Lam(var name, var body)) {
                    int useCount = countUses(1, body);
                    if (useCount == 1 && isSimple(optArg)) {
                        return substitute(body, 1, optArg);
                    }
                }
                return Term.apply(optFn, optArg);
            }));
            default -> mapChildren(term, this::betaReduceWalk);
        });
    }

    // ---- Pass 6: Eta reduction ----

    Term etaReduce(Term term) {
        return etaReduceWalk(term).run();
    }

    private Trampoline<Term> etaReduceWalk(Term term) {
        return Trampoline.defer(() -> switch (term) {
            case Term.Lam(var name, var body) -> etaReduceWalk(body).map(optBody -> {
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
                    return shiftDown(f, 1);
                }
                return Term.lam(name, optBody);
            });
            default -> mapChildren(term, this::etaReduceWalk);
        });
    }

    // ---- Pass 7: Constr/Case reduction ----

    Term constrCaseReduce(Term term) {
        return constrCaseReduceWalk(term).run();
    }

    private Trampoline<Term> constrCaseReduceWalk(Term term) {
        return Trampoline.defer(() -> switch (term) {
            case Term.Case(var scrutinee, var branches) -> constrCaseReduceWalk(scrutinee).then(optScrutinee ->
                    Trampoline.each(branches, this::constrCaseReduceWalk).map(optBranches -> {
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
                            return branch;
                        }
                        return (Term) new Term.Case(optScrutinee, optBranches);
                    }));
            default -> mapChildren(term, this::constrCaseReduceWalk);
        });
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
        // A conjunction over the term and the fields and arguments it requires to be values: checked with a work list,
        // as a Constr can nest as deeply as a list literal is long (issue #327).
        var pending = new ArrayDeque<Term>();
        pending.push(term);
        while (!pending.isEmpty()) {
            var next = pending.pop();
            switch (next) {
                case Term.Var _, Term.Const _, Term.Builtin _, Term.Lam _, Term.Delay _ -> { }
                case Term.Constr(_, var fields) -> pushAll(pending, fields);
                case Term.Force _, Term.Apply _ -> {
                    var spine = builtinSpine(next);
                    if (spine == null) return false;
                    var sig = BuiltinSemantics.find(spine.fun());
                    if (sig == null) return false;
                    if (spine.args().isEmpty()) {
                        if (spine.forces() > sig.typeArity()) return false;
                    } else {
                        if (spine.forces() != sig.typeArity() || spine.args().size() >= sig.valueArity()) return false;
                        pushAll(pending, spine.args());
                    }
                }
                default -> {
                    return false; // Error, Case
                }
            }
        }
        return true;
    }

    /** Pushes {@code terms} so that they are popped in their order. */
    private static void pushAll(ArrayDeque<Term> pending, List<Term> terms) {
        for (int i = terms.size() - 1; i >= 0; i--) pending.push(terms.get(i));
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
        return pure(term).run();
    }

    /** {@link #isPure}, stack-safe: a pure conditional can nest as deeply as an else-if chain is long (issue #327). */
    private static Trampoline<Boolean> pure(Term term) {
        return Trampoline.defer(() -> {
            if (isValue(term)) return Trampoline.done(true);
            if (isFixpointOfFunction(term)) return Trampoline.done(true);
            var lazyIf = lazyIf(term);
            if (lazyIf != null) {
                return satisfies(lazyIf.cond(), BuiltinSemantics.ArgType.BOOL).then(cond -> !cond
                        ? Trampoline.done(false)
                        : pure(lazyIf.thenBody()).then(then -> !then ? Trampoline.done(false) : pure(lazyIf.elseBody())));
            }
            var spine = builtinSpine(term);
            if (spine == null) return Trampoline.done(false);
            var sig = BuiltinSemantics.find(spine.fun());
            if (sig == null || !sig.total()) return Trampoline.done(false);
            if (spine.forces() != sig.typeArity() || spine.args().size() != sig.valueArity()) {
                return Trampoline.done(false);
            }
            return argumentsSatisfy(spine.args(), sig.argTypes());
        });
    }

    /** Whether each argument satisfies the type at its position, checked in order, stopping at the first that fails. */
    private static Trampoline<Boolean> argumentsSatisfy(List<Term> args, List<BuiltinSemantics.ArgType> types) {
        var positions = new ArrayList<Integer>(args.size());
        for (int i = 0; i < args.size(); i++) positions.add(i);
        return Trampoline.all(positions, i -> satisfies(args.get(i), types.get(i)));
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
    private static Trampoline<Boolean> satisfies(Term arg, BuiltinSemantics.ArgType expected) {
        return Trampoline.defer(() -> {
            if (expected == BuiltinSemantics.ArgType.ANY) {
                return pure(arg);
            }
            if (arg instanceof Term.Const(var c)) {
                return Trampoline.done(BuiltinSemantics.constantMatches(c, expected));
            }
            return certifiedType(arg).map(type -> BuiltinSemantics.typeSatisfies(type, expected));
        });
    }

    /**
     * The provable runtime type of a pure term, or null. Non-null means the
     * term is certified pure and evaluates to a value of the returned type:
     * a typed constant, a saturated total builtin application with certified
     * arguments, or a lazy conditional whose branches certify to the same type.
     */
    private static Trampoline<BuiltinSemantics.ArgType> certifiedType(Term term) {
        return Trampoline.defer(() -> {
            if (term instanceof Term.Const(var c)) {
                return Trampoline.done(BuiltinSemantics.constantType(c));
            }
            var lazyIf = lazyIf(term);
            if (lazyIf != null) {
                return satisfies(lazyIf.cond(), BuiltinSemantics.ArgType.BOOL).then(cond -> !cond
                        ? Trampoline.<BuiltinSemantics.ArgType>done(null)
                        : certifiedType(lazyIf.thenBody()).then(thenType -> certifiedType(lazyIf.elseBody())
                                .map(elseType -> thenType != null && thenType == elseType ? thenType : null)));
            }
            var spine = builtinSpine(term);
            if (spine == null) return Trampoline.done(null);
            var sig = BuiltinSemantics.find(spine.fun());
            if (sig == null || !sig.total() || sig.resultType() == null) return Trampoline.done(null);
            if (spine.forces() != sig.typeArity() || spine.args().size() != sig.valueArity()) {
                return Trampoline.done(null);
            }
            return argumentsSatisfy(spine.args(), sig.argTypes()).map(all -> all ? sig.resultType() : null);
        });
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
        // A conjunction over corresponding subterms, compared with a work list (issue #327).
        var pending = new ArrayDeque<Term>();
        pending.push(b);
        pending.push(a);
        while (!pending.isEmpty()) {
            Term x = pending.pop();
            Term y = pending.pop();
            boolean same = switch (x) {
                case Term.Var(var v) -> y instanceof Term.Var(var w) && v.index() == w.index();
                case Term.Lam(_, var bodyA) -> y instanceof Term.Lam(_, var bodyB) && pushPair(pending, bodyA, bodyB);
                case Term.Apply(var fnA, var argA) -> y instanceof Term.Apply(var fnB, var argB)
                        && pushPair(pending, argA, argB) && pushPair(pending, fnA, fnB);
                case Term.Force(var innerA) -> y instanceof Term.Force(var innerB) && pushPair(pending, innerA, innerB);
                case Term.Delay(var innerA) -> y instanceof Term.Delay(var innerB) && pushPair(pending, innerA, innerB);
                case Term.Constr(var tagA, var fieldsA) -> y instanceof Term.Constr(var tagB, var fieldsB)
                        && tagA == tagB && pushPairs(pending, fieldsA, fieldsB);
                case Term.Case(var scrutA, var branchesA) -> y instanceof Term.Case(var scrutB, var branchesB)
                        && pushPairs(pending, branchesA, branchesB) && pushPair(pending, scrutA, scrutB);
                default -> x.equals(y); // Const, Builtin, Error
            };
            if (!same) return false;
        }
        return true;
    }

    /** Pushes the pair so that {@code a} and {@code b} are popped together; always true. */
    private static boolean pushPair(ArrayDeque<Term> pending, Term a, Term b) {
        pending.push(b);
        pending.push(a);
        return true;
    }

    /** Pushes corresponding elements as pairs, or answers false when the lists differ in length. */
    private static boolean pushPairs(ArrayDeque<Term> pending, List<Term> a, List<Term> b) {
        if (a.size() != b.size()) return false;
        for (int i = a.size() - 1; i >= 0; i--) pushPair(pending, a.get(i), b.get(i));
        return true;
    }

    /**
     * Check if a term is simple enough to substitute into a single use site
     * (beta reduction). Must be a value whose re-evaluation at the use site is
     * effect-free and near-zero cost: constants, variables, and (partially)
     * type-instantiated builtins. Larger values (Lam, Delay, Constr, partial
     * applications) are excluded to avoid moving code around.
     */
    /**
     * Whether beta reduction replaces a binding used once by {@code value}: a constant, a variable, or a builtin forced
     * no more than its type arity. ADR-076's handler sharing asks this of a method's optimized body, since sharing a
     * binding the optimizer would have inlined keeps it bound.
     */
    public static boolean inlinesWhenUsedOnce(Term value) {
        return isSimple(value);
    }

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
        var found = new boolean[1];
        visitVariables(index, term, (wanted, occurrence) -> {
            if (occurrence == wanted) found[0] = true;
            return !found[0];
        });
        return found[0];
    }

    /**
     * Count how many times variable at given De Bruijn index is used in the term.
     */
    static int countUses(int index, Term term) {
        var uses = new int[1];
        visitVariables(index, term, (wanted, occurrence) -> {
            if (occurrence == wanted) uses[0]++;
            return true;
        });
        return uses[0];
    }

    /** Receives each variable occurrence's index with the index the variable sought has at that depth. */
    private interface VariableVisitor {
        /** Returns whether to keep visiting. */
        boolean visit(int wanted, int index);
    }

    /**
     * Visits every variable occurrence in {@code term}, with the index that the variable {@code index} has at its depth
     * (one more under each Lam), using a work list instead of recursion (issue #327).
     */
    private static void visitVariables(int index, Term term, VariableVisitor visitor) {
        var terms = new ArrayDeque<Term>();
        var indexes = new ArrayDeque<Integer>();
        terms.push(term);
        indexes.push(index);
        while (!terms.isEmpty()) {
            Term next = terms.pop();
            int wanted = indexes.pop();
            switch (next) {
                case Term.Var(var v) -> {
                    if (!visitor.visit(wanted, v.index())) return;
                }
                case Term.Lam(_, var body) -> {
                    terms.push(body);
                    indexes.push(wanted + 1);
                }
                case Term.Apply(var fn, var arg) -> {
                    terms.push(arg);
                    indexes.push(wanted);
                    terms.push(fn);
                    indexes.push(wanted);
                }
                case Term.Force(var inner) -> {
                    terms.push(inner);
                    indexes.push(wanted);
                }
                case Term.Delay(var inner) -> {
                    terms.push(inner);
                    indexes.push(wanted);
                }
                case Term.Constr(_, var fields) -> {
                    for (int i = fields.size() - 1; i >= 0; i--) {
                        terms.push(fields.get(i));
                        indexes.push(wanted);
                    }
                }
                case Term.Case(var scrutinee, var branches) -> {
                    for (int i = branches.size() - 1; i >= 0; i--) {
                        terms.push(branches.get(i));
                        indexes.push(wanted);
                    }
                    terms.push(scrutinee);
                    indexes.push(wanted);
                }
                default -> { } // Const, Builtin, Error
            }
        }
    }

    /**
     * Substitute variable at given De Bruijn index with a replacement term.
     * Adjusts De Bruijn indices correctly.
     */
    static Term substitute(Term term, int index, Term replacement) {
        return substituteWalk(term, index, replacement).run();
    }

    private static Trampoline<Term> substituteWalk(Term term, int index, Term replacement) {
        return Trampoline.defer(() -> switch (term) {
            case Term.Var(var v) -> {
                if (v.index() == index) {
                    yield Trampoline.done(replacement);
                } else if (v.index() > index) {
                    // Shift down — the binding is removed
                    yield Trampoline.done(Term.var(v.index() - 1));
                }
                yield Trampoline.done(term);
            }
            case Term.Lam(var name, var body) -> substituteWalk(body, index + 1, shiftUp(replacement, 1))
                    .map(newBody -> Term.lam(name, newBody));
            default -> mapChildren(term, child -> substituteWalk(child, index, replacement));
        });
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
        return shiftWalk(term, amount, cutoff).run();
    }

    private static Trampoline<Term> shiftWalk(Term term, int amount, int cutoff) {
        return Trampoline.defer(() -> switch (term) {
            case Term.Var(var v) -> {
                if (v.index() >= cutoff) {
                    yield Trampoline.done(Term.var(v.index() + amount));
                }
                yield Trampoline.done(term);
            }
            case Term.Lam(var name, var body) -> shiftWalk(body, amount, cutoff + 1).map(newBody -> Term.lam(name, newBody));
            default -> mapChildren(term, child -> shiftWalk(child, amount, cutoff));
        });
    }
}
