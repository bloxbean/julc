package org.julclang.compiler.pir;

import org.julclang.compiler.CompilationContext;
import org.julclang.compiler.CompilerTarget;
import org.julclang.core.DefaultFun;
import org.julclang.core.source.SourceLocation;
import org.julclang.vm.ProtocolCapability;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
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
 * the ADR-043 failure contract. Only a binding proven to hold a list is promoted, and whether
 * a binding holds a list is decided by how it was bound, never by the type its uses carry: a
 * {@code Let} whose value is a list by construction ({@link #producesList}, under the
 * environment of proven names at its binder, so an alias of an unproven variable stays
 * unproven), a list-typed match field, a list-match tail, a list-typed parameter of the root
 * term's own lambdas (the program's boundary with its caller), or a list-typed parameter of a
 * method that every call site in the program passes a proven list ({@link Method}: the
 * frontend binds every method, helper, library method and loop as a {@code Let}- or
 * {@code LetRec}-bound lambda chain, and the pass carries provenance across those boundaries
 * in both directions, so a parameter fed from a callback's raw Data element, from a cast local
 * or from a loop's unproven state is never trusted, and a list-typed call result is proven only
 * when the method's body produces a list under that parameter environment). A callback
 * lambda's parameter is never trusted: the list operations apply callbacks to raw Data
 * elements whatever the parameter's type says.
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
    private final Map<MethodKey, Method> methods = new HashMap<>();
    private int nextName;
    private boolean applied;
    private boolean analysing;
    private boolean refuted;

    public ListIndexPromotionPass(CompilationContext context, Map<PirTerm, SourceLocation> positions) {
        this.context = context;
        if (positions != null) this.positions.putAll(positions);
    }

    public record Result(PirTerm term, Map<PirTerm, SourceLocation> positions) {}

    public Result lower(PirTerm term) {
        if (!enabled()) return new Result(term, positions);
        collectNames(term);
        var root = new Method(chain(term));
        analyse(term, root);
        var rewritten = rewrite(term, Map.of(), new Chain(root, 0));
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

    // --- provenance: what a name in scope is known to hold ---

    /**
     * What the pass knows about a name in scope. A name absent from the environment is bound
     * (by a binder the pass cannot vouch for, or not at all) but holds nothing it can use.
     */
    private sealed interface Binding {
        /** A variable that holds a UPLC list by construction. */
        record Proven() implements Binding {}

        /** A method: the name of a {@code Let}- or {@code LetRec}-bound lambda chain. */
        record Callable(Method method) implements Binding {}
    }

    private static final Binding PROVEN = new Binding.Proven();

    /**
     * The provenance of a method (a {@code Let}- or {@code LetRec}-bound lambda chain, or the
     * root term's chain). Parameter {@code i} is proven while it is list-typed and every call
     * site in the program passes it a term that {@link #producesList produces a list}; a call
     * that supplies fewer arguments, or a use of the method's name that is not a call at all
     * (the method escaping as a value), refutes every parameter it leaves unbound. The result is
     * proven while the chain's body produces a list under that parameter environment. Both
     * start optimistic and are refuted until nothing changes: a greatest fixpoint, so a
     * recursive helper or loop that passes its own list parameter (or its tail) back to itself
     * keeps the proof that its first call established, and a value can only ever reach a
     * parameter through a finite chain of calls each of which the fixpoint has checked. The
     * root term's parameters are its contract with the caller and are never refuted. Two
     * binders with the same name and parameter names share one record, so their facts are the
     * conservative intersection.
     */
    private static final class Method {
        final boolean[] proven;
        boolean returns = true;

        Method(List<PirTerm.Lam> chain) {
            proven = new boolean[chain.size()];
            for (int i = 0; i < proven.length; i++) proven[i] = chain.get(i).paramType() instanceof PirType.ListType;
        }

        /**
         * A lambda applied on the spot: each parameter is proven exactly when its argument is.
         * Its result is never followed, so nothing about it can change between iterations.
         */
        Method(boolean[] arguments) {
            proven = arguments;
            returns = false;
        }

        /** A binder met after the analysis that the analysis never saw: trusts nothing. */
        static Method untrusted(List<PirTerm.Lam> chain) {
            var method = new Method(chain);
            Arrays.fill(method.proven, false);
            method.returns = false;
            return method;
        }

        boolean trusts(int index) {
            return index < proven.length && proven[index];
        }
    }

    private record MethodKey(String name, List<String> params) {}

    /**
     * A position on a parameter chain: the next lambda met binds parameter {@code index} of
     * {@code method}. A chain is walked through the lets and recursive bindings between its
     * lambdas (the validator wrapper's decode lets and method bindings sit between the
     * handler's parameters); a lambda beyond the chain's parameters (a partial application)
     * is a function value and trusts nothing.
     */
    private record Chain(Method method, int index) {
        Chain next() { return new Chain(method, index + 1); }

        boolean trusts() { return method.trusts(index); }
    }

    /**
     * The chain of a lambda applied on the spot ({@code [[(lam x (lam y body)) a] b]}, the
     * lambdas possibly under lets): each parameter is bound exactly as {@code let x = a} would
     * bind it, so it is proven if and only if its argument (evaluated in the environment of the
     * application) produces a list.
     */
    private Chain redex(List<PirTerm> arguments, Map<String, Binding> env) {
        var proven = new boolean[arguments.size()];
        for (int i = 0; i < proven.length; i++) proven[i] = producesList(arguments.get(i), env);
        return new Chain(new Method(proven), 0);
    }

    /**
     * The leading lambdas of a bound value: the parameters a call applies, in order, read
     * through the lets between them (the validator wrapper's decode lets, the array bindings
     * this pass inserts) and through recursive-binding bodies. Empty when the value is not a
     * function. Stable under this pass's own rewriting, so the rewrite finds the analysis's
     * record for a binder whose scope has already been promoted.
     */
    private static List<PirTerm.Lam> chain(PirTerm value) {
        var lams = new ArrayList<PirTerm.Lam>();
        PirTerm term = value;
        while (true) {
            switch (term) {
                case PirTerm.Lam lam -> { lams.add(lam); term = lam.body(); }
                case PirTerm.Let let -> term = let.body();
                case PirTerm.LetRec rec -> term = rec.body();
                default -> { return lams; }
            }
        }
    }

    /** The method record of a bound value, or null when the value is not a function. */
    private Method methodOf(String name, PirTerm value) {
        var chain = chain(value);
        if (chain.isEmpty()) return null;
        var key = new MethodKey(name, chain.stream().map(PirTerm.Lam::param).toList());
        var method = methods.get(key);
        if (method == null) {
            if (!analysing) return Method.untrusted(chain);
            method = new Method(chain);
            methods.put(key, method);
            return method;
        }
        for (int i = 0; i < chain.size(); i++) {
            if (!(chain.get(i).paramType() instanceof PirType.ListType)) refute(method, i);
        }
        return method;
    }

    private void refute(Method method, int index) {
        if (method.proven[index]) {
            method.proven[index] = false;
            refuted = true;
        }
    }

    private static Map<String, Binding> bind(Map<String, Binding> env, String name, Binding binding) {
        if (binding.equals(env.get(name))) return env;
        var extended = new HashMap<>(env);
        extended.put(name, binding);
        return extended;
    }

    private static Map<String, Binding> unbind(Map<String, Binding> env, String name) {
        return unbind(env, List.of(name));
    }

    private static Map<String, Binding> unbind(Map<String, Binding> env, List<String> names) {
        if (names.stream().noneMatch(env::containsKey)) return env;
        var reduced = new HashMap<>(env);
        names.forEach(reduced::remove);
        return reduced;
    }

    /** The scope of a {@code Let}: its method record, else its proof, else nothing. */
    private static Map<String, Binding> bindLet(Map<String, Binding> env, String name, Method method, boolean proves) {
        if (method != null) return bind(env, name, new Binding.Callable(method));
        return proves ? bind(env, name, PROVEN) : unbind(env, name);
    }

    /** The scope of a {@code LetRec}: every function-valued binding is a method, every other binding is opaque. */
    private Map<String, Binding> bindRec(Map<String, Binding> env, PirTerm.LetRec rec) {
        var inner = env;
        for (var binding : rec.bindings()) {
            var method = methodOf(binding.name(), binding.value());
            inner = method != null ? bind(inner, binding.name(), new Binding.Callable(method)) : unbind(inner, binding.name());
        }
        return inner;
    }

    private static Chain chainOf(Map<String, Binding> env, String name) {
        return env.get(name) instanceof Binding.Callable callable ? new Chain(callable.method(), 0) : null;
    }

    /** A match branch: a list-typed field is decoded with {@code unListData} by the generator; every other binder is opaque. */
    private static Map<String, Binding> matchEnv(Map<String, Binding> env, PirTerm.MatchBranch branch) {
        var inner = env;
        for (int i = 0; i < branch.bindings().size(); i++) {
            String field = branch.bindings().get(i);
            inner = branch.bindingTypes().get(i) instanceof PirType.ListType ? bind(inner, field, PROVEN) : unbind(inner, field);
        }
        return branch.patternVar() != null ? unbind(inner, branch.patternVar()) : inner;
    }

    /** The cons branch of a list match: the tail is a list, the head is opaque. */
    private static Map<String, Binding> consEnv(Map<String, Binding> env, PirTerm.ListMatch match) {
        return bind(unbind(env, match.headName()), match.tailName(), PROVEN);
    }

    // --- analysis: refute method provenance until nothing changes ---

    private void analyse(PirTerm term, Method root) {
        analysing = true;
        do {
            refuted = false;
            visit(term, Map.of(), new Chain(root, 0));
        } while (refuted);
        analysing = false;
    }

    /**
     * One pass over the term with the same binder discipline as {@link #rewrite}: every call
     * of a method constrains the parameters it binds, every non-call use of a method's name
     * constrains them all, and the chain's body (the first sub-term that is not a lambda, a let
     * or a recursive binding) decides its result.
     */
    private void visit(PirTerm term, Map<String, Binding> env, Chain chain) {
        var here = chain;
        if (here != null && !(term instanceof PirTerm.Lam) && !(term instanceof PirTerm.Let) && !(term instanceof PirTerm.LetRec)) {
            if (here.method().returns && !producesList(term, env)) {
                here.method().returns = false;
                refuted = true;
            }
            here = null;
        }
        var next = here;
        switch (term) {
            case PirTerm.Lam lam -> {
                boolean list = next != null && next.trusts();
                visit(lam.body(), list ? bind(env, lam.param(), PROVEN) : unbind(env, lam.param()),
                        next == null ? null : next.next());
            }
            case PirTerm.Let let -> {
                var method = methodOf(let.name(), let.value());
                visit(let.value(), env, method == null ? null : new Chain(method, 0));
                visit(let.body(), bindLet(env, let.name(), method, method == null && producesList(let.value(), env)), next);
            }
            case PirTerm.LetRec rec -> {
                var inner = bindRec(env, rec);
                rec.bindings().forEach(b -> visit(b.value(), inner, chainOf(inner, b.name())));
                visit(rec.body(), inner, next);
            }
            case PirTerm.App app -> {
                var arguments = new ArrayList<PirTerm>();
                PirTerm head = app;
                while (head instanceof PirTerm.App spine) { arguments.add(spine.argument()); head = spine.function(); }
                var ordered = arguments.reversed();
                if (head instanceof PirTerm.Var function) {
                    if (env.get(function.name()) instanceof Binding.Callable callable) constrain(callable.method(), ordered, env);
                } else {
                    visit(head, env, chain(head).isEmpty() ? null : redex(ordered, env));
                }
                ordered.forEach(argument -> visit(argument, env, null));
            }
            case PirTerm.Var v -> {
                if (env.get(v.name()) instanceof Binding.Callable callable) constrain(callable.method(), List.of(), env);
            }
            case PirTerm.DataMatch match -> {
                visit(match.scrutinee(), env, null);
                match.branches().forEach(branch -> visit(branch.body(), matchEnv(env, branch), null));
            }
            case PirTerm.ListMatch match -> {
                visit(match.scrutinee(), env, null);
                visit(match.nilBranch(), env, null);
                visit(match.consBranch(), consEnv(env, match), null);
            }
            case PirTerm.PairMatch match -> {
                visit(match.scrutinee(), env, null);
                visit(match.body(), unbind(env, List.of(match.firstName(), match.secondName())), null);
            }
            default -> PirHelpers.mapChildren(term, child -> { visit(child, env, null); return child; });
        }
    }

    /** A call with these arguments (evaluated in {@code env}) refutes every parameter it does not feed a proven list. */
    private void constrain(Method method, List<PirTerm> arguments, Map<String, Binding> env) {
        for (int i = 0; i < method.proven.length; i++) {
            if (method.proven[i] && (i >= arguments.size() || !producesList(arguments.get(i), env))) refute(method, i);
        }
    }

    // --- rewrite: promote every proven binding in its own scope ---

    /**
     * Pre-order over binders: promote each proven list binding in its own scope, then descend.
     *
     * <p>{@code env} is the lexical environment: the variables in scope that hold a UPLC list by
     * construction (a list-typed match field, which the generator decodes with
     * {@code unListData}; a list-match tail; every {@code Let} whose value
     * {@link #producesList produces a list} under the environment at its binder; a parameter
     * the analysis proved) and the methods in scope with their provenance. Whether a variable
     * is a list is decided by how it was bound, never by the type its uses carry: a cast from
     * Data to JulcList lowers to the Data-typed inner term, so the local it binds is unproven,
     * and so is every alias of it, including the {@code let xs = xs} the loop lowering emits
     * after a loop with the declared list type. An unproven binding is left alone because
     * converting it on a path that never indexes would turn a success into a failure. Any
     * binder that is not a list by construction shadows the name out of the environment.
     *
     * <p>{@code chain} is the position on a parameter chain while the walk is still on one: the
     * root term's leading lambdas (a compiled method, the validator wrapper), the leading
     * lambdas of every {@code Let}- or {@code LetRec}-bound value, and the leading lambdas of a
     * lambda applied on the spot (the validator's handler), in every case through the lets and
     * recursive bindings between them. A lambda met there binds the parameter the chain names
     * and is trusted exactly when the analysis proved it (a method) or its argument produces a
     * list (a redex, bound as {@code let x = arg} would bind it); a lambda met anywhere else (a
     * callback in argument position, applied by a list operation to its raw Data elements) is
     * never trusted whatever type it carries.
     */
    private PirTerm rewrite(PirTerm term, Map<String, Binding> env, Chain chain) {
        var result = switch (term) {
            case PirTerm.Let let -> {
                var method = methodOf(let.name(), let.value());
                boolean proves = method == null && producesList(let.value(), env);
                yield new PirTerm.Let(let.name(), rewrite(let.value(), env, method == null ? null : new Chain(method, 0)),
                        rewrite(proves ? promote(let.body(), let.name()) : let.body(),
                                bindLet(env, let.name(), method, proves), chain));
            }
            case PirTerm.Lam lam -> {
                boolean list = chain != null && chain.trusts();
                yield new PirTerm.Lam(lam.param(), lam.paramType(),
                        rewrite(list ? promote(lam.body(), lam.param()) : lam.body(),
                                list ? bind(env, lam.param(), PROVEN) : unbind(env, lam.param()),
                                chain == null ? null : chain.next()));
            }
            case PirTerm.App app when appliedChain(app) != null -> {
                var spine = new ArrayList<PirTerm.App>();   // outermost application first
                PirTerm head = app;
                while (head instanceof PirTerm.App node) { spine.add(node); head = node.function(); }
                var arguments = new ArrayList<PirTerm>(spine.reversed().stream().map(PirTerm.App::argument).toList());
                PirTerm rebuilt = rewrite(head, env, redex(arguments, env));
                for (int i = spine.size() - 1; i >= 0; i--) {
                    var node = spine.get(i);
                    rebuilt = remember(node, new PirTerm.App(rebuilt, rewrite(node.argument(), env, null)));
                }
                yield rebuilt;
            }
            case PirTerm.LetRec rec -> {
                var inner = bindRec(env, rec);
                yield new PirTerm.LetRec(rec.bindings().stream()
                        .map(b -> new PirTerm.Binding(b.name(), rewrite(b.value(), inner, chainOf(inner, b.name())))).toList(),
                        rewrite(rec.body(), inner, chain));
            }
            case PirTerm.DataMatch match -> new PirTerm.DataMatch(rewrite(match.scrutinee(), env, null),
                    match.branches().stream().map(branch -> {
                        var body = branch.body();
                        for (int i = 0; i < branch.bindings().size(); i++) {
                            if (branch.bindingTypes().get(i) instanceof PirType.ListType) {
                                body = promote(body, branch.bindings().get(i));
                            }
                        }
                        return new PirTerm.MatchBranch(branch.constructorName(), branch.bindings(),
                                branch.bindingTypes(), rewrite(body, matchEnv(env, branch), null), branch.patternVar());
                    }).toList());
            case PirTerm.ListMatch match -> new PirTerm.ListMatch(rewrite(match.scrutinee(), env, null),
                    match.headName(), match.tailName(), rewrite(match.nilBranch(), env, null),
                    rewrite(match.consBranch(), consEnv(env, match), null));
            case PirTerm.PairMatch match -> new PirTerm.PairMatch(rewrite(match.scrutinee(), env, null),
                    match.pairType(), match.firstName(), match.secondName(),
                    rewrite(match.body(), unbind(env, List.of(match.firstName(), match.secondName())), null));
            default -> PirHelpers.mapChildren(term, child -> rewrite(child, env, null));
        };
        return remember(term, result);
    }

    /**
     * The head of an application spine when it is a lambda chain applied on the spot (a lambda,
     * possibly under the lets and recursive bindings that bind what it uses, as the validator
     * wrapper applies the handler under its method bindings), else null.
     */
    private static PirTerm appliedChain(PirTerm.App app) {
        PirTerm head = app;
        while (head instanceof PirTerm.App spine) head = spine.function();
        return chain(head).isEmpty() ? null : head;
    }

    /**
     * Whether a term evaluates to a UPLC list by construction under {@code env}: a proven
     * variable, a decode or list builtin, a full call of a method whose declared return type is
     * a list and whose body the analysis proved to produce one, or a compound term whose result
     * position is one. A bare Data-typed term (the lowering of a cast to {@code JulcList}) is
     * not, and neither is a variable outside the proven set, whatever type its uses carry: an
     * alias of an unproven variable is unproven. A lambda applied on the spot and a partial
     * application are not followed.
     */
    boolean producesList(PirTerm term, Map<String, Binding> env) {
        return switch (term) {
            case PirTerm.Var v -> env.get(v.name()) instanceof Binding.Proven;
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
                    if (!(env.get(function.name()) instanceof Binding.Callable callable)
                            || callable.method().proven.length != arguments || !callable.method().returns) {
                        yield false;
                    }
                    PirType type = function.type();
                    for (int i = 0; i < arguments; i++) {
                        if (!(type instanceof PirType.FunType fun)) yield false;
                        type = fun.returnType();
                    }
                    yield type instanceof PirType.ListType;
                }
                yield false;
            }
            case PirTerm.Let let -> {
                var method = methodOf(let.name(), let.value());
                yield producesList(let.body(), bindLet(env, let.name(), method, method == null && producesList(let.value(), env)));
            }
            case PirTerm.LetRec rec -> producesList(rec.body(), bindRec(env, rec));
            case PirTerm.IfThenElse ite -> (ite.thenBranch() instanceof PirTerm.Error || producesList(ite.thenBranch(), env))
                    && (ite.elseBranch() instanceof PirTerm.Error || producesList(ite.elseBranch(), env));
            case PirTerm.Trace trace -> producesList(trace.body(), env);
            case PirTerm.ListMatch match -> (match.nilBranch() instanceof PirTerm.Error || producesList(match.nilBranch(), env))
                    && (match.consBranch() instanceof PirTerm.Error || producesList(match.consBranch(), consEnv(env, match)));
            case PirTerm.PairMatch match -> producesList(match.body(),
                    unbind(env, List.of(match.firstName(), match.secondName())));
            case PirTerm.DataMatch match -> match.branches().stream().allMatch(b ->
                    b.body() instanceof PirTerm.Error || producesList(b.body(), matchEnv(env, b)));
            case PirTerm.IntegerCase c -> c.branches().stream()
                    .allMatch(b -> b instanceof PirTerm.Error || producesList(b, env));
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
