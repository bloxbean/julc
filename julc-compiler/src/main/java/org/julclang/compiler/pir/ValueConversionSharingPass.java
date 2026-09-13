package org.julclang.compiler.pir;

import org.julclang.compiler.CompilationContext;
import org.julclang.compiler.CompilerTarget;
import org.julclang.core.BuiltinSemantics;
import org.julclang.core.DefaultFun;
import org.julclang.core.source.SourceLocation;
import org.julclang.vm.ProtocolCapability;

import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * ADR-042 (O8): share one strict native Value conversion of a variable across a scope.
 *
 * <p>A conversion of {@code x} is {@code [(builtin unValueData) x]} or {@code [w x]} where
 * {@code w} is a once-bound library wrapper of exactly that shape ({@code NativeValueLib.fromData}).
 * For a scope {@code S} in which the conversion of {@code x} occurs at least twice without
 * {@code x} being rebound, the pass emits {@code let v = C(x) in S[C(x) := v]} exactly when
 * {@code C(x)} is the first non-trivial evaluation on every path through {@code S}. The shared
 * conversion therefore ran first on every path already: no result, trace, failure point or
 * failure text changes, only the budget of the later occurrences and the script size. A path
 * that reaches no later occurrence (an untaken branch, an empty loop) pays the binding alone:
 * one lambda, one application and one variable lookup.
 *
 * <p>Trivial evaluations are variable lookups, constants, lambdas, and under-saturated builtin
 * or once-bound {@code Let} lambda applications whose arguments are trivial. None of these can
 * fail, trace or run user code before the conversion: the CEK machine collects builtin
 * arguments unchecked until saturation, and applying a lambda chain to fewer arguments than
 * its depth only builds a closure. Every other node (saturated calls, traces, errors, matches,
 * recursive bindings, non-trivial conditions) blocks the proof.
 *
 * <p>Gate: exact PV11 target, a safe optimization level, and
 * {@link ProtocolCapability#VALUE_CONSTANTS}. Rule provenance {@link #RULE}. NONE and BASELINE
 * keep their historical bytes.
 */
public final class ValueConversionSharingPass {
    public static final String RULE = "pv11.o8.value-sharing";
    private static final PirType NATIVE_VALUE = new PirType.NativeValueType();

    private final CompilationContext context;
    private final IdentityHashMap<PirTerm, SourceLocation> positions = new IdentityHashMap<>();
    private final Set<String> names = new HashSet<>();
    private final Map<String, Integer> binderCounts = new HashMap<>();
    private final Map<String, PirTerm> letValues = new LinkedHashMap<>();
    /** Lambda depth of every once-bound {@code Let} whose value is a lambda chain. */
    private final Map<String, Integer> lambdaDepths = new HashMap<>();
    /** Once-bound wrappers of the shape {@code (lam p [(builtin unValueData) p])}. */
    private final Set<String> aliases = new HashSet<>();
    /** Variables converted at least twice anywhere in the program, in first-occurrence order. */
    private final Set<String> candidates = new LinkedHashSet<>();
    private int nextName;
    private boolean applied;

    public ValueConversionSharingPass(CompilationContext context, Map<PirTerm, SourceLocation> positions) {
        this.context = context;
        if (positions != null) this.positions.putAll(positions);
    }

    public record Result(PirTerm term, Map<PirTerm, SourceLocation> positions) {}

    public Result lower(PirTerm term) {
        if (!context.target().equals(CompilerTarget.PLUTUS_V3_PV11)
                || !context.optimizationLevel().pv11SafeRulesEnabled()
                || !context.supports(ProtocolCapability.VALUE_CONSTANTS)) {
            return new Result(term, positions);
        }
        collectBinders(term);
        letValues.forEach((name, value) -> {
            if (binderCounts.get(name) != 1) return;
            int depth = lambdaDepth(value);
            if (depth > 0) lambdaDepths.put(name, depth);
            if (isConversionWrapper(value)) aliases.add(name);
        });
        var conversionSites = new LinkedHashMap<String, Integer>();
        collectConversionSites(term, conversionSites);
        conversionSites.forEach((name, count) -> { if (count >= 2) candidates.add(name); });
        if (candidates.isEmpty()) return new Result(term, positions);

        var rewritten = rewrite(term);
        if (applied) context.recordOptimizationRule(RULE);
        return new Result(rewritten, positions);
    }

    /** Pre-order: share at the outermost scope whose first non-trivial evaluation is the conversion. */
    private PirTerm rewrite(PirTerm term) {
        for (var variable : candidates) {
            var lead = leadingConversion(term, variable);
            if (lead == null || countConversions(term, variable) < 2) continue;
            String shared = fresh();
            var body = mapConversions(term, variable, use -> remember(use, new PirTerm.Var(shared, NATIVE_VALUE)));
            applied = true;
            // The inserted value is non-trivial, so a later candidate can only be shared below it:
            // conversion order between variables is preserved.
            return remember(term, new PirTerm.Let(shared, lead, rewrite(body)));
        }
        return remember(term, PirHelpers.mapChildren(term, this::rewrite));
    }

    /**
     * The unique conversion of {@code variable} that every path through {@code term} evaluates
     * before any other non-trivial step, or null. Function position evaluates before argument
     * position; a {@code Let} value before its body; a condition or scrutinee before its
     * branches. Branch bodies, lambdas, traces, errors, recursive bindings and constructor
     * builds never lead.
     */
    private PirTerm leadingConversion(PirTerm term, String variable) {
        if (isConversion(term, variable)) return term;
        return switch (term) {
            case PirTerm.App app -> {
                var inFunction = leadingConversion(app.function(), variable);
                if (inFunction != null) yield inFunction;
                yield isTrivial(app.function()) ? leadingConversion(app.argument(), variable) : null;
            }
            case PirTerm.Let let -> {
                var inValue = leadingConversion(let.value(), variable);
                if (inValue != null) yield inValue;
                // The shared binding is inserted above this Let, so the Let must not bind a free
                // variable of the conversion: neither the variable itself nor a wrapper alias
                // (a wrapper-spelled conversion [w x] is free in both x and w).
                yield isTrivial(let.value()) && !let.name().equals(variable) && !aliases.contains(let.name())
                        ? leadingConversion(let.body(), variable) : null;
            }
            // Branches are exclusive: a conversion leading in both arms of a conditional would be
            // evaluated once per path either way, so sharing it above the conditional only adds
            // a binding. Only the condition (or a scrutinee) can lead.
            case PirTerm.IfThenElse ite -> leadingConversion(ite.cond(), variable);
            case PirTerm.DataMatch match -> leadingConversion(match.scrutinee(), variable);
            case PirTerm.ListMatch match -> leadingConversion(match.scrutinee(), variable);
            case PirTerm.PairMatch match -> leadingConversion(match.scrutinee(), variable);
            case PirTerm.IntegerCase c -> leadingConversion(c.scrutinee(), variable);
            case PirTerm.Var _, PirTerm.Const _, PirTerm.Builtin _, PirTerm.Lam _, PirTerm.LetRec _,
                 PirTerm.Trace _, PirTerm.Error _, PirTerm.DataConstr _ -> null;
        };
    }

    /** Evaluation that cannot fail, trace, or run user code. */
    private boolean isTrivial(PirTerm term) {
        return switch (term) {
            case PirTerm.Var _, PirTerm.Const _, PirTerm.Builtin _, PirTerm.Lam _ -> true;
            case PirTerm.App app -> isUnsaturatedSpine(app);
            default -> false;
        };
    }

    /** A builtin or once-bound lambda chain applied to fewer trivial arguments than its arity. */
    private boolean isUnsaturatedSpine(PirTerm.App app) {
        int arguments = 0;
        PirTerm head = app;
        while (head instanceof PirTerm.App spine) {
            if (!isTrivial(spine.argument())) return false;
            arguments++;
            head = spine.function();
        }
        Integer arity = switch (head) {
            case PirTerm.Builtin builtin -> {
                var sig = BuiltinSemantics.find(builtin.fun());
                yield sig == null ? null : sig.valueArity();
            }
            case PirTerm.Var var -> lambdaDepths.get(var.name());
            default -> null;
        };
        return arity != null && arguments < arity;
    }

    private boolean isConversion(PirTerm term, String variable) {
        return term instanceof PirTerm.App app
                && app.argument() instanceof PirTerm.Var argument && argument.name().equals(variable)
                && (app.function() instanceof PirTerm.Builtin builtin && builtin.fun() == DefaultFun.UnValueData
                    || app.function() instanceof PirTerm.Var function && aliases.contains(function.name()));
    }

    private static boolean isConversionWrapper(PirTerm value) {
        return value instanceof PirTerm.Lam lam
                && lam.body() instanceof PirTerm.App app
                && app.function() instanceof PirTerm.Builtin builtin && builtin.fun() == DefaultFun.UnValueData
                && app.argument() instanceof PirTerm.Var argument && argument.name().equals(lam.param());
    }

    private static int lambdaDepth(PirTerm value) {
        int depth = 0;
        while (value instanceof PirTerm.Lam lam) { depth++; value = lam.body(); }
        return depth;
    }

    private int countConversions(PirTerm term, String variable) {
        int[] count = {0};
        mapConversions(term, variable, use -> { count[0]++; return use; });
        return count[0];
    }

    /** Visit every conversion of this lexical binding of {@code variable}; rebindings are opaque. */
    private PirTerm mapConversions(PirTerm term, String variable, UnaryOperator<PirTerm> use) {
        if (isConversion(term, variable)) return use.apply(term);
        var result = switch (term) {
            case PirTerm.Lam lam when lam.param().equals(variable) -> term;
            case PirTerm.Let let when let.name().equals(variable) ->
                    new PirTerm.Let(let.name(), mapConversions(let.value(), variable, use), let.body());
            case PirTerm.LetRec rec when rec.bindings().stream().anyMatch(b -> b.name().equals(variable)) -> term;
            case PirTerm.ListMatch match -> new PirTerm.ListMatch(
                    mapConversions(match.scrutinee(), variable, use), match.headName(), match.tailName(),
                    mapConversions(match.nilBranch(), variable, use),
                    match.headName().equals(variable) || match.tailName().equals(variable) ? match.consBranch()
                            : mapConversions(match.consBranch(), variable, use));
            case PirTerm.PairMatch match -> new PirTerm.PairMatch(
                    mapConversions(match.scrutinee(), variable, use), match.pairType(), match.firstName(),
                    match.secondName(), match.firstName().equals(variable) || match.secondName().equals(variable)
                            ? match.body() : mapConversions(match.body(), variable, use));
            case PirTerm.DataMatch match -> new PirTerm.DataMatch(mapConversions(match.scrutinee(), variable, use),
                    match.branches().stream().map(b -> new PirTerm.MatchBranch(b.constructorName(), b.bindings(),
                            b.bindingTypes(), b.bindings().contains(variable) || variable.equals(b.patternVar())
                                    ? b.body() : mapConversions(b.body(), variable, use), b.patternVar())).toList());
            default -> PirHelpers.mapChildren(term, child -> mapConversions(child, variable, use));
        };
        return remember(term, result);
    }

    private void collectConversionSites(PirTerm term, Map<String, Integer> sites) {
        if (term instanceof PirTerm.App app && app.argument() instanceof PirTerm.Var argument
                && isConversion(term, argument.name())) {
            sites.merge(argument.name(), 1, Integer::sum);
        }
        PirHelpers.mapChildren(term, child -> { collectConversionSites(child, sites); return child; });
    }

    private void collectBinders(PirTerm term) {
        switch (term) {
            case PirTerm.Var v -> names.add(v.name());
            case PirTerm.Lam l -> bind(l.param());
            case PirTerm.Let l -> { bind(l.name()); letValues.putIfAbsent(l.name(), l.value()); }
            case PirTerm.LetRec r -> r.bindings().forEach(b -> bind(b.name()));
            case PirTerm.ListMatch m -> { bind(m.headName()); bind(m.tailName()); }
            case PirTerm.PairMatch m -> { bind(m.firstName()); bind(m.secondName()); }
            case PirTerm.DataMatch m -> m.branches().forEach(b -> {
                b.bindings().forEach(this::bind);
                if (b.patternVar() != null) bind(b.patternVar());
            });
            case PirTerm.IntegerCase _, PirTerm.App _, PirTerm.Const _, PirTerm.Builtin _, PirTerm.Error _,
                 PirTerm.IfThenElse _, PirTerm.Trace _, PirTerm.DataConstr _ -> { }
        }
        PirHelpers.mapChildren(term, child -> { collectBinders(child); return child; });
    }

    private void bind(String name) {
        names.add(name);
        binderCounts.merge(name, 1, Integer::sum);
    }

    private String fresh() {
        String name;
        do { name = "#value-" + nextName++; } while (!names.add(name));
        return name;
    }

    /** Preserve unchanged nodes and map replacements back to their original source locations. */
    private PirTerm remember(PirTerm original, PirTerm replacement) {
        if (original.equals(replacement)) return original;
        var location = positions.get(original);
        if (location != null) positions.put(replacement, location);
        return replacement;
    }
}
