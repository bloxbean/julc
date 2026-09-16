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
 * of the term it replaces, measured as it stands in the artifact: the builtin (or the wrapper
 * variable, at the smallest index) applied to the call-site arguments, where a constant
 * counts as itself and a literal local counts as a variable reference, not as the constant
 * its binding carries. A local bound directly to a literal term (a constant, or a domain's
 * literal shape) whose every remaining occurrence the call consumes dies with the fold (the
 * UPLC optimiser's dead-code elimination drops the binding), so its first occurrence in the
 * call counts as the constant the binding denotes; a local that stays
 * live elsewhere is only a reference here, and folding through it would copy its constant
 * into the call site. An alias ({@code Let w = Var v}) is never credited: its binding holds
 * a reference, and the constant it names stays as long as {@code v} has any other
 * occurrence, the alias binding itself included (ADR-045's second review found the guard
 * measuring every local as its constant, then crediting a dying alias with the constant it
 * names; either lets a shared local grow the artifact). A call the semantics reject is left
 * exactly as written, so its runtime failure
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
    /** Occurrences of each variable still standing in the term; a fold removes the ones it consumes. */
    private final Map<String, Integer> remainingUses = new HashMap<>();
    /**
     * Literals bound directly to a literal term (a constant, or a domain's literal shape such as a
     * list literal chain), not through an alias: the only bindings a fold may be credited with.
     */
    private final Set<String> creditableBindings = new HashSet<>();
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
            remainingUses.clear();
            creditableBindings.clear();
            collectBinders(rewritten);
            letValues.forEach((name, value) -> {
                if (binderCounts.get(name) != 1) return;
                var wrapper = wrapperOf(value);
                if (wrapper != null) wrappers.put(name, wrapper);
                if (value instanceof PirTerm.Const c) {
                    literals.put(name, c.value());
                    creditableBindings.add(name);
                }
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
                // A local bound to a literal, or aliasing one, is a literal below; only the
                // former may be credited to a fold that consumes its last occurrence.
                var literal = literalOf(value);
                if (literal != null) {
                    literals.put(let.name(), literal);
                    if (!(value instanceof PirTerm.Var)) creditableBindings.add(let.name());
                }
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
        // The term this fold replaces, as it stands in the artifact: the bare builtin, or for a
        // wrapper call the wrapper variable (the shape that stays when the wrapper remains live;
        // if the optimiser inlines the wrapper instead, the artifact loses its body as well, so
        // this is the conservative bound), applied to the call-site arguments. A constant
        // argument stands there as itself; a domain's further literal shapes (a list literal
        // chain) are measured structurally, each nested variable as a reference (see measure).
        // A literal local stands there as a variable reference, and its literal lives
        // once in its binding: when the local is bound directly to a literal term (not an
        // alias) and this call consumes every remaining occurrence of it, the binding dies with
        // the fold (the optimiser drops it: constants and the literal shapes are pure), so the
        // first occurrence is measured as the constant the binding denotes and any further
        // occurrence in the same call as a reference. A local that stays live elsewhere
        // is measured as a reference only, since folding would copy its constant into the call
        // site; so is an alias (`Let w = Var v`) whether or not it dies: its binding holds a
        // reference, and `v`'s constant stays as long as `v` has any other occurrence, the alias
        // binding itself included.
        var consumed = new HashMap<String, Integer>();
        for (var actual : spine.args()) countVariables(actual, consumed);
        Term replaced = spine.head() instanceof PirTerm.Builtin ? Term.builtin(fun) : Term.var(1);
        var credited = new HashSet<String>();
        for (var actual : spine.args()) replaced = Term.apply(replaced, measure(actual, consumed, credited));
        if (!fitsObjective(replaced, result)) return null;
        consumed.forEach((name, uses) -> remainingUses.merge(name, -uses, Integer::sum));
        return folded(result);
    }

    /** Variable occurrences in an argument as it stands; a domain's literal shape may nest them. */
    private static void countVariables(PirTerm term, Map<String, Integer> counts) {
        if (term instanceof PirTerm.Var v) counts.merge(v.name(), 1, Integer::sum);
        PirHelpers.mapChildren(term, child -> { countVariables(child, counts); return child; });
    }

    /**
     * A term whose FLAT encoding is not longer than the argument's own, as the argument stands
     * in the artifact: a constant as itself; a variable as a reference at the smallest index,
     * or, the first time this call meets a creditable local whose every remaining occurrence
     * the call consumes, as the constant its binding denotes (the binding dies with the fold);
     * a builtin as itself; an application as the application of its parts' measures; any other
     * node of a literal shape (the Bool encoding's conditional) as its children's measures
     * applied in sequence, which drops the node's own tags and so never measures long. A
     * shared local nested in a list literal is therefore a reference, not a copy of its
     * constant (ADR-046's review found `JulcArray.of(b, b)` over a live 256-byte `b` measured
     * as two copies and approved).
     */
    private Term measure(PirTerm term, Map<String, Integer> consumed, Set<String> credited) {
        return switch (term) {
            case PirTerm.Const c -> Term.const_(c.value());
            case PirTerm.Var v -> {
                boolean dies = creditableBindings.contains(v.name())
                        && consumed.get(v.name()).equals(remainingUses.getOrDefault(v.name(), 0));
                yield dies && credited.add(v.name()) ? Term.const_(literals.get(v.name())) : Term.var(1);
            }
            case PirTerm.Builtin b -> Term.builtin(b.fun());
            case PirTerm.App a -> Term.apply(measure(a.function(), consumed, credited), measure(a.argument(), consumed, credited));
            default -> {
                var children = new ArrayList<PirTerm>();
                PirHelpers.mapChildren(term, child -> { children.add(child); return child; });
                Term measured = null;
                for (var child : children) {
                    var part = measure(child, consumed, credited);
                    measured = measured == null ? part : Term.apply(measured, part);
                }
                yield measured == null ? Term.error() : measured;
            }
        };
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
    protected final PirTerm remember(PirTerm original, PirTerm replacement) {
        if (original.equals(replacement)) return original;
        var location = positions.get(original);
        if (location != null) positions.put(replacement, location);
        return replacement;
    }
}
