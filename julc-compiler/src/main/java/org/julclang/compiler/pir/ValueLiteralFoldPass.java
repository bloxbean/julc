package org.julclang.compiler.pir;

import org.julclang.compiler.CompilationContext;
import org.julclang.compiler.CompilerTarget;
import org.julclang.core.BuiltinSemantics;
import org.julclang.core.Constant;
import org.julclang.core.DefaultFun;
import org.julclang.core.NativeValueSemantics;
import org.julclang.core.PlutusData;
import org.julclang.core.Term;
import org.julclang.core.flat.FlatWriter;
import org.julclang.core.flat.UplcFlatEncoder;
import org.julclang.core.source.SourceLocation;
import org.julclang.vm.ProtocolCapability;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ADR-045 (O14): fold native Value builtin calls whose arguments are all literals into the
 * literal they evaluate to, at the safe profile on the PV11 target.
 *
 * <p>A <b>literal</b> is a {@link PirTerm.Const}, or a variable bound exactly once in the
 * program by a {@code Let} whose value is (or has just become) a constant. A <b>literal
 * call</b> is a saturated application of one of {@code InsertCoin}, {@code LookupCoin},
 * {@code UnionValue}, {@code ValueContains}, {@code ScaleValue}, {@code ValueData} or
 * {@code UnValueData} to literals, spelled either as the bare builtin or through a
 * <b>wrapper</b>: a variable bound exactly once to a lambda chain, every parameter of which
 * occurs in the body, whose body is that builtin applied, by position, to the chain's
 * parameters and to constants ({@code NativeValueLib}'s methods are exactly such wrappers;
 * the producers {@code Builtins.emptyValue}, {@code singletonValue} and
 * {@code lovelaceValue} are intrinsics that inline the constant or the bare
 * {@code InsertCoin} spine at the call site). Every argument at a wrapper call site must be a
 * literal, used or not: the strict application evaluates them all.
 *
 * <p>A literal call is replaced by its result exactly when the pinned semantics
 * ({@link NativeValueSemantics}, the same code the VM runs) succeed on the literals and the
 * FLAT encoding of the result is not longer, in bits, than the encoding of the term it
 * replaces, measured as it stands in the artifact: the builtin (or the wrapper variable, at
 * the smallest index) applied to the call-site arguments, where a constant counts as itself
 * and a literal local counts as a variable reference, not as the constant its binding
 * carries. A local bound directly to a constant whose every remaining occurrence the call
 * consumes dies with the fold (the UPLC optimiser's dead-code elimination drops the
 * binding), so its first occurrence in the call counts as that constant; a local that stays
 * live elsewhere is only a reference here, and folding through it would copy its constant
 * into the call site. An alias ({@code Let w = Var v}) is never credited: its binding holds
 * a reference, and the constant it names stays as long as {@code v} has any other
 * occurrence, the alias binding itself included (the second review found the guard
 * measuring every local as its constant, then crediting a dying alias with the constant it
 * names; either lets a shared local grow the artifact). A
 * call the semantics reject is left exactly as written, so its runtime failure text and
 * failure point are untouched; a call with a non-literal argument (a runtime key, a trace, an
 * error) is never touched. Folding is bottom-up, so nested literal calls fold to a fixed
 * point, and a local bound to a folded literal feeds the calls below it. No algebraic
 * identity is applied: {@code lookupCoin(p, t, emptyValue())} with a runtime key stays a call.
 *
 * <p>Soundness: every argument of a folded call is a value, so no evaluation, trace or failure
 * is skipped; the result is what the builtin would compute at runtime, by the same code; a
 * wrapper applied to values is a fixed number of beta steps around that builtin. NONE and
 * BASELINE never run the pass. Rule {@value #RULE} is recorded when a fold fires. The pass
 * expects a closed term (every pipeline site hands it one): a free variable that happens to
 * share its name with a once-bound literal elsewhere would be read as that literal.
 */
public final class ValueLiteralFoldPass {

    public static final String RULE = "pv11.o14.value-literal-fold";

    private static final Set<DefaultFun> VALUE_BUILTINS = Set.of(
            DefaultFun.InsertCoin, DefaultFun.LookupCoin, DefaultFun.UnionValue,
            DefaultFun.ValueContains, DefaultFun.ScaleValue, DefaultFun.ValueData,
            DefaultFun.UnValueData);

    /** A once-bound lambda chain whose body is a Value builtin over its parameters and constants. */
    private record Wrapper(DefaultFun fun, List<String> params, List<PirTerm> bodyArgs) {}

    private record Spine(PirTerm head, List<PirTerm> args) {}

    private final CompilationContext context;
    private final IdentityHashMap<PirTerm, SourceLocation> positions = new IdentityHashMap<>();
    private final Map<String, Integer> binderCounts = new HashMap<>();
    private final Map<String, PirTerm> letValues = new HashMap<>();
    private final Map<String, Wrapper> wrappers = new HashMap<>();
    private final Map<String, Constant> literals = new HashMap<>();
    /** Occurrences of each variable still standing in the term; a fold removes the ones it consumes. */
    private final Map<String, Integer> remainingUses = new HashMap<>();
    /** Literals bound directly to their constant (not aliases): the only bindings a fold may be credited with. */
    private final Set<String> constantBindings = new HashSet<>();
    private boolean applied;

    public ValueLiteralFoldPass(CompilationContext context, Map<PirTerm, SourceLocation> positions) {
        this.context = context;
        if (positions != null) this.positions.putAll(positions);
    }

    public record Result(PirTerm term, Map<PirTerm, SourceLocation> positions) {}

    public Result lower(PirTerm term) {
        if (!context.target().equals(CompilerTarget.PLUTUS_V3_PV11)
                || !context.optimizationLevel().pv11SafeRulesEnabled()
                || !context.supports(ProtocolCapability.VALUE_CONSTANTS)
                || !context.ruleEnabled(RULE)) {
            return new Result(term, positions);
        }
        var rewritten = term;
        PirTerm previous;
        do {
            previous = rewritten;
            binderCounts.clear();
            letValues.clear();
            wrappers.clear();
            literals.clear();
            remainingUses.clear();
            constantBindings.clear();
            collectBinders(rewritten);
            letValues.forEach((name, value) -> {
                if (binderCounts.get(name) != 1) return;
                var wrapper = wrapperOf(value);
                if (wrapper != null) wrappers.put(name, wrapper);
                if (value instanceof PirTerm.Const c) {
                    literals.put(name, c.value());
                    constantBindings.add(name);
                }
            });
            rewritten = fold(rewritten);
        } while (rewritten != previous);
        if (applied) context.recordOptimizationRule(RULE);
        return new Result(rewritten, positions);
    }

    /** Bottom-up: fold children, register a local that became a literal, then try the node. */
    private PirTerm fold(PirTerm term) {
        if (term instanceof PirTerm.Let let) {
            var value = fold(let.value());
            if (binderCounts.get(let.name()) == 1) {
                // A local bound to a literal, or aliasing one (`JulcValue f = e` with `e` such a
                // local), is a literal below.
                var literal = literalOf(value);
                if (literal != null) literals.put(let.name(), literal);
                if (value instanceof PirTerm.Const) constantBindings.add(let.name());
            }
            var body = fold(let.body());
            return remember(term, new PirTerm.Let(let.name(), value, body));
        }
        var mapped = remember(term, PirHelpers.mapChildren(term, this::fold));
        var folded = foldLiteralCall(mapped);
        return folded == null ? mapped : remember(mapped, folded);
    }

    /** The literal a saturated literal call evaluates to, or null when it is not one or must stay. */
    private PirTerm foldLiteralCall(PirTerm term) {
        var spine = spineOf(term);
        if (spine == null) return null;
        DefaultFun fun;
        List<PirTerm> args;
        if (spine.head() instanceof PirTerm.Builtin builtin && VALUE_BUILTINS.contains(builtin.fun())) {
            fun = builtin.fun();
            args = spine.args();
        } else if (spine.head() instanceof PirTerm.Var head && wrappers.containsKey(head.name())) {
            var wrapper = wrappers.get(head.name());
            if (spine.args().size() != wrapper.params().size()) return null;
            // Every call-site argument is evaluated by the strict application whether or not
            // the body uses it (a wrapper uses every parameter, but this guard does not rely on
            // that): a non-literal anywhere in the spine blocks the fold.
            for (var actual : spine.args()) {
                if (literalOf(actual) == null) return null;
            }
            fun = wrapper.fun();
            args = new ArrayList<>();
            for (var bodyArg : wrapper.bodyArgs()) {
                args.add(bodyArg instanceof PirTerm.Var param
                        ? spine.args().get(wrapper.params().indexOf(param.name())) : bodyArg);
            }
        } else {
            return null;
        }
        var sig = BuiltinSemantics.find(fun);
        if (sig == null || args.size() != sig.valueArity()) return null;
        var constants = new ArrayList<Constant>();
        for (var arg : args) {
            var literal = literalOf(arg);
            if (literal == null) return null;
            constants.add(literal);
        }
        var result = evaluate(fun, constants);
        if (result == null) return null;
        // The term this fold replaces, as it stands in the artifact: the bare builtin, or for a
        // wrapper call the wrapper variable (the shape that stays when the wrapper remains live;
        // if the optimiser inlines the wrapper instead, the artifact loses its body as well, so
        // this is the conservative bound), applied to the call-site arguments. A constant
        // argument stands there as itself. A literal local stands there as a variable
        // reference, and its constant lives once in its binding: when the local is bound
        // directly to that constant and this call consumes every remaining occurrence of it,
        // the binding dies with the fold (the optimiser drops it), so the first occurrence is
        // measured as that constant and any further occurrence in the same call as a
        // reference. A local that stays live elsewhere is measured as a reference only, since
        // folding would copy its constant into the call site; so is an alias (`Let w = Var v`)
        // whether or not it dies: its binding holds a reference, and `v`'s constant stays as
        // long as `v` has any other occurrence, the alias binding itself included.
        var consumed = new HashMap<String, Integer>();
        for (var actual : spine.args()) {
            if (actual instanceof PirTerm.Var v) consumed.merge(v.name(), 1, Integer::sum);
        }
        Term replaced = spine.head() instanceof PirTerm.Builtin ? Term.builtin(fun) : Term.var(1);
        var measuredAsConstant = new HashSet<String>();
        for (var actual : spine.args()) {
            Term measure;
            if (actual instanceof PirTerm.Var v) {
                boolean dies = constantBindings.contains(v.name())
                        && consumed.get(v.name()).equals(remainingUses.getOrDefault(v.name(), 0));
                measure = dies && measuredAsConstant.add(v.name()) ? Term.const_(literalOf(actual)) : Term.var(1);
            } else {
                measure = Term.const_(literalOf(actual));
            }
            replaced = Term.apply(replaced, measure);
        }
        if (!fitsObjective(replaced, result)) return null;
        consumed.forEach((name, uses) -> remainingUses.merge(name, -uses, Integer::sum));
        applied = true;
        return new PirTerm.Const(result);
    }

    private Constant literalOf(PirTerm term) {
        return switch (term) {
            case PirTerm.Const c -> c.value();
            case PirTerm.Var v -> literals.get(v.name());
            default -> null;
        };
    }

    /** The pinned semantics on literals; null when they reject the call (it then stays as written). */
    private static Constant evaluate(DefaultFun fun, List<Constant> args) {
        try {
            return switch (fun) {
                case InsertCoin -> new Constant.ValueConst(NativeValueSemantics.insertCoin(
                        bytes(args.get(0)), bytes(args.get(1)), integer(args.get(2)), value(args.get(3))).entries());
                case LookupCoin -> Constant.integer(NativeValueSemantics.lookupCoin(
                        bytes(args.get(0)), bytes(args.get(1)), value(args.get(2))));
                case UnionValue -> NativeValueSemantics.unionValue(value(args.get(0)), value(args.get(1)));
                case ValueContains -> Constant.bool(NativeValueSemantics.valueContains(value(args.get(0)), value(args.get(1))));
                case ScaleValue -> NativeValueSemantics.scaleValue(integer(args.get(0)), value(args.get(1)));
                case ValueData -> Constant.data(NativeValueSemantics.valueData(value(args.get(0))));
                case UnValueData -> NativeValueSemantics.unValueData(data(args.get(0)));
                default -> null;
            };
        } catch (NativeValueSemantics.EvaluationFailure | IllegalArgumentException rejected) {
            return null;
        }
    }

    /**
     * The artifact objective: the FLAT encoding of the literal must not be longer, in bits,
     * than the encoding of the term it replaces (a Value-to-Value fold only removes
     * applications and constant headers; a Data conversion swaps CBOR-in-FLAT for list
     * structure and is measured). Bits, not bytes, so that a sequence of folds cannot grow the
     * artifact through rounding; the whole artifact's final padding can still differ by up to
     * seven bits.
     */
    private static boolean fitsObjective(Term replaced, Constant result) {
        return bitLength(Term.const_(result)) <= bitLength(replaced);
    }

    /** The FLAT bit length of a term on its own (no program header, no padding). */
    static int bitLength(Term term) {
        var writer = new FlatWriter();
        new UplcFlatEncoder(writer).writeTerm(term);
        return writer.bitLength();
    }

    private static byte[] bytes(Constant c) {
        if (c instanceof Constant.ByteStringConst b) return b.value();
        throw new IllegalArgumentException("not a bytestring literal");
    }

    private static BigInteger integer(Constant c) {
        if (c instanceof Constant.IntegerConst i) return i.value();
        throw new IllegalArgumentException("not an integer literal");
    }

    private static Constant.ValueConst value(Constant c) {
        if (c instanceof Constant.ValueConst v) return v;
        throw new IllegalArgumentException("not a value literal");
    }

    private static PlutusData data(Constant c) {
        if (c instanceof Constant.DataConst d) return d.value();
        throw new IllegalArgumentException("not a data literal");
    }

    /** A lambda chain whose body is a saturated Value builtin over its parameters and constants. */
    private static Wrapper wrapperOf(PirTerm value) {
        var params = new ArrayList<String>();
        PirTerm body = value;
        while (body instanceof PirTerm.Lam lam) {
            if (params.contains(lam.param())) return null;
            params.add(lam.param());
            body = lam.body();
        }
        var spine = spineOf(body);
        if (spine == null || !(spine.head() instanceof PirTerm.Builtin builtin)
                || !VALUE_BUILTINS.contains(builtin.fun())) {
            return null;
        }
        var sig = BuiltinSemantics.find(builtin.fun());
        if (sig == null || spine.args().size() != sig.valueArity()) return null;
        var used = new HashSet<String>();
        for (var arg : spine.args()) {
            if (arg instanceof PirTerm.Var v && params.contains(v.name())) {
                used.add(v.name());
            } else if (!(arg instanceof PirTerm.Const)) {
                return null;
            }
        }
        // A parameter the body never uses would let its argument vanish unexamined.
        if (!used.containsAll(params)) return null;
        return new Wrapper(builtin.fun(), List.copyOf(params), List.copyOf(spine.args()));
    }

    private static Spine spineOf(PirTerm term) {
        var reversed = new ArrayList<PirTerm>();
        PirTerm head = term;
        while (head instanceof PirTerm.App app) {
            reversed.add(app.argument());
            head = app.function();
        }
        if (reversed.isEmpty()) return null;
        Collections.reverse(reversed);
        return new Spine(head, List.copyOf(reversed));
    }

    private void collectBinders(PirTerm term) {
        switch (term) {
            case PirTerm.Var v -> remainingUses.merge(v.name(), 1, Integer::sum);
            case PirTerm.Lam l -> bind(l.param());
            case PirTerm.Let l -> { bind(l.name()); letValues.putIfAbsent(l.name(), l.value()); }
            case PirTerm.LetRec r -> r.bindings().forEach(b -> bind(b.name()));
            case PirTerm.ListMatch m -> { bind(m.headName()); bind(m.tailName()); }
            case PirTerm.PairMatch m -> { bind(m.firstName()); bind(m.secondName()); }
            case PirTerm.DataMatch m -> m.branches().forEach(b -> {
                b.bindings().forEach(this::bind);
                if (b.patternVar() != null) bind(b.patternVar());
            });
            default -> { }
        }
        PirHelpers.mapChildren(term, child -> { collectBinders(child); return child; });
    }

    private void bind(String name) {
        binderCounts.merge(name, 1, Integer::sum);
    }

    /** Preserve unchanged nodes and map replacements back to their original source locations. */
    private PirTerm remember(PirTerm original, PirTerm replacement) {
        if (original.equals(replacement)) return original;
        var location = positions.get(original);
        if (location != null) positions.put(replacement, location);
        return replacement;
    }
}
