package org.julclang.compiler.codegen;

import org.julclang.compiler.codegen.ValidatorAssembly.Method;
import org.julclang.compiler.codegen.ValidatorAssembly.StaticField;
import org.julclang.compiler.pir.PirSubstitution;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.resolve.LibraryMethodRegistry.LibraryMethod;
import org.julclang.compiler.uplc.UplcOptimizer;

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
 * The methods a multi-purpose validator's handlers may share, read from the PIR (issue #325, ADR-076 D1). The UPLC
 * optimizer shares those of them its own output binds the same way in every handler ({@code HandlerBindingSharing}).
 * <p>
 * A method is a candidate when:
 * <ul>
 *   <li>every handler needs it: its entry point reaches it through the methods and static fields it names, read as free
 *       variables, so a local that shadows a method's name does not count;</li>
 *   <li>nothing it reaches is a static field of the validator: the static fields stay bound inside each handler, as
 *       before, so their initializers run where they always did; and</li>
 *   <li>it, and every method it reaches, is a function the optimizer keeps ({@link #isKeptFunction}). A function is
 *       total and has no effect, so evaluating its binding before the handlers branch, not in the one that runs, gives
 *       the same results, failures and traces.</li>
 * </ul>
 * A candidate's dependencies are candidates too, since every handler that reaches it reaches them.
 */
public final class SharedBindings {

    /** The switchable rule; it runs from {@code PV11_SAFE} up. */
    public static final String RULE = UplcOptimizer.SHARED_HANDLER_BINDINGS_RULE;

    private SharedBindings() {
    }

    /** The candidates of a validator whose handlers start at {@code entries}, read from the PIR. */
    static Set<String> candidates(List<PirTerm> entries, List<Method> helpers, List<StaticField> statics,
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
        if (everyHandler == null) return Set.of();
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

    /** {@code names}, less {@code excluded} and every name that reaches one of them through {@code dependencies}. */
    private static Set<String> withoutWhatReaches(Set<String> names, Set<String> excluded,
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
        for (String name : names) if (!dropped.contains(name)) kept.add(name);
        return kept;
    }

    /**
     * Whether {@code body} has the PIR shape of a function the optimizer keeps wherever it is bound: a lambda whose body
     * is not just a builtin applied to its parameters in order. The optimizer inlines a binding used once only when its
     * value is a constant, a variable or a (forced) builtin, and a builtin wrapped in a lambda over its arguments becomes
     * one by eta reduction. A method with no parameters, a constant or a delayed body, is not shared for the same reason.
     *
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

}
