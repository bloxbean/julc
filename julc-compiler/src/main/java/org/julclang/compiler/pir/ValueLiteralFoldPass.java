package org.julclang.compiler.pir;

import org.julclang.compiler.CompilationContext;
import org.julclang.compiler.CompilerTarget;
import org.julclang.core.BuiltinSemantics;
import org.julclang.core.Constant;
import org.julclang.core.DefaultFun;
import org.julclang.core.NativeValueSemantics;
import org.julclang.core.PlutusData;
import org.julclang.core.Program;
import org.julclang.core.Term;
import org.julclang.core.flat.UplcFlatEncoder;
import org.julclang.core.source.SourceLocation;
import org.julclang.vm.ProtocolCapability;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
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
 * <b>wrapper</b>: a variable bound exactly once to a lambda chain whose body is that builtin
 * applied, in order, to the chain's parameters and to constants ({@code NativeValueLib}'s
 * methods are exactly such wrappers; the producers {@code Builtins.emptyValue},
 * {@code singletonValue} and {@code lovelaceValue} are intrinsics that inline the constant or
 * the bare {@code InsertCoin} spine at the call site).
 *
 * <p>A literal call is replaced by its result exactly when the pinned semantics
 * ({@link NativeValueSemantics}, the same code the VM runs) succeed on the literals and the
 * FLAT encoding of the result is not larger than the encoding of the direct builtin
 * application it replaces. A call the semantics reject is left exactly as written, so its
 * runtime failure text and failure point are untouched; a call with a non-literal argument
 * (a runtime key, a trace, an error) is never touched. Folding is bottom-up, so nested literal
 * calls fold to a fixed point, and a local bound to a folded literal feeds the calls below it.
 * No algebraic identity is applied: {@code lookupCoin(p, t, empty())} with a runtime key stays
 * a call.
 *
 * <p>Soundness: every argument of a folded call is a value, so no evaluation, trace or failure
 * is skipped; the result is what the builtin would compute at runtime, by the same code; a
 * wrapper applied to values is a fixed number of beta steps around that builtin. NONE and
 * BASELINE never run the pass. Rule {@value #RULE} is recorded when a fold fires.
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
            collectBinders(rewritten);
            letValues.forEach((name, value) -> {
                if (binderCounts.get(name) != 1) return;
                var wrapper = wrapperOf(value);
                if (wrapper != null) wrappers.put(name, wrapper);
                if (value instanceof PirTerm.Const c) literals.put(name, c.value());
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
                // A local bound to a literal, or aliasing one (`JulcValue e = NativeValueLib.empty()`
                // binds e to the library's constant binding), is a literal below.
                var literal = literalOf(value);
                if (literal != null) literals.put(let.name(), literal);
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
        if (result == null || !fitsObjective(fun, constants, result)) return null;
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
     * The artifact objective: the FLAT encoding of the literal must not be larger than the
     * encoding of the direct builtin application it replaces (a Value-to-Value fold only
     * removes applications and constant headers; a Data conversion swaps CBOR-in-FLAT for
     * list structure and is measured). Alignment inside the whole artifact can move its size
     * by a byte either way around a fold.
     */
    private static boolean fitsObjective(DefaultFun fun, List<Constant> args, Constant result) {
        Term before = Term.builtin(fun);
        for (var arg : args) before = Term.apply(before, Term.const_(arg));
        int beforeBytes = UplcFlatEncoder.encodeProgram(Program.plutusV3(before)).length;
        int afterBytes = UplcFlatEncoder.encodeProgram(Program.plutusV3(Term.const_(result))).length;
        return afterBytes <= beforeBytes;
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
        for (var arg : spine.args()) {
            boolean ok = arg instanceof PirTerm.Const
                    || arg instanceof PirTerm.Var v && params.contains(v.name());
            if (!ok) return null;
        }
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
