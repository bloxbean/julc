package org.julclang.compiler.codegen;

import org.julclang.compiler.codegen.ValidatorAssembly.Method;
import org.julclang.compiler.codegen.ValidatorAssembly.StaticField;
import org.julclang.compiler.pir.PirSubstitution;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.resolve.LibraryMethodRegistry.LibraryMethod;
import org.julclang.compiler.uplc.UplcOptimizer;
import org.julclang.core.Term;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which of a multi-purpose validator's methods are bound once, around its purpose dispatch, instead of once inside each
 * purpose's handler (issue #325, ADR-076 D1).
 * <p>
 * Each handler of a validator with several entry points binds the helper methods and library methods it uses. A method
 * every handler uses was bound, and so written into the script, once per handler. It is decided in two steps.
 * <p>
 * {@link #candidates} reads the PIR. A method is a candidate when:
 * <ul>
 *   <li>every handler needs it: its entry point reaches it through the methods and static fields it names, read as free
 *       variables, so a local that shadows a method's name does not count;</li>
 *   <li>nothing it reaches is a static field of the validator: the static fields stay bound inside each handler, as
 *       before, so their initializers run where they always did; and</li>
 *   <li>it, and every method it reaches, has the PIR shape of a function the optimizer keeps ({@link #isKeptFunction}).</li>
 * </ul>
 * {@link #keptIn} then reads the program as compiled without D1, through the whole backend: a candidate stays shared only
 * if every handler there still binds it, each to the same function, and that function is not a value the optimizer
 * would inline into a single use ({@link UplcOptimizer#inlinesWhenUsedOnce}). What the PIR cannot show, such as a cast a
 * PIR pass removes or a use constant folding drops, is read from what the backend did.
 * <p>
 * A shared method's dependencies are shared too, since every handler that reaches it reaches them, so binding the shared
 * methods outside the dispatch leaves each name in scope where it is read.
 * <p>
 * This keeps what each purpose runs. Every handler bound each shared method, to the same function value, before; that
 * value is now computed once before the dispatch instead of after it, with the same result, failures and traces.
 * <p>
 * A run that reaches a handler takes the same steps, or fewer: that handler evaluated the same binding to the same
 * function, which the optimizer kept bound there and keeps bound around the dispatch. A run whose purpose has no handler
 * fails at the dispatch, and now evaluates the shared bindings first (ADR-076 I3).
 */
public final class SharedBindings {

    /** The switchable rule; it runs from {@code PV11_SAFE} up. */
    public static final String RULE = "pv11.o16.shared-handler-bindings";

    /** No method shared: every handler binds what it uses, as below {@code PV11_SAFE} or with the rule disabled. */
    static final SharedBindings NONE = new SharedBindings(Set.of(), Map.of());

    private final Set<String> shared;
    /** For each shared method, the shared methods its body names. */
    private final Map<String, Set<String>> dependencies;

    private SharedBindings(Set<String> shared, Map<String, Set<String>> dependencies) {
        this.shared = shared;
        this.dependencies = dependencies;
    }

    /** The candidates of a validator whose handlers start at {@code entries}, read from the PIR. */
    static SharedBindings candidates(List<PirTerm> entries, List<Method> helpers, List<StaticField> statics,
                                     Collection<LibraryMethod> libraries) {
        var bodies = new HashMap<String, PirTerm>();
        for (var library : libraries) bodies.put(library.qualifiedName(), library.body());
        for (var helper : helpers) bodies.put(helper.name(), helper.body());
        var staticNames = new HashSet<String>();
        for (var field : statics) {
            bodies.put(field.name(), field.value());
            staticNames.add(field.name());
        }
        var freeNames = new HashMap<String, Set<String>>();

        Set<String> everyHandler = null;
        for (var entry : entries) {
            var reached = reached(PirSubstitution.collectFreeVarNames(entry), bodies, freeNames);
            if (everyHandler == null) everyHandler = new LinkedHashSet<>(reached);
            else everyHandler.retainAll(reached);
        }
        if (everyHandler == null) return NONE;
        // What every handler reaches is closed under what it names: each name's dependencies are in the set.
        var dependencies = new HashMap<String, Set<String>>();
        var excluded = new HashSet<String>();
        for (String name : everyHandler) {
            var names = new LinkedHashSet<String>();
            for (String next : freeNames.get(name)) if (bodies.containsKey(next)) names.add(next);
            dependencies.put(name, names);
            // A static stays in each handler, and a method the optimizer would inline is left to it.
            if (staticNames.contains(name) || !isKeptFunction(bodies.get(name))) excluded.add(name);
        }
        return withoutWhatReaches(everyHandler, excluded, dependencies);
    }

    /**
     * These candidates, less those {@code trial}, the program as compiled without D1 by the whole backend, does not bind
     * the same way in each of its {@code handlers} handlers.
     */
    SharedBindings keptIn(Term trial, int handlers) {
        if (shared.isEmpty()) return this;
        var bindings = new HashMap<String, List<Binding>>();
        var pending = new ArrayDeque<Binding>();
        pending.push(new Binding(trial, null));
        while (!pending.isEmpty()) {
            var next = pending.pop();
            var scope = next.scope();
            switch (next.term()) {
                case Term.Apply(var function, var argument) -> {
                    if (function instanceof Term.Lam(var name, var body) && shared.contains(name)) {
                        bindings.computeIfAbsent(name, n -> new ArrayList<>()).add(new Binding(argument, scope));
                    }
                    pending.push(new Binding(function, scope));
                    pending.push(new Binding(argument, scope));
                }
                case Term.Lam(var name, var body) -> pending.push(new Binding(body, new Scope(name, scope)));
                case Term.Force(var inner) -> pending.push(new Binding(inner, scope));
                case Term.Delay(var inner) -> pending.push(new Binding(inner, scope));
                case Term.Constr(var tag, var fields) -> {
                    for (var field : fields) pending.push(new Binding(field, scope));
                }
                case Term.Case(var scrutinee, var branches) -> {
                    pending.push(new Binding(scrutinee, scope));
                    for (var branch : branches) pending.push(new Binding(branch, scope));
                }
                default -> {
                }
            }
        }
        var excluded = new HashSet<String>();
        for (String name : shared) {
            var found = bindings.getOrDefault(name, List.of());
            if (found.size() != handlers || UplcOptimizer.inlinesWhenUsedOnce(found.getFirst().term())
                    || !found.stream().allMatch(binding -> sameFunction(found.getFirst(), binding))) {
                excluded.add(name);
            }
        }
        return withoutWhatReaches(shared, excluded, dependencies);
    }

    /** {@code names}, less {@code excluded} and every name that reaches one of them through {@code dependencies}. */
    private static SharedBindings withoutWhatReaches(Set<String> names, Set<String> excluded,
                                                     Map<String, Set<String>> dependencies) {
        var dependents = new HashMap<String, List<String>>();
        for (String name : names) {
            for (String dependency : dependencies.get(name)) dependents.computeIfAbsent(dependency, d -> new ArrayList<>()).add(name);
        }
        var dropped = new HashSet<>(excluded);
        var pending = new ArrayDeque<>(excluded);
        while (!pending.isEmpty()) {
            for (String dependent : dependents.getOrDefault(pending.pop(), List.of())) {
                if (dropped.add(dependent)) pending.push(dependent);
            }
        }
        var kept = new LinkedHashSet<String>();
        var keptDependencies = new HashMap<String, Set<String>>();
        for (String name : names) {
            if (dropped.contains(name)) continue;
            kept.add(name);
            keptDependencies.put(name, dependencies.get(name));
        }
        return new SharedBindings(kept, keptDependencies);
    }

    /** A term of the trial program, under the names bound around it, innermost first. */
    private record Binding(Term term, Scope scope) {
    }

    /** The binders around a term, innermost first, and how many there are. */
    private record Scope(String name, Scope outer, int depth) {
        Scope(String name, Scope outer) {
            this(name, outer, outer == null ? 1 : outer.depth() + 1);
        }

        /** The {@code index}th binder out, from 1, or null if there is none. */
        static Scope at(Scope scope, int index) {
            for (int i = 1; scope != null && i < index; i++) scope = scope.outer();
            return scope;
        }
    }

    /**
     * Whether two of a method's bindings are the same function: the same term, where a variable bound inside it is read
     * by the same index and one bound outside it names the same binder. A method's free names are other shared methods,
     * whose binders carry the method's name, which no other binder has, and names bound outside every handler, which
     * each handler sees at the same depth.
     */
    private boolean sameFunction(Binding a, Binding b) {
        record Pair(Term a, Term b, int depth) {}
        var pending = new ArrayDeque<Pair>();
        pending.push(new Pair(a.term(), b.term(), 0));
        while (!pending.isEmpty()) {
            var pair = pending.pop();
            int depth = pair.depth();
            switch (pair.a()) {
                case Term.Var(var x) -> {
                    if (!(pair.b() instanceof Term.Var(var y))) return false;
                    int i = x.index(), j = y.index();
                    if (i <= depth || j <= depth) {
                        if (i != j) return false;
                    } else {
                        var outerA = Scope.at(a.scope(), i - depth);
                        var outerB = Scope.at(b.scope(), j - depth);
                        if (outerA == null || outerB == null || !outerA.name().equals(outerB.name())) return false;
                        if (!shared.contains(outerA.name()) && outerA.depth() != outerB.depth()) return false;
                    }
                }
                case Term.Lam(var name, var body) -> {
                    if (!(pair.b() instanceof Term.Lam(var otherName, var otherBody))) return false;
                    pending.push(new Pair(body, otherBody, depth + 1));
                }
                case Term.Apply(var function, var argument) -> {
                    if (!(pair.b() instanceof Term.Apply(var otherFunction, var otherArgument))) return false;
                    pending.push(new Pair(function, otherFunction, depth));
                    pending.push(new Pair(argument, otherArgument, depth));
                }
                case Term.Force(var inner) -> {
                    if (!(pair.b() instanceof Term.Force(var other))) return false;
                    pending.push(new Pair(inner, other, depth));
                }
                case Term.Delay(var inner) -> {
                    if (!(pair.b() instanceof Term.Delay(var other))) return false;
                    pending.push(new Pair(inner, other, depth));
                }
                case Term.Constr(var tag, var fields) -> {
                    if (!(pair.b() instanceof Term.Constr(var otherTag, var otherFields))
                            || tag != otherTag || fields.size() != otherFields.size()) return false;
                    for (int k = 0; k < fields.size(); k++) pending.push(new Pair(fields.get(k), otherFields.get(k), depth));
                }
                case Term.Case(var scrutinee, var branches) -> {
                    if (!(pair.b() instanceof Term.Case(var otherScrutinee, var otherBranches))
                            || branches.size() != otherBranches.size()) return false;
                    pending.push(new Pair(scrutinee, otherScrutinee, depth));
                    for (int k = 0; k < branches.size(); k++) pending.push(new Pair(branches.get(k), otherBranches.get(k), depth));
                }
                default -> {
                    // A constant, a builtin or an error: compared whole.
                    if (!pair.a().equals(pair.b())) return false;
                }
            }
        }
        return true;
    }

    /**
     * Whether {@code body} has the PIR shape of a function the optimizer keeps wherever it is bound: a lambda whose body
     * is not just a builtin applied to its parameters in order. The optimizer inlines a binding used once only when its
     * value is a constant, a variable or a (forced) builtin, and a builtin wrapped in a lambda over its arguments becomes
     * one by eta reduction. A method with no parameters, a constant or a delayed body, is not shared for the same reason.
     * This only narrows the candidates cheaply; {@link #keptIn} decides from what the backend did.
     * <p>
     * A {@code let} that only renames a variable, as the javac frontend writes for a local initialised from a parameter
     * ({@code PlutusData e = d; return Builtins.unIData(e);}), is read through: the optimizer beta-reduces it, and what is
     * left can be a builtin over the parameters.
     */
    private static boolean isKeptFunction(PirTerm body) {
        var parameters = new ArrayList<String>();
        var aliases = new HashMap<String, String>();
        PirTerm inner = body;
        while (true) {
            if (inner instanceof PirTerm.Lam lam) {
                // A parameter that hides an earlier one cannot be eta-reduced away with it: keep the function.
                if (parameters.contains(lam.param())) return true;
                parameters.add(lam.param());
                aliases.remove(lam.param());
                inner = lam.body();
            } else if (inner instanceof PirTerm.Let let && let.value() instanceof PirTerm.Var renamed) {
                // A rename that hides a parameter leaves that parameter unused: keep the function.
                if (parameters.contains(let.name())) return true;
                aliases.put(let.name(), aliases.getOrDefault(renamed.name(), renamed.name()));
                inner = let.body();
            } else {
                break;
            }
        }
        if (parameters.isEmpty()) return false;
        return !switch (inner) {
            case PirTerm.Var _, PirTerm.Const _, PirTerm.Builtin _ -> true;
            case PirTerm.App app -> isBuiltinOver(app, parameters, aliases);
            default -> false;
        };
    }

    /** Whether {@code app} is a builtin applied to exactly {@code parameters}, in order, read through {@code aliases}. */
    private static boolean isBuiltinOver(PirTerm.App app, List<String> parameters, Map<String, String> aliases) {
        var arguments = new ArrayList<PirTerm>();
        PirTerm head = app;
        while (head instanceof PirTerm.App next) {
            arguments.addFirst(next.argument());
            head = next.function();
        }
        if (!(head instanceof PirTerm.Builtin) || arguments.size() != parameters.size()) return false;
        for (int i = 0; i < arguments.size(); i++) {
            if (!(arguments.get(i) instanceof PirTerm.Var variable)
                    || !aliases.getOrDefault(variable.name(), variable.name()).equals(parameters.get(i))) return false;
        }
        return true;
    }

    /** The methods and static fields {@code start} names, and everything they reach in turn, found with a work list. */
    private static Set<String> reached(Set<String> start, Map<String, PirTerm> bodies, Map<String, Set<String>> freeNames) {
        var reached = new LinkedHashSet<String>();
        var pending = new ArrayDeque<String>();
        for (String name : start) if (bodies.containsKey(name)) pending.add(name);
        while (!pending.isEmpty()) {
            String name = pending.poll();
            if (!reached.add(name)) continue;
            var names = freeNames.computeIfAbsent(name, n -> PirSubstitution.collectFreeVarNames(bodies.get(n)));
            for (String next : names) if (bodies.containsKey(next) && !reached.contains(next)) pending.add(next);
        }
        return reached;
    }

    /** Whether {@code name}, a helper's binder or a library method's qualified name, is bound around the dispatch. */
    boolean isShared(String name) {
        return shared.contains(name);
    }

    /** Whether any method is shared. */
    boolean any() {
        return !shared.isEmpty();
    }
}
