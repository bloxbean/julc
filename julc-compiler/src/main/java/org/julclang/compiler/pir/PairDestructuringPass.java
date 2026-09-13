package org.julclang.compiler.pir;

import org.julclang.compiler.CompilationContext;
import org.julclang.compiler.CompilerTarget;
import org.julclang.core.DefaultFun;
import org.julclang.core.source.SourceLocation;
import org.julclang.vm.ProtocolCapability;

import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/** ADR-036: local projection-use proof for once-bound UnConstrData results. */
public final class PairDestructuringPass {
    private static final PirType.PairType CONSTRUCTOR_PAIR = new PirType.PairType(
            new PirType.IntegerType(), new PirType.ListType(new PirType.DataType()));

    private final CompilationContext context;
    private final IdentityHashMap<PirTerm, SourceLocation> positions = new IdentityHashMap<>();
    private final Set<String> names = new HashSet<>();
    private int nextName;

    public PairDestructuringPass(CompilationContext context, Map<PirTerm, SourceLocation> positions) {
        this.context = context;
        if (positions != null) this.positions.putAll(positions);
    }

    public record Result(PirTerm term, Map<PirTerm, SourceLocation> positions) {}

    public Result lower(PirTerm term) {
        if (!context.target().equals(CompilerTarget.PLUTUS_V3_PV11)
                || !context.optimizationLevel().pv11SafeRulesEnabled()
                || !context.supports(ProtocolCapability.CASE_ON_BUILTIN_CONSTANTS)) {
            return new Result(term, positions);
        }
        collectNames(term);
        return new Result(rewrite(term), positions);
    }

    private PirTerm rewrite(PirTerm term) {
        var rewritten = mapChildren(term, this::rewrite);
        if (!(rewritten instanceof PirTerm.Let let)
                || !(let.value() instanceof PirTerm.App app)
                || !(app.function() instanceof PirTerm.Builtin builtin)
                || builtin.fun() != DefaultFun.UnConstrData) return rewritten;

        var uses = new Uses();
        mapUses(let.body(), let.name(), use -> {
            if (use instanceof PirTerm.App projection
                    && CONSTRUCTOR_PAIR.equals(((PirTerm.Var) projection.argument()).type())) {
                if (((PirTerm.Builtin) projection.function()).fun() == DefaultFun.FstPair) uses.first = true;
                else uses.second = true;
            } else {
                uses.escapes = true;
            }
            return use;
        });
        if (uses.escapes || !uses.first || !uses.second) return rewritten;

        String first = fresh("first"), second = fresh("second");
        var body = mapUses(let.body(), let.name(), use -> {
            var projection = (PirTerm.App) use; // The preceding proof excludes every bare use.
            boolean isFirst = ((PirTerm.Builtin) projection.function()).fun() == DefaultFun.FstPair;
            return remember(use, new PirTerm.Var(isFirst ? first : second,
                    isFirst ? CONSTRUCTOR_PAIR.first() : CONSTRUCTOR_PAIR.second()));
        });
        return remember(term, new PirTerm.PairMatch(let.value(), CONSTRUCTOR_PAIR, first, second, body));
    }

    private static final class Uses {
        boolean first;
        boolean second;
        boolean escapes;
    }

    /** Visit only uses of this lexical binding; a direct projection is one atomic use. */
    private PirTerm mapUses(PirTerm term, String name, UnaryOperator<PirTerm> use) {
        if (term instanceof PirTerm.Var var && var.name().equals(name)) return use.apply(term);
        if (term instanceof PirTerm.App app && app.argument() instanceof PirTerm.Var var
                && var.name().equals(name) && app.function() instanceof PirTerm.Builtin builtin
                && (builtin.fun() == DefaultFun.FstPair || builtin.fun() == DefaultFun.SndPair)) {
            return use.apply(term);
        }
        var result = switch (term) {
            case PirTerm.Lam lam when lam.param().equals(name) -> term;
            case PirTerm.Let let when let.name().equals(name) ->
                    new PirTerm.Let(let.name(), mapUses(let.value(), name, use), let.body());
            case PirTerm.LetRec rec when rec.bindings().stream().anyMatch(b -> b.name().equals(name)) -> term;
            case PirTerm.ListMatch match -> new PirTerm.ListMatch(
                    mapUses(match.scrutinee(), name, use), match.headName(), match.tailName(),
                    mapUses(match.nilBranch(), name, use),
                    match.headName().equals(name) || match.tailName().equals(name) ? match.consBranch()
                            : mapUses(match.consBranch(), name, use));
            case PirTerm.PairMatch match -> new PirTerm.PairMatch(
                    mapUses(match.scrutinee(), name, use), match.pairType(), match.firstName(), match.secondName(),
                    match.firstName().equals(name) || match.secondName().equals(name) ? match.body()
                            : mapUses(match.body(), name, use));
            case PirTerm.DataMatch match -> new PirTerm.DataMatch(mapUses(match.scrutinee(), name, use),
                    match.branches().stream().map(b -> new PirTerm.MatchBranch(b.constructorName(), b.bindings(),
                            b.bindingTypes(), b.bindings().contains(name) || name.equals(b.patternVar()) ? b.body()
                                    : mapUses(b.body(), name, use), b.patternVar())).toList());
            default -> mapChildren(term, child -> mapUses(child, name, use));
        };
        return remember(term, result);
    }

    private String fresh(String component) {
        String name;
        do { name = "#pair-" + component + "-" + nextName++; } while (!names.add(name));
        return name;
    }

    private void collectNames(PirTerm term) {
        switch (term) {
            case PirTerm.Var v -> names.add(v.name());
            case PirTerm.Lam l -> names.add(l.param());
            case PirTerm.Let l -> names.add(l.name());
            case PirTerm.LetRec r -> r.bindings().forEach(b -> names.add(b.name()));
            case PirTerm.ListMatch m -> { names.add(m.headName()); names.add(m.tailName()); }
            case PirTerm.PairMatch m -> { names.add(m.firstName()); names.add(m.secondName()); }
            case PirTerm.DataMatch m -> m.branches().forEach(b -> {
                names.addAll(b.bindings());
                if (b.patternVar() != null) names.add(b.patternVar());
            });
            default -> { }
        }
        mapChildren(term, child -> { collectNames(child); return child; });
    }

    /** Preserve unchanged nodes and map replacements back to their original source locations. */
    private PirTerm remember(PirTerm original, PirTerm replacement) {
        if (original.equals(replacement)) return original;
        var location = positions.get(original);
        if (location != null) positions.put(replacement, location);
        return replacement;
    }

    private PirTerm mapChildren(PirTerm term, UnaryOperator<PirTerm> map) {
        var mapped = switch (term) {
            case PirTerm.Var _, PirTerm.Const _, PirTerm.Builtin _, PirTerm.Error _ -> term;
            case PirTerm.Lam l -> new PirTerm.Lam(l.param(), l.paramType(), map.apply(l.body()));
            case PirTerm.Let l -> new PirTerm.Let(l.name(), map.apply(l.value()), map.apply(l.body()));
            case PirTerm.LetRec r -> new PirTerm.LetRec(r.bindings().stream()
                    .map(b -> new PirTerm.Binding(b.name(), map.apply(b.value()))).toList(), map.apply(r.body()));
            case PirTerm.App a -> new PirTerm.App(map.apply(a.function()), map.apply(a.argument()));
            case PirTerm.IfThenElse i -> new PirTerm.IfThenElse(map.apply(i.cond()),
                    map.apply(i.thenBranch()), map.apply(i.elseBranch()));
            case PirTerm.Trace t -> new PirTerm.Trace(map.apply(t.message()), map.apply(t.body()));
            case PirTerm.DataConstr c -> new PirTerm.DataConstr(c.tag(), c.dataType(), c.fields().stream().map(map).toList());
            case PirTerm.DataMatch m -> new PirTerm.DataMatch(map.apply(m.scrutinee()), m.branches().stream()
                    .map(b -> new PirTerm.MatchBranch(b.constructorName(), b.bindings(), b.bindingTypes(),
                            map.apply(b.body()), b.patternVar())).toList());
            case PirTerm.ListMatch m -> new PirTerm.ListMatch(map.apply(m.scrutinee()), m.headName(), m.tailName(),
                    map.apply(m.nilBranch()), map.apply(m.consBranch()));
            case PirTerm.PairMatch m -> new PirTerm.PairMatch(map.apply(m.scrutinee()), m.pairType(),
                    m.firstName(), m.secondName(), map.apply(m.body()));
        };
        return remember(term, mapped);
    }
}
