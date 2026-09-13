package org.julclang.compiler.pir;

import org.julclang.compiler.CompilationContext;
import org.julclang.compiler.CompilerTarget;
import org.julclang.core.DefaultFun;
import org.julclang.core.source.SourceLocation;
import org.julclang.vm.ProtocolCapability;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * ADR-043 (O9): promote a repeatedly indexed list variable to a PV11 array.
 *
 * <p>{@code JulcList.get} lowers per site to the recursive traversal
 * {@link PirHelpers#recursiveListGet} (O(index) {@code TailList} steps). For a variable
 * {@code xs} bound by a {@code Let}, a lambda parameter or a match field, whose scope indexes
 * that binding either at two or more sites or at a site inside a recursive binding (a loop
 * body or a recursive helper), the pass binds {@code a = ListToArray(xs)} once and rewrites
 * every such site to {@code IndexArray(a, index)}; the element decoding around the site and every
 * other use of {@code xs} (for-each, size, head, tail, passing it on) are untouched. The array
 * binding is placed at the innermost sub-term that contains every rewritten site and is
 * evaluated at most once per evaluation of the binder's scope (never inside a lambda body or a
 * recursive binding), so a path through the scope that indexes nothing pays nothing where the
 * sites sit behind a branch it does not take.
 *
 * <p>{@code ListToArray} is total on a list and pure, so its placement changes only the budget;
 * the result of each rewritten site is the same element. Out-of-range indexes fail at the same
 * semantic point (after the index is evaluated, before anything else) but with the array
 * builtin's text instead of {@code HeadList: empty list} / {@code TailList: empty list}; this is
 * the ADR-043 failure contract. Only a binding proven to hold a list is promoted: a {@code Let}
 * whose value is a list by construction ({@link #producesList}, under the environment of
 * proven names at its binder, so an alias of an unproven variable stays unproven), a list-typed
 * parameter of a method lambda (never of a callback lambda, which the list operations apply to
 * raw Data elements) or a list-typed match field. Method parameters and list-typed call returns
 * are trusted, which javac guarantees unless a caller casts a non-list {@code PlutusData} to
 * {@code JulcList} through {@code Object} and passes it on, or carries it through a loop as
 * the loop's state. Such a value fails at the conversion on every path below the binding,
 * including paths that never index (ADR-043 "Typing trust").
 *
 * <p>Gate: exact PV11 target, a level with {@link org.julclang.compiler.OptimizationLevel#pv11CostedRulesEnabled()},
 * {@link ProtocolCapability#ARRAY_CONSTANTS} and both array builtins available. The break-even
 * (ADR-043) depends on list length and index values known only at run time, so the rule is a
 * costed-profile decision: NONE, BASELINE and PV11_SAFE keep their bytes.
 */
public final class ListIndexPromotionPass {
    public static final String RULE = "pv11.o9.list-to-array";

    private final CompilationContext context;
    private final IdentityHashMap<PirTerm, SourceLocation> positions = new IdentityHashMap<>();
    private final Set<String> names = new HashSet<>();
    private int nextName;
    private boolean applied;

    public ListIndexPromotionPass(CompilationContext context, Map<PirTerm, SourceLocation> positions) {
        this.context = context;
        if (positions != null) this.positions.putAll(positions);
    }

    public record Result(PirTerm term, Map<PirTerm, SourceLocation> positions) {}

    public Result lower(PirTerm term) {
        if (!enabled()) return new Result(term, positions);
        collectNames(term);
        var rewritten = rewrite(term, Set.of(), true);
        if (applied) context.recordOptimizationRule(RULE);
        return new Result(rewritten, positions);
    }

    private boolean enabled() {
        if (!context.target().equals(CompilerTarget.PLUTUS_V3_PV11)
                || !context.optimizationLevel().pv11CostedRulesEnabled()
                || !context.supports(ProtocolCapability.ARRAY_CONSTANTS)
                || !context.ruleEnabled(RULE)) {
            return false;
        }
        var profile = context.resolvedTarget().featureProfile();
        return profile.isBuiltinAvailable(DefaultFun.ListToArray)
                && profile.isBuiltinAvailable(DefaultFun.IndexArray);
    }

    /**
     * Pre-order over binders: promote each proven list binding in its own scope, then descend.
     *
     * <p>{@code proven} is the set of variables in scope that hold a UPLC list by construction:
     * list-typed parameters of method lambdas and list-typed match fields (trusted; a match
     * field of list type is decoded with {@code unListData} by the generator), list-match tails,
     * and every {@code Let} whose value {@link #producesList produces a list} under the
     * environment at its binder. Whether a variable is a list is decided by how it was bound,
     * never by the type its uses carry: a cast from Data to JulcList lowers to the Data-typed
     * inner term, so the local it binds is unproven, and so is every alias of it, including the
     * {@code let xs = xs} the loop lowering emits after a loop with the declared list type. An
     * unproven binding is left alone because converting it on a path that never indexes would
     * turn a success into a failure. Any binder that is not a list by construction shadows the
     * name out of the set.
     *
     * <p>{@code chain} is true while the walk is still on a method's parameter chain: the root
     * term's leading lambdas (a compiled method, the validator wrapper) and the leading lambdas
     * of every {@code Let}- or {@code LetRec}-bound value (the frontend binds every method,
     * helper, library method and loop that way and passes lambda expressions straight to the
     * call that consumes them), through the decode lets between them. Only such lambdas have
     * list-typed parameters that hold lists: a callback lambda (an application argument,
     * applied by a list operation to its raw Data elements) does not, so its parameters are
     * never trusted whatever type they carry. A lambda applied on the spot
     * ({@code [(lam x body) arg]}) binds {@code x} exactly as {@code let x = arg} would, so
     * {@code x} is proven if and only if the argument produces a list.
     */
    private PirTerm rewrite(PirTerm term, Set<String> proven, boolean chain) {
        var result = switch (term) {
            case PirTerm.Let let -> {
                boolean proves = producesList(let.value(), proven);
                yield new PirTerm.Let(let.name(), rewrite(let.value(), proven, true),
                        rewrite(proves ? promote(let.body(), let.name()) : let.body(),
                                proves ? with(proven, let.name()) : without(proven, let.name()), chain));
            }
            case PirTerm.Lam lam -> {
                boolean list = chain && lam.paramType() instanceof PirType.ListType;
                yield new PirTerm.Lam(lam.param(), lam.paramType(),
                        rewrite(list ? promote(lam.body(), lam.param()) : lam.body(),
                                list ? with(proven, lam.param()) : without(proven, lam.param()), chain));
            }
            case PirTerm.App app when redexHead(app) != null -> {
                var spine = new ArrayList<PirTerm.App>();   // outermost application first
                PirTerm head = app;
                while (head instanceof PirTerm.App node) { spine.add(node); head = node.function(); }
                var arguments = new ArrayList<PirTerm>(spine.reversed().stream().map(PirTerm.App::argument).toList());
                PirTerm rebuilt = rewriteRedex(head, arguments, 0, proven, proven);
                for (int i = spine.size() - 1; i >= 0; i--) {
                    var node = spine.get(i);
                    rebuilt = remember(node, new PirTerm.App(rebuilt, rewrite(node.argument(), proven, false)));
                }
                yield rebuilt;
            }
            case PirTerm.LetRec rec -> {
                var inner = without(proven, rec.bindings().stream().map(PirTerm.Binding::name).toList());
                yield new PirTerm.LetRec(rec.bindings().stream()
                        .map(b -> new PirTerm.Binding(b.name(), rewrite(b.value(), inner, true))).toList(),
                        rewrite(rec.body(), inner, chain));
            }
            case PirTerm.DataMatch match -> new PirTerm.DataMatch(rewrite(match.scrutinee(), proven, false),
                    match.branches().stream().map(branch -> {
                        var body = branch.body();
                        var inner = proven;
                        for (int i = 0; i < branch.bindings().size(); i++) {
                            String field = branch.bindings().get(i);
                            if (branch.bindingTypes().get(i) instanceof PirType.ListType) {
                                body = promote(body, field);
                                inner = with(inner, field);
                            } else {
                                inner = without(inner, field);
                            }
                        }
                        if (branch.patternVar() != null) inner = without(inner, branch.patternVar());
                        return new PirTerm.MatchBranch(branch.constructorName(), branch.bindings(),
                                branch.bindingTypes(), rewrite(body, inner, false), branch.patternVar());
                    }).toList());
            case PirTerm.ListMatch match -> new PirTerm.ListMatch(rewrite(match.scrutinee(), proven, false),
                    match.headName(), match.tailName(), rewrite(match.nilBranch(), proven, false),
                    rewrite(match.consBranch(), with(without(proven, match.headName()), match.tailName()), false));
            case PirTerm.PairMatch match -> new PirTerm.PairMatch(rewrite(match.scrutinee(), proven, false),
                    match.pairType(), match.firstName(), match.secondName(),
                    rewrite(match.body(), without(proven, List.of(match.firstName(), match.secondName())), false));
            default -> PirHelpers.mapChildren(term, child -> rewrite(child, proven, false));
        };
        return remember(term, result);
    }

    /** The lambda at the head of an application spine, or null when the head is not a lambda. */
    private static PirTerm.Lam redexHead(PirTerm.App app) {
        PirTerm head = app;
        while (head instanceof PirTerm.App spine) head = spine.function();
        return head instanceof PirTerm.Lam lam ? lam : null;
    }

    /**
     * The head of a redex, its leading lambdas bound to the spine's arguments in order: each
     * parameter is proven exactly when its argument (evaluated in the {@code outer} environment)
     * produces a list, as a {@code Let} of that argument would be; lambdas left over after the
     * arguments (a partial application) are a function value and trust nothing.
     */
    private PirTerm rewriteRedex(PirTerm function, List<PirTerm> arguments, int index, Set<String> proven, Set<String> outer) {
        if (index < arguments.size() && function instanceof PirTerm.Lam lam) {
            boolean proves = producesList(arguments.get(index), outer);
            var body = proves ? promote(lam.body(), lam.param()) : lam.body();
            var inner = proves ? with(proven, lam.param()) : without(proven, lam.param());
            return remember(function, new PirTerm.Lam(lam.param(), lam.paramType(),
                    rewriteRedex(body, arguments, index + 1, inner, outer)));
        }
        return rewrite(function, proven, false);
    }

    private static Set<String> with(Set<String> proven, String name) {
        if (proven.contains(name)) return proven;
        var extended = new HashSet<>(proven);
        extended.add(name);
        return extended;
    }

    private static Set<String> without(Set<String> proven, String name) {
        return without(proven, List.of(name));
    }

    private static Set<String> without(Set<String> proven, List<String> names) {
        if (names.stream().noneMatch(proven::contains)) return proven;
        var reduced = new HashSet<>(proven);
        names.forEach(reduced::remove);
        return reduced;
    }

    /**
     * Whether a term evaluates to a UPLC list by construction under {@code proven}: a proven
     * variable, a decode or list builtin, a call whose return type is a list, or a compound term
     * whose result position is one. A bare Data-typed term (the lowering of a cast to
     * {@code JulcList}) is not, and neither is a variable outside {@code proven}, whatever type
     * its uses carry: an alias of an unproven variable is unproven.
     */
    static boolean producesList(PirTerm term, Set<String> proven) {
        return switch (term) {
            case PirTerm.Var v -> proven.contains(v.name());
            case PirTerm.App app -> {
                PirTerm head = app;
                int arguments = 0;
                while (head instanceof PirTerm.App spine) { head = spine.function(); arguments++; }
                if (head instanceof PirTerm.Builtin builtin) {
                    yield switch (builtin.fun()) {
                        case UnListData, TailList, MkCons, MkNilData, DropList -> true;
                        default -> false;
                    };
                }
                if (head instanceof PirTerm.Var function) {
                    PirType type = function.type();
                    for (int i = 0; i < arguments; i++) {
                        if (!(type instanceof PirType.FunType fun)) yield false;
                        type = fun.returnType();
                    }
                    yield type instanceof PirType.ListType;
                }
                yield producesList(head, proven) && arguments == 0;
            }
            case PirTerm.Let let -> producesList(let.body(),
                    producesList(let.value(), proven) ? with(proven, let.name()) : without(proven, let.name()));
            case PirTerm.LetRec rec -> producesList(rec.body(),
                    without(proven, rec.bindings().stream().map(PirTerm.Binding::name).toList()));
            case PirTerm.IfThenElse ite -> (ite.thenBranch() instanceof PirTerm.Error || producesList(ite.thenBranch(), proven))
                    && (ite.elseBranch() instanceof PirTerm.Error || producesList(ite.elseBranch(), proven));
            case PirTerm.Trace trace -> producesList(trace.body(), proven);
            case PirTerm.ListMatch match -> {
                var cons = with(without(proven, match.headName()), match.tailName());
                yield (match.nilBranch() instanceof PirTerm.Error || producesList(match.nilBranch(), proven))
                        && (match.consBranch() instanceof PirTerm.Error || producesList(match.consBranch(), cons));
            }
            case PirTerm.PairMatch match -> producesList(match.body(),
                    without(proven, List.of(match.firstName(), match.secondName())));
            case PirTerm.DataMatch match -> match.branches().stream().allMatch(b -> {
                if (b.body() instanceof PirTerm.Error) return true;
                var inner = proven;
                for (int i = 0; i < b.bindings().size(); i++) {
                    inner = b.bindingTypes().get(i) instanceof PirType.ListType
                            ? with(inner, b.bindings().get(i)) : without(inner, b.bindings().get(i));
                }
                if (b.patternVar() != null) inner = without(inner, b.patternVar());
                return producesList(b.body(), inner);
            });
            case PirTerm.IntegerCase c -> c.branches().stream()
                    .allMatch(b -> b instanceof PirTerm.Error || producesList(b, proven));
            case PirTerm.Const _, PirTerm.Builtin _, PirTerm.Lam _, PirTerm.Error _, PirTerm.DataConstr _ -> false;
        };
    }

    /**
     * {@code let x = x in …}, as the loop lowering emits after a loop for every pre-loop
     * variable used afterwards: the same value under the same name, so sites below it belong
     * to the outer binding and the array binding may sit below it.
     */
    private static boolean isSelfAlias(PirTerm.Let let) {
        return let.value() instanceof PirTerm.Var v && v.name().equals(let.name());
    }

    /** Sites of this binding of {@code list} in its scope. */
    private static final class Sites {
        int count;
        boolean underRecursion;
        PirTerm.Var list;
    }

    private PirTerm promote(PirTerm scope, String list) {
        var sites = new Sites();
        collectSites(scope, list, false, sites);
        if (sites.count == 0 || (sites.count < 2 && !sites.underRecursion)) return scope;
        String array = fresh();
        var arrayType = new PirType.ArrayType(sites.list.type() instanceof PirType.ListType lt
                ? lt.elemType() : new PirType.DataType());
        var replaced = replaceSites(scope, list, array, arrayType);
        applied = true;
        return place(replaced, sites.list, array, countUses(replaced, array), true);
    }

    /**
     * Insert {@code let array = ListToArray(list)} at the innermost sub-term that holds every
     * use of {@code array} and is evaluated at most once per evaluation of the binder's scope.
     * Recursive binding values are never entered. Lambda bodies are entered only while
     * {@code chain} holds, i.e. through the parameter chain that directly follows the binder
     * (curried method parameters, the validator wrapper's parameter and decode lets), which
     * JuLC-generated code always applies in full and once; any other lambda (a callback passed
     * to a list operation) may run any number of times, so the binding stays above it. The
     * scope of a binder that rebinds {@code list} is never entered; since {@link #replaceSites}
     * leaves such scopes opaque they cannot hold uses of {@code array}, so those guards are
     * defensive and unreachable, kept so the invariant is explicit at every descent.
     */
    private PirTerm place(PirTerm term, PirTerm.Var list, String array, int total, boolean chain) {
        String name = list.name();
        var result = switch (term) {
            case PirTerm.Lam lam when chain && !lam.param().equals(name) && countUses(lam.body(), array) == total ->
                    new PirTerm.Lam(lam.param(), lam.paramType(), place(lam.body(), list, array, total, true));
            case PirTerm.Let let when countUses(let.value(), array) == total ->
                    new PirTerm.Let(let.name(), place(let.value(), list, array, total, false), let.body());
            case PirTerm.Let let when (!let.name().equals(name) || isSelfAlias(let)) && countUses(let.body(), array) == total ->
                    new PirTerm.Let(let.name(), let.value(), place(let.body(), list, array, total, chain));
            case PirTerm.App app when countUses(app.function(), array) == total ->
                    new PirTerm.App(place(app.function(), list, array, total, false), app.argument());
            case PirTerm.App app when countUses(app.argument(), array) == total ->
                    new PirTerm.App(app.function(), place(app.argument(), list, array, total, false));
            case PirTerm.IfThenElse ite when countUses(ite.cond(), array) == total ->
                    new PirTerm.IfThenElse(place(ite.cond(), list, array, total, false), ite.thenBranch(), ite.elseBranch());
            case PirTerm.IfThenElse ite when countUses(ite.thenBranch(), array) == total ->
                    new PirTerm.IfThenElse(ite.cond(), place(ite.thenBranch(), list, array, total, false), ite.elseBranch());
            case PirTerm.IfThenElse ite when countUses(ite.elseBranch(), array) == total ->
                    new PirTerm.IfThenElse(ite.cond(), ite.thenBranch(), place(ite.elseBranch(), list, array, total, false));
            case PirTerm.Trace trace when countUses(trace.message(), array) == total ->
                    new PirTerm.Trace(place(trace.message(), list, array, total, false), trace.body());
            case PirTerm.Trace trace when countUses(trace.body(), array) == total ->
                    new PirTerm.Trace(trace.message(), place(trace.body(), list, array, total, false));
            case PirTerm.LetRec rec when rec.bindings().stream().noneMatch(b -> b.name().equals(name))
                    && countUses(rec.body(), array) == total ->
                    new PirTerm.LetRec(rec.bindings(), place(rec.body(), list, array, total, false));
            case PirTerm.DataMatch match when countUses(match.scrutinee(), array) == total ->
                    new PirTerm.DataMatch(place(match.scrutinee(), list, array, total, false), match.branches());
            case PirTerm.DataMatch match -> {
                var branches = match.branches();
                for (int i = 0; i < branches.size(); i++) {
                    var branch = branches.get(i);
                    if (branch.bindings().contains(name) || name.equals(branch.patternVar())) continue;
                    if (countUses(branch.body(), array) != total) continue;
                    var rebuilt = new ArrayList<>(branches);
                    rebuilt.set(i, new PirTerm.MatchBranch(branch.constructorName(), branch.bindings(),
                            branch.bindingTypes(), place(branch.body(), list, array, total, false), branch.patternVar()));
                    yield new PirTerm.DataMatch(match.scrutinee(), rebuilt);
                }
                yield bind(term, list, array);
            }
            case PirTerm.ListMatch match when countUses(match.scrutinee(), array) == total ->
                    new PirTerm.ListMatch(place(match.scrutinee(), list, array, total, false), match.headName(),
                            match.tailName(), match.nilBranch(), match.consBranch());
            case PirTerm.ListMatch match when countUses(match.nilBranch(), array) == total ->
                    new PirTerm.ListMatch(match.scrutinee(), match.headName(), match.tailName(),
                            place(match.nilBranch(), list, array, total, false), match.consBranch());
            case PirTerm.ListMatch match when !match.headName().equals(name) && !match.tailName().equals(name)
                    && countUses(match.consBranch(), array) == total ->
                    new PirTerm.ListMatch(match.scrutinee(), match.headName(), match.tailName(),
                            match.nilBranch(), place(match.consBranch(), list, array, total, false));
            case PirTerm.PairMatch match when countUses(match.scrutinee(), array) == total ->
                    new PirTerm.PairMatch(place(match.scrutinee(), list, array, total, false), match.pairType(),
                            match.firstName(), match.secondName(), match.body());
            case PirTerm.PairMatch match when !match.firstName().equals(name) && !match.secondName().equals(name)
                    && countUses(match.body(), array) == total ->
                    new PirTerm.PairMatch(match.scrutinee(), match.pairType(), match.firstName(),
                            match.secondName(), place(match.body(), list, array, total, false));
            case PirTerm.IntegerCase c when countUses(c.scrutinee(), array) == total ->
                    new PirTerm.IntegerCase(place(c.scrutinee(), list, array, total, false), c.branches());
            case PirTerm.IntegerCase c -> {
                var branches = c.branches();
                for (int i = 0; i < branches.size(); i++) {
                    if (countUses(branches.get(i), array) != total) continue;
                    var rebuilt = new ArrayList<>(branches);
                    rebuilt.set(i, place(branches.get(i), list, array, total, false));
                    yield new PirTerm.IntegerCase(c.scrutinee(), rebuilt);
                }
                yield bind(term, list, array);
            }
            case PirTerm.DataConstr constr -> {
                var fields = constr.fields();
                for (int i = 0; i < fields.size(); i++) {
                    if (countUses(fields.get(i), array) != total) continue;
                    var rebuilt = new ArrayList<>(fields);
                    rebuilt.set(i, place(fields.get(i), list, array, total, false));
                    yield new PirTerm.DataConstr(constr.tag(), constr.dataType(), rebuilt);
                }
                yield bind(term, list, array);
            }
            // A callback lambda may run any number of times; a bare use, constant or error is
            // the tightest scope there is.
            default -> bind(term, list, array);
        };
        return remember(term, result);
    }

    private PirTerm bind(PirTerm body, PirTerm.Var list, String array) {
        return new PirTerm.Let(array, new PirTerm.App(new PirTerm.Builtin(DefaultFun.ListToArray), list), body);
    }

    /** The list variable indexed by {@code term} when it is exactly the recursive {@code get} shape. */
    public static PirTerm.Var indexedList(PirTerm term) {
        if (term instanceof PirTerm.LetRec rec
                && rec.bindings().equals(List.of(PirHelpers.RECURSIVE_LIST_GET))
                && rec.body() instanceof PirTerm.App outer
                && outer.function() instanceof PirTerm.App inner
                && inner.function() instanceof PirTerm.Var go && go.name().equals(PirHelpers.RECURSIVE_LIST_GET.name())
                && inner.argument() instanceof PirTerm.Var list) {
            return list;
        }
        return null;
    }

    private static PirTerm indexOf(PirTerm site) {
        return ((PirTerm.App) ((PirTerm.LetRec) site).body()).argument();
    }

    private void collectSites(PirTerm term, String list, boolean underRecursion, Sites sites) {
        var indexed = indexedList(term);
        if (indexed != null && indexed.name().equals(list)) {
            sites.count++;
            sites.underRecursion |= underRecursion;
            if (sites.list == null) sites.list = indexed;
            collectSites(indexOf(term), list, underRecursion, sites);
            return;
        }
        switch (term) {
            case PirTerm.Lam lam when lam.param().equals(list) -> { }
            case PirTerm.Let let when let.name().equals(list) && !isSelfAlias(let) ->
                    collectSites(let.value(), list, underRecursion, sites);
            case PirTerm.LetRec rec -> {
                if (rec.bindings().stream().anyMatch(b -> b.name().equals(list))) return;
                rec.bindings().forEach(b -> collectSites(b.value(), list, true, sites));
                collectSites(rec.body(), list, underRecursion, sites);
            }
            case PirTerm.ListMatch match -> {
                collectSites(match.scrutinee(), list, underRecursion, sites);
                collectSites(match.nilBranch(), list, underRecursion, sites);
                if (!match.headName().equals(list) && !match.tailName().equals(list)) {
                    collectSites(match.consBranch(), list, underRecursion, sites);
                }
            }
            case PirTerm.PairMatch match -> {
                collectSites(match.scrutinee(), list, underRecursion, sites);
                if (!match.firstName().equals(list) && !match.secondName().equals(list)) {
                    collectSites(match.body(), list, underRecursion, sites);
                }
            }
            case PirTerm.DataMatch match -> {
                collectSites(match.scrutinee(), list, underRecursion, sites);
                match.branches().forEach(b -> {
                    if (!b.bindings().contains(list) && !list.equals(b.patternVar())) {
                        collectSites(b.body(), list, underRecursion, sites);
                    }
                });
            }
            default -> PirHelpers.mapChildren(term, child -> { collectSites(child, list, underRecursion, sites); return child; });
        }
    }

    /** Rewrite every site of this binding of {@code list} to {@code IndexArray(array, index)}; rebindings are opaque. */
    private PirTerm replaceSites(PirTerm term, String list, String array, PirType arrayType) {
        var indexed = indexedList(term);
        if (indexed != null && indexed.name().equals(list)) {
            var index = replaceSites(indexOf(term), list, array, arrayType);
            return remember(term, PirHelpers.builtinApp2(DefaultFun.IndexArray, new PirTerm.Var(array, arrayType), index));
        }
        UnaryOperator<PirTerm> recurse = child -> replaceSites(child, list, array, arrayType);
        var result = switch (term) {
            case PirTerm.Lam lam when lam.param().equals(list) -> term;
            case PirTerm.Let let when let.name().equals(list) && !isSelfAlias(let) ->
                    new PirTerm.Let(let.name(), recurse.apply(let.value()), let.body());
            case PirTerm.LetRec rec when rec.bindings().stream().anyMatch(b -> b.name().equals(list)) -> term;
            case PirTerm.ListMatch match -> new PirTerm.ListMatch(recurse.apply(match.scrutinee()),
                    match.headName(), match.tailName(), recurse.apply(match.nilBranch()),
                    match.headName().equals(list) || match.tailName().equals(list)
                            ? match.consBranch() : recurse.apply(match.consBranch()));
            case PirTerm.PairMatch match -> new PirTerm.PairMatch(recurse.apply(match.scrutinee()), match.pairType(),
                    match.firstName(), match.secondName(),
                    match.firstName().equals(list) || match.secondName().equals(list)
                            ? match.body() : recurse.apply(match.body()));
            case PirTerm.DataMatch match -> new PirTerm.DataMatch(recurse.apply(match.scrutinee()),
                    match.branches().stream().map(b -> new PirTerm.MatchBranch(b.constructorName(), b.bindings(),
                            b.bindingTypes(), b.bindings().contains(list) || list.equals(b.patternVar())
                                    ? b.body() : recurse.apply(b.body()), b.patternVar())).toList());
            default -> PirHelpers.mapChildren(term, recurse);
        };
        return remember(term, result);
    }

    /** Occurrences of the fresh array variable (never shadowed, so a plain count). */
    private static int countUses(PirTerm term, String array) {
        if (term instanceof PirTerm.Var var) return var.name().equals(array) ? 1 : 0;
        int[] count = {0};
        PirHelpers.mapChildren(term, child -> { count[0] += countUses(child, array); return child; });
        return count[0];
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
            case PirTerm.IntegerCase _, PirTerm.App _, PirTerm.Const _, PirTerm.Builtin _, PirTerm.Error _,
                 PirTerm.IfThenElse _, PirTerm.Trace _, PirTerm.DataConstr _ -> { }
        }
        PirHelpers.mapChildren(term, child -> { collectNames(child); return child; });
    }

    private String fresh() {
        String name;
        do { name = "#array-" + nextName++; } while (!names.add(name));
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
