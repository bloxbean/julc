package org.julclang.compiler.codegen;

import org.julclang.compiler.CompilationContext;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.codegen.ValidatorAssembly.Method;
import org.julclang.compiler.codegen.ValidatorAssembly.StaticField;
import org.julclang.compiler.pir.PirSubstitution;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;
import org.julclang.compiler.resolve.LibraryMethodRegistry.LibraryMethod;
import org.julclang.compiler.uplc.UplcGenerator;
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
 * every handler uses was bound, and so written into the script, once per handler. A method is <em>shared</em> when:
 * <ul>
 *   <li>it, and every method it reaches, is a function the optimizer keeps ({@link #isKeptFunction});</li>
 *   <li>every handler needs it: its entry point reaches it through the methods and static fields it names, read as free
 *       variables, so a local that shadows a method's name does not count; and</li>
 *   <li>nothing it reaches is a static field of the validator: the static fields stay bound inside each handler, as
 *       before, so their initializers run where they always did.</li>
 * </ul>
 * A shared method's dependencies are shared too, since every handler that reaches it reaches them, so binding the shared
 * methods outside the dispatch leaves each name in scope where it is read.
 * <p>
 * This keeps what each purpose runs. Every handler bound each shared method before; a binding is a function value, or
 * one of the total, cheap values the delayed-method pass leaves bound, so evaluating it before the dispatch instead of
 * after it gives the same values, failures and traces.
 * <p>
 * A run that reaches a handler takes the same steps, or fewer: that handler bound the same methods, each still a lambda
 * after the optimizer ({@link #optimizesToAFunction}), which it never inlines. A run whose purpose has no handler fails
 * at the dispatch, and now evaluates the shared bindings first (ADR-076 I3).
 */
public final class SharedBindings {

    /** The switchable rule; it runs from {@code PV11_SAFE} up. */
    public static final String RULE = "pv11.o16.shared-handler-bindings";

    /** No method shared: every handler binds what it uses, as below {@code PV11_SAFE} or with the rule disabled. */
    static final SharedBindings NONE = new SharedBindings(Set.of());

    private final Set<String> shared;

    private SharedBindings(Set<String> shared) {
        this.shared = shared;
    }

    /** The shared methods of a validator whose handlers start at {@code entries}. */
    static SharedBindings of(List<PirTerm> entries, List<Method> helpers, List<StaticField> statics,
                             Collection<LibraryMethod> libraries, CompilationContext context) {
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
        var shared = new LinkedHashSet<String>();
        if (everyHandler != null) {
            for (String name : everyHandler) {
                if (staticNames.contains(name)) continue;
                var reached = reached(Set.of(name), bodies, freeNames);
                // Everything it reaches must be a function the optimizer keeps, so that sharing it cannot stop an inlining
                // (and every dependency of a shared method is shared too).
                if (reached.stream().noneMatch(staticNames::contains)
                        && reached.stream().allMatch(reachedName -> isKeptFunction(bodies.get(reachedName))
                                && optimizesToAFunction(bodies.get(reachedName), context))) {
                    shared.add(name);
                }
            }
        }
        return new SharedBindings(shared);
    }

    /**
     * Whether the UPLC optimizer keeps {@code body} as a function wherever it is bound: a lambda whose body is not just a
     * builtin applied to its parameters in order. The optimizer inlines a binding used once only when its value is a
     * constant, a variable or a (forced) builtin, and a builtin wrapped in a lambda over its arguments becomes one by eta
     * reduction. A handler that called such a method once had it inlined, and could fold it further; bound around the
     * dispatch it would not be, so sharing it would add work to that handler. A method with no parameters, a constant
     * or a delayed body, is not shared for the same reason.
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

    /**
     * Whether {@code body} is still a function once the UPLC optimizer has run on it, not a value its beta reduction
     * would inline into a single use ({@link UplcOptimizer#inlinesWhenUsedOnce}). The PIR shape {@link #isKeptFunction}
     * reads cannot see everything the optimizer simplifies: an unused local is dropped and a lambda over a builtin
     * becomes the builtin. So the body is lowered and optimized on its own, its free names as parameters, at this
     * compilation's level, in a scratch context that records nothing. A body that cannot be lowered alone is not shared.
     */
    private static boolean optimizesToAFunction(PirTerm body, CompilationContext context) {
        var free = new ArrayList<>(PirSubstitution.collectFreeVarNames(body));
        PirTerm closed = body;
        for (int i = free.size() - 1; i >= 0; i--) closed = new PirTerm.Lam(free.get(i), new PirType.DataType(), closed);
        try {
            var options = new CompilerOptions().setTarget(context.target()).setOptimizationLevel(context.optimizationLevel())
                    .disableOptimizationRule(UplcOptimizer.SHARED_CLOSED_VALUES_RULE);
            if (context.optimizationCostProfile() != null) options.setOptimizationCostProfile(context.optimizationCostProfile());
            var scratch = CompilationContext.resolve(options);
            Term term = new UplcOptimizer(scratch).optimize(new UplcGenerator(scratch, null).generate(closed));
            for (int i = 0; i < free.size(); i++) {
                if (!(term instanceof Term.Lam(var name, var inner))) return false;
                term = inner;
            }
            return !UplcOptimizer.inlinesWhenUsedOnce(term);
        } catch (RuntimeException e) {
            return false;
        }
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
