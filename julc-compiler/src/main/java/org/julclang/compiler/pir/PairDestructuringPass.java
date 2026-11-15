package org.julclang.compiler.pir;

import org.julclang.compiler.CompilationContext;
import org.julclang.compiler.CompilerTarget;
import org.julclang.compiler.debug.PirDebugProvenance;
import org.julclang.core.DefaultFun;
import org.julclang.core.recursion.Trampoline;
import org.julclang.core.source.SourceLocation;
import org.julclang.vm.ProtocolCapability;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/** ADR-036: local projection-use proof for once-bound UnConstrData results. */
public final class PairDestructuringPass {
    private static final PirType.PairType CONSTRUCTOR_PAIR = new PirType.PairType(
            new PirType.IntegerType(), new PirType.ListType(new PirType.DataType()));

    private final CompilationContext context;
    private final IdentityHashMap<PirTerm, SourceLocation> positions = new IdentityHashMap<>();
    private final Set<String> names = new HashSet<>();
    private int nextName;
    private final PirDebugProvenance debugProvenance;

    public PairDestructuringPass(CompilationContext context, Map<PirTerm, SourceLocation> positions) {
        this(context, positions, null);
    }

    public PairDestructuringPass(CompilationContext context, Map<PirTerm, SourceLocation> positions,
                                 PirDebugProvenance debugProvenance) {
        this.context = context;
        this.debugProvenance = debugProvenance;
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
        return new Result(rewrite(term).run(), positions);
    }

    /** Every walk here is stack-safe (issue #327, ADR-074): a term nests as deeply as a program is long. */
    private Trampoline<PirTerm> rewrite(PirTerm term) {
        return Trampoline.defer(() -> mapChildren(term, this::rewrite).map(rewritten -> destructure(term, rewritten)));
    }

    /** {@code rewritten}, the rewrite of {@code term}, with its {@code UnConstrData} binding destructured where both fields are read. */
    private PirTerm destructure(PirTerm term, PirTerm rewritten) {
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
        }).run();
        if (uses.escapes || !uses.first || !uses.second) return rewritten;

        String first = fresh("first"), second = fresh("second");
        var body = mapUses(let.body(), let.name(), use -> {
            var projection = (PirTerm.App) use; // The preceding proof excludes every bare use.
            boolean isFirst = ((PirTerm.Builtin) projection.function()).fun() == DefaultFun.FstPair;
            return remember(use, new PirTerm.Var(isFirst ? first : second,
                    isFirst ? CONSTRUCTOR_PAIR.first() : CONSTRUCTOR_PAIR.second()));
        }).run();
        return remember(term, new PirTerm.PairMatch(let.value(), CONSTRUCTOR_PAIR, first, second, body));
    }

    private static final class Uses {
        boolean first;
        boolean second;
        boolean escapes;
    }

    /** Visit only uses of this lexical binding; a direct projection is one atomic use. */
    private Trampoline<PirTerm> mapUses(PirTerm term, String name, UnaryOperator<PirTerm> use) {
        return Trampoline.defer(() -> {
            if (term instanceof PirTerm.Var var && var.name().equals(name)) return Trampoline.done(use.apply(term));
            if (term instanceof PirTerm.App app && app.argument() instanceof PirTerm.Var var
                    && var.name().equals(name) && app.function() instanceof PirTerm.Builtin builtin
                    && (builtin.fun() == DefaultFun.FstPair || builtin.fun() == DefaultFun.SndPair)) {
                return Trampoline.done(use.apply(term));
            }
            Trampoline<PirTerm> result = switch (term) {
                case PirTerm.Lam lam when lam.param().equals(name) -> Trampoline.done(term);
                case PirTerm.Let let when let.name().equals(name) -> mapUses(let.value(), name, use)
                        .map(value -> new PirTerm.Let(let.name(), value, let.body()));
                case PirTerm.LetRec rec when rec.bindings().stream().anyMatch(b -> b.name().equals(name)) ->
                        Trampoline.done(term);
                case PirTerm.ListMatch match -> mapUses(match.scrutinee(), name, use).then(scrutinee ->
                        mapUses(match.nilBranch(), name, use).then(nil ->
                                (match.headName().equals(name) || match.tailName().equals(name)
                                        ? Trampoline.done(match.consBranch())
                                        : mapUses(match.consBranch(), name, use))
                                        .map(cons -> new PirTerm.ListMatch(scrutinee, match.headName(), match.tailName(),
                                                nil, cons))));
                case PirTerm.IntegerCase c -> mapUses(c.scrutinee(), name, use).then(scrutinee ->
                        Trampoline.each(c.branches(), b -> mapUses(b, name, use))
                                .map(branches -> new PirTerm.IntegerCase(scrutinee, branches)));
                case PirTerm.PairMatch match -> mapUses(match.scrutinee(), name, use).then(scrutinee ->
                        (match.firstName().equals(name) || match.secondName().equals(name)
                                ? Trampoline.done(match.body())
                                : mapUses(match.body(), name, use))
                                .map(body -> new PirTerm.PairMatch(scrutinee, match.pairType(), match.firstName(),
                                        match.secondName(), body)));
                case PirTerm.DataMatch match -> mapUses(match.scrutinee(), name, use).then(scrutinee ->
                        Trampoline.each(match.branches(), b ->
                                        (b.bindings().contains(name) || name.equals(b.patternVar())
                                                ? Trampoline.done(b.body())
                                                : mapUses(b.body(), name, use))
                                                .map(body -> new PirTerm.MatchBranch(b.constructorName(), b.bindings(),
                                                        b.bindingTypes(), body, b.patternVar())))
                                .map(branches -> new PirTerm.DataMatch(scrutinee, branches)));
                default -> mapChildren(term, child -> mapUses(child, name, use));
            };
            return result.map(mapped -> remember(term, mapped));
        });
    }

    private String fresh(String component) {
        String name;
        do { name = "#pair-" + component + "-" + nextName++; } while (!names.add(name));
        return name;
    }

    /** Every name the term binds or reads: a work list, not recursion. */
    private void collectNames(PirTerm root) {
        var pending = new ArrayDeque<PirTerm>();
        pending.push(root);
        while (!pending.isEmpty()) {
            var term = pending.pop();
            switch (term) {
                case PirTerm.Var v -> names.add(v.name());
                case PirTerm.Lam l -> names.add(l.param());
                case PirTerm.Let l -> names.add(l.name());
                case PirTerm.LetRec r -> r.bindings().forEach(b -> names.add(b.name()));
                case PirTerm.ListMatch m -> { names.add(m.headName()); names.add(m.tailName()); }
                case PirTerm.IntegerCase _ -> { }
                case PirTerm.PairMatch m -> { names.add(m.firstName()); names.add(m.secondName()); }
                case PirTerm.DataMatch m -> m.branches().forEach(b -> {
                    names.addAll(b.bindings());
                    if (b.patternVar() != null) names.add(b.patternVar());
                });
                default -> { }
            }
            var children = PirHelpers.children(term);
            for (int i = children.size() - 1; i >= 0; i--) pending.push(children.get(i));
        }
    }

    /** Preserve unchanged nodes and map replacements back to their original source locations. */
    private PirTerm remember(PirTerm original, PirTerm replacement) {
        if (original.equals(replacement)) return original;
        var location = positions.get(original);
        if (location != null) positions.put(replacement, location);
        if (debugProvenance != null) debugProvenance.transfer(original, replacement, "pv11.o4.case-pair");
        return replacement;
    }

    private Trampoline<PirTerm> mapChildren(PirTerm term, Function<PirTerm, Trampoline<PirTerm>> map) {
        return PirHelpers.traverseChildren(term, map).map(children -> remember(term, children));
    }
}
