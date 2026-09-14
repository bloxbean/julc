package org.julclang.compiler.pir;

import org.julclang.compiler.CompilationContext;
import org.julclang.compiler.CompilerTarget;
import org.julclang.core.BuiltinSemantics;
import org.julclang.core.Constant;
import org.julclang.core.DefaultFun;
import org.julclang.core.Term;
import org.julclang.core.flat.FlatWriter;
import org.julclang.core.flat.UplcFlatEncoder;
import org.julclang.core.source.SourceLocation;
import org.julclang.vm.ProtocolCapability;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The shared machinery of the literal folds (ADR-045 O14 for native Values, ADR-046 O10 for
 * arrays): a domain of builtins with pinned semantics, folded at the safe profile on the PV11
 * target when every argument of a saturated call is a literal.
 *
 * <p>A <b>literal</b> is a {@link PirTerm.Const}, or a variable bound exactly once in the
 * program by a {@code Let} whose value is (or has just become) a literal; a domain may
 * recognise further literal shapes ({@link #literalOf}). A <b>literal call</b> is a saturated
 * application of a domain builtin to literals, spelled either as the bare builtin or through a
 * <b>wrapper</b>: a variable bound exactly once to a lambda chain, every parameter of which
 * occurs in the body, whose body is that builtin applied, by position, to the chain's
 * parameters and to constants. Every argument at a wrapper call site must be a literal, used
 * or not: the strict application evaluates them all.
 *
 * <p>A literal call is replaced by its result exactly when the domain's semantics succeed on
 * the literals and the FLAT encoding of the result is not longer, in bits, than the encoding
 * of the term it replaces (the builtin spine, or the wrapper variable applied to the call-site
 * literals). A call the semantics reject is left exactly as written, so its runtime failure
 * text and failure point are untouched; a call with a non-literal argument (a runtime value, a
 * trace, an error) is never touched. Folding is bottom-up and runs to a fixed point, so nested
 * literal calls fold and a local bound to a folded literal feeds the calls below it.
 *
 * <p>Soundness: every argument of a folded call is a value, so no evaluation, trace or failure
 * is skipped; the result is what the builtin would compute at runtime, by the same code; a
 * wrapper applied to values is a fixed number of beta steps around that builtin. NONE and
 * BASELINE never run a fold. The pass expects a closed term (every pipeline site hands it
 * one): a free variable that happens to share its name with a once-bound literal elsewhere
 * would be read as that literal.
 */
public abstract class LiteralFoldPass {

    /** A once-bound lambda chain whose body is a domain builtin over its parameters and constants. */
    private record Wrapper(DefaultFun fun, List<String> params, List<PirTerm> bodyArgs) {}

    protected record Spine(PirTerm head, List<PirTerm> args) {}

    protected final CompilationContext context;
    private final IdentityHashMap<PirTerm, SourceLocation> positions = new IdentityHashMap<>();
    private final Map<String, Integer> binderCounts = new HashMap<>();
    private final Map<String, PirTerm> letValues = new HashMap<>();
    private final Map<String, Wrapper> wrappers = new HashMap<>();
    private final Map<String, Constant> literals = new HashMap<>();
    private boolean applied;

    LiteralFoldPass(CompilationContext context, Map<PirTerm, SourceLocation> positions) {
        this.context = context;
        if (positions != null) this.positions.putAll(positions);
    }

    public record Result(PirTerm term, Map<PirTerm, SourceLocation> positions) {}

    /** The rule id recorded when a fold fires. */
    protected abstract String rule();

    /** The protocol capability the domain's constants need. */
    protected abstract ProtocolCapability capability();

    /** The builtins of the domain. */
    protected abstract Set<DefaultFun> builtins();

    /**
     * The pinned semantics on literals; null when they reject the call (it then stays as
     * written) or when a literal has the wrong kind.
     */
    protected abstract Constant evaluate(DefaultFun fun, List<Constant> args);

    public Result lower(PirTerm term) {
        if (!context.target().equals(CompilerTarget.PLUTUS_V3_PV11)
                || !context.optimizationLevel().pv11SafeRulesEnabled()
                || !context.supports(capability())
                || !context.ruleEnabled(rule())) {
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
        if (applied) context.recordOptimizationRule(rule());
        return new Result(rewritten, positions);
    }

    /** Bottom-up: fold children, register a local that became a literal, then try the node. */
    private PirTerm fold(PirTerm term) {
        if (term instanceof PirTerm.Let let) {
            var value = fold(let.value());
            if (binderCounts.get(let.name()) == 1) {
                // A local bound to a literal, or aliasing one, is a literal below.
                var literal = literalOf(value);
                if (literal != null) literals.put(let.name(), literal);
            }
            var body = fold(let.body());
            return remember(term, new PirTerm.Let(let.name(), value, body));
        }
        var mapped = remember(term, PirHelpers.mapChildren(term, this::fold));
        var folded = foldNode(mapped);
        return folded == null ? mapped : remember(mapped, folded);
    }

    /** The replacement for a node whose children are folded, or null; domains may add cases. */
    protected PirTerm foldNode(PirTerm mapped) {
        return foldLiteralCall(mapped);
    }

    /** The literal a saturated literal call evaluates to, or null when it is not one or must stay. */
    protected final PirTerm foldLiteralCall(PirTerm term) {
        var spine = spineOf(term);
        if (spine == null) return null;
        DefaultFun fun;
        List<PirTerm> args;
        if (spine.head() instanceof PirTerm.Builtin builtin && builtins().contains(builtin.fun())) {
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
        // The term this fold replaces: the bare builtin spine, or for a wrapper call the
        // application of the wrapper variable to the call-site literals (the shape that stays
        // in the artifact when the wrapper remains live; if the optimiser inlines the wrapper
        // instead, the artifact loses its body as well, so this is the conservative bound).
        Term replaced = spine.head() instanceof PirTerm.Builtin ? Term.builtin(fun) : Term.var(1);
        for (var actual : spine.args()) replaced = Term.apply(replaced, Term.const_(literalOf(actual)));
        if (!fitsObjective(replaced, result)) return null;
        return folded(result);
    }

    /** A folded constant: marks the rule as applied and lets a domain track the node. */
    protected PirTerm.Const folded(Constant result) {
        applied = true;
        return new PirTerm.Const(result);
    }

    /** The literal {@code term} denotes, or null: a constant, or a once-bound literal local. */
    protected Constant literalOf(PirTerm term) {
        return switch (term) {
            case PirTerm.Const c -> c.value();
            case PirTerm.Var v -> literals.get(v.name());
            default -> null;
        };
    }

    /**
     * The artifact objective: the FLAT encoding of the literal must not be longer, in bits,
     * than the encoding of the term it replaces. Bits, not bytes, so that a sequence of folds
     * cannot grow the artifact through rounding; the whole artifact's final padding can still
     * differ by up to seven bits.
     */
    protected static boolean fitsObjective(Term replaced, Constant result) {
        return bitLength(Term.const_(result)) <= bitLength(replaced);
    }

    /** The FLAT bit length of a term on its own (no program header, no padding). */
    static int bitLength(Term term) {
        var writer = new FlatWriter();
        new UplcFlatEncoder(writer).writeTerm(term);
        return writer.bitLength();
    }

    /** A lambda chain whose body is a saturated domain builtin over all its parameters and constants. */
    private Wrapper wrapperOf(PirTerm value) {
        var params = new ArrayList<String>();
        PirTerm body = value;
        while (body instanceof PirTerm.Lam lam) {
            if (params.contains(lam.param())) return null;
            params.add(lam.param());
            body = lam.body();
        }
        var spine = spineOf(body);
        if (spine == null || !(spine.head() instanceof PirTerm.Builtin builtin)
                || !builtins().contains(builtin.fun())) {
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

    protected static Spine spineOf(PirTerm term) {
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

    protected static boolean isBuiltin(PirTerm term, DefaultFun fun) {
        return term instanceof PirTerm.Builtin builtin && builtin.fun() == fun;
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
    protected final PirTerm remember(PirTerm original, PirTerm replacement) {
        if (original.equals(replacement)) return original;
        var location = positions.get(original);
        if (location != null) positions.put(replacement, location);
        return replacement;
    }
}
