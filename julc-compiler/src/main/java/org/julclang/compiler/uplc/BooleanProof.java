package org.julclang.compiler.uplc;

import org.julclang.compiler.pir.PirTerm;
import org.julclang.core.BuiltinSemantics;
import org.julclang.core.Constant;
import org.julclang.core.DefaultFun;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

/**
 * Decides whether a PIR term can only produce a real UPLC boolean (ADR-032 O2).
 *
 * <p>{@code Case} on a boolean selects a branch exactly as builtin {@code IfThenElse} does. On any
 * other value the two differ: {@code IfThenElse} fails on every value that is not a boolean, while
 * {@code Case} selects a branch for the integers 0 and 1 and for lists. {@link UplcGenerator} therefore
 * emits {@code Case} only for a condition this class proves, and the builtin for every other one.
 * A {@code BoolType} annotation and a declared Java type are not evidence: this class reads terms
 * and never reads a type.
 *
 * <p><b>The property.</b> {@link #of} assigns each term a {@link Fact}, and a {@link Scope} assigns
 * one to each binder. The meaning of a fact is a set of runtime values:
 * <ul>
 *   <li>{@link Unknown}: every value;</li>
 *   <li>{@link Never}: no value, the term fails or does not terminate;</li>
 *   <li>{@code Yields(0)}: the booleans;</li>
 *   <li>{@code Yields(n)}, {@code n > 0}: values which, applied to {@code n} arguments one at a
 *       time, produce a boolean or fail or do not terminate. A value that is not a function is one,
 *       since applying it fails.</li>
 * </ul>
 * If every binder's value is in its fact's set, then a term that produces a value produces one in
 * the set of its {@code Fact}. {@link #proves} accepts {@code Yields(0)} and {@code Never}, so a proven
 * condition is a boolean whenever it produces a value at all.
 *
 * <p><b>Why that holds</b>, by induction on the evaluation:
 * <ul>
 *   <li>A boolean constant is a boolean. A builtin in {@link #BOOLEAN_RESULTS} produces a boolean
 *       once saturated, by its signature, whatever its arguments, or it fails.</li>
 *   <li>{@code Let} is strict, so its name holds its value. A {@code Lam} parameter holds whatever
 *       the caller passes, so it is {@link Unknown} even when a binder of the same name outside it
 *       was proven: every binder rebinds its name, and the innermost one decides.</li>
 *   <li>{@code App} reads the function's fact only, since an argument's value decides nothing and
 *       its failure is allowed. Applying a value of {@code Yields(n)} gives {@code Yields(n - 1)}.</li>
 *   <li>A conditional produces the value of one arm, in either lowering, so it joins the arms. Its
 *       own condition plays no part: that condition is either proven, or lowered to the builtin,
 *       which fails on anything else. The same reasoning covers {@code DataMatch}, which runs a
 *       branch only after {@code UnConstrData} succeeds, and {@code IntegerCase}: its scrutinee
 *       selects a branch and may apply it to a list's head and tail, which leaves a value of the
 *       same fact, because arguments past {@code n} fail.</li>
 *   <li>{@code PairMatch} and {@code ListMatch} bind arguments through {@code Case}, which applies
 *       its branch only for a real pair or list. Given a boolean or an integer it can select the
 *       branch and produce the binding lambda instead of running the body. So {@code PairMatch}
 *       counts its body only over a saturated {@code UnConstrData}, which yields a real pair or
 *       fails, and {@code ListMatch}, whose scrutinee is a variable, is {@link Unknown}.</li>
 *   <li>A recursive binding is assumed to be what its leading lambdas claim, and it keeps that fact
 *       only if its value, read under the assumption, has exactly that fact; otherwise it is
 *       {@link Unknown} and the group is read again. This is the greatest fixpoint of "returns a
 *       boolean". Every recursive call inside a terminating evaluation has fewer unfoldings left, so
 *       the argument above applies to it first, and an evaluation that does not terminate produces
 *       no value. A binding with no lambda is {@link Unknown}, because the lowering's recursive
 *       reference is an eta-expansion, which equals the binding only when the binding is a
 *       function.</li>
 * </ul>
 *
 * <p>Nothing else is proven; in particular {@code DataConstr} is {@link Unknown}, because it is Data,
 * not a boolean. The class is pure, and {@link Scope} is also the generator's scope: the generator
 * keeps one and resolves names from it, so a binder cannot be seen there and missed here, and a
 * binder with no stated fact is {@link Unknown}.
 */
public final class BooleanProof {

    private BooleanProof() {}

    /** What is known of the values a term can produce. */
    sealed interface Fact permits Unknown, Never, Yields {}

    /** Any value at all. */
    record Unknown() implements Fact {}

    /** No value: the term fails or does not terminate. */
    record Never() implements Fact {}

    /** After {@code arguments} more applications, a boolean. */
    record Yields(int arguments) implements Fact {}

    static final Fact UNKNOWN = new Unknown();
    static final Fact NEVER = new Never();
    static final Fact BOOLEAN = new Yields(0);

    /** Every builtin whose saturated result is a boolean; anchored to the builtin signatures by a test. */
    private static final Set<DefaultFun> BOOLEAN_RESULTS = EnumSet.of(
            DefaultFun.EqualsInteger, DefaultFun.LessThanInteger, DefaultFun.LessThanEqualsInteger,
            DefaultFun.EqualsByteString, DefaultFun.LessThanByteString, DefaultFun.LessThanEqualsByteString,
            DefaultFun.EqualsString, DefaultFun.EqualsData, DefaultFun.NullList,
            DefaultFun.VerifyEd25519Signature, DefaultFun.VerifyEcdsaSecp256k1Signature,
            DefaultFun.VerifySchnorrSecp256k1Signature, DefaultFun.Bls12_381_G1_equal,
            DefaultFun.Bls12_381_G2_equal, DefaultFun.Bls12_381_finalVerify, DefaultFun.ReadBit,
            DefaultFun.ValueContains);

    /** Whether a saturated application of {@code fun} produces a boolean by its signature. */
    public static boolean returnsBoolean(DefaultFun fun) {
        return BOOLEAN_RESULTS.contains(fun);
    }

    /** The binders in scope, innermost first, each with what it is proven to hold. */
    record Scope(String name, Fact fact, Scope outer) {
        static final Scope EMPTY = new Scope(null, UNKNOWN, null);

        Scope bind(String binder, Fact held) {
            return new Scope(binder, held, this);
        }

        /** The innermost binder of {@code binder} decides, so a rebinding hides an outer proof. */
        Fact lookup(String binder) {
            for (var scope = this; scope != EMPTY; scope = scope.outer) {
                if (scope.name.equals(binder)) return scope.fact;
            }
            return UNKNOWN;
        }
    }

    /** Whether {@code condition}, evaluated in {@code scope}, is a boolean if it produces a value. */
    static boolean proves(PirTerm condition, Scope scope) {
        var fact = of(condition, scope);
        return fact.equals(BOOLEAN) || fact.equals(NEVER);
    }

    /** The fact of {@code term} evaluated in {@code scope}. */
    static Fact of(PirTerm term, Scope scope) {
        return switch (term) {
            case PirTerm.Const(var value) -> value instanceof Constant.BoolConst ? BOOLEAN : UNKNOWN;
            case PirTerm.Builtin(var fun) ->
                    returnsBoolean(fun) ? new Yields(BuiltinSemantics.find(fun).valueArity()) : UNKNOWN;
            case PirTerm.Var(var name, _) -> scope.lookup(name);
            case PirTerm.Lam(var param, _, var body) -> abstracted(of(body, scope.bind(param, UNKNOWN)));
            case PirTerm.App(var function, _) -> applied(of(function, scope));
            case PirTerm.Let(var name, var value, var body) -> of(body, scope.bind(name, of(value, scope)));
            case PirTerm.LetRec(var bindings, var body) -> of(body, bindRecursive(bindings, scope));
            case PirTerm.IfThenElse(_, var thenBranch, var elseBranch) ->
                    join(of(thenBranch, scope), of(elseBranch, scope));
            case PirTerm.IntegerCase(_, var branches) -> {
                Fact fact = NEVER;
                for (var branch : branches) fact = join(fact, of(branch, scope));
                yield fact;
            }
            case PirTerm.DataMatch(_, var branches) -> {
                Fact fact = NEVER;
                for (var branch : branches) {
                    var inner = scope;
                    for (var binding : branch.bindings()) inner = inner.bind(binding, UNKNOWN);
                    if (branch.patternVar() != null) inner = inner.bind(branch.patternVar(), UNKNOWN);
                    fact = join(fact, of(branch.body(), inner));
                }
                yield fact;
            }
            case PirTerm.PairMatch(var scrutinee, _, var first, var second, var body) ->
                    isUnConstrData(scrutinee)
                            ? of(body, scope.bind(first, UNKNOWN).bind(second, UNKNOWN))
                            : UNKNOWN;
            case PirTerm.Trace(_, var body) -> of(body, scope);
            case PirTerm.Error _ -> NEVER;
            case PirTerm.ListMatch _, PirTerm.DataConstr _ -> UNKNOWN;
        };
    }

    /**
     * {@code scope} extended with a recursive group, each binding holding what survives of the claim
     * of its leading lambdas. A refuted binding is {@link Unknown} for good, so a group of {@code k}
     * bindings is read at most {@code k + 1} times, and a single binding once.
     */
    static Scope bindRecursive(List<PirTerm.Binding> bindings, Scope scope) {
        var facts = new HashMap<String, Fact>();
        for (var binding : bindings) {
            int parameters = parameters(binding.value());
            facts.put(binding.name(), parameters > 0 ? new Yields(parameters) : UNKNOWN);
        }
        Scope inner;
        boolean refuted;
        do {
            refuted = false;
            inner = scope;
            for (var binding : bindings) inner = inner.bind(binding.name(), facts.get(binding.name()));
            for (var binding : bindings) {
                var claim = facts.get(binding.name());
                if (!claim.equals(UNKNOWN) && !of(binding.value(), inner).equals(claim)) {
                    facts.put(binding.name(), UNKNOWN);
                    refuted = true;
                }
            }
        } while (refuted);
        return inner;
    }

    /** The parameters a call applies: a bound value's leading lambdas, read through lets between them. */
    private static int parameters(PirTerm value) {
        return switch (value) {
            case PirTerm.Lam lam -> 1 + parameters(lam.body());
            case PirTerm.Let let -> parameters(let.body());
            case PirTerm.LetRec rec -> parameters(rec.body());
            default -> 0;
        };
    }

    private static Fact abstracted(Fact body) {
        return switch (body) {
            case Unknown _ -> UNKNOWN;
            case Never _ -> new Yields(1);
            case Yields(var arguments) -> new Yields(arguments + 1);
        };
    }

    private static Fact applied(Fact function) {
        return switch (function) {
            case Never _ -> NEVER;
            case Yields(var arguments) when arguments > 0 -> new Yields(arguments - 1);
            default -> UNKNOWN;
        };
    }

    private static Fact join(Fact a, Fact b) {
        if (a.equals(NEVER)) return b;
        if (b.equals(NEVER)) return a;
        return a.equals(b) ? a : UNKNOWN;
    }

    private static boolean isUnConstrData(PirTerm term) {
        return term instanceof PirTerm.App(PirTerm.Builtin(var fun), _) && fun == DefaultFun.UnConstrData;
    }
}
