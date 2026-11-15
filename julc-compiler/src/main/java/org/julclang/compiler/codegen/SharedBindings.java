package org.julclang.compiler.codegen;

import org.julclang.compiler.codegen.ValidatorAssembly.Method;
import org.julclang.compiler.codegen.ValidatorAssembly.StaticField;
import org.julclang.compiler.pir.PirSubstitution;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.resolve.LibraryMethodRegistry.LibraryMethod;

import java.util.ArrayDeque;
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
 * The budget can move a little either way, because the optimizer treats a binding outside the dispatch differently
 * from one inside a handler: a helper a handler calls once was inlined into it, and once shared it is bound instead,
 * a few machine steps more on that handler's path; other paths can come out cheaper. The counter benchmark of issue
 * #325 measures and pins these (ADR-076).
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
        var shared = new LinkedHashSet<String>();
        if (everyHandler != null) {
            for (String name : everyHandler) {
                if (staticNames.contains(name)) continue;
                var reached = reached(Set.of(name), bodies, freeNames);
                if (reached.stream().noneMatch(staticNames::contains)) shared.add(name);
            }
        }
        return new SharedBindings(shared);
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
