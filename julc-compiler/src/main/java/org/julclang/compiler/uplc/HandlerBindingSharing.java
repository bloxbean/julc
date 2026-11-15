package org.julclang.compiler.uplc;

import org.julclang.core.NamedDeBruijn;
import org.julclang.core.Program;
import org.julclang.core.Term;
import org.julclang.core.flat.UplcFlatEncoder;
import org.julclang.core.recursion.Trampoline;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Binds once, where a multi-purpose validator's handlers branch apart, a method every handler binds to the same function
 * (issue #325, ADR-076 D1). It runs on the optimizer's output, after closed-value sharing, so each handler's code is what
 * it is without D1: only the duplicate bindings are replaced by one.
 * <p>
 * <b>What is shared.</b> A candidate method ({@link HandlerBindings}) is shared when the term binds it, as
 * {@code [(\m. body) value]}:
 * <ul>
 *   <li>once in each of as many <em>let spines</em> as there are handlers: runs of nested {@code let}s, each entered by
 *       some other step, with every shared method bound once in each of the same spines;</li>
 *   <li>with no lambda between the point where the handlers branch and the binding but {@code let}s', and, once the
 *       path below that point enters a {@code let}'s value, no delay, case branch or lambda after it: any of these could
 *       run the binding any number of times. Another method's value is such a value;</li>
 *   <li>to the same function each time: the same term, where a variable bound inside it is read by the same index and
 *       one bound outside it names the same binder;</li>
 *   <li>to a value that is total and has no effect, and that the optimizer does not inline into a single use; and</li>
 *   <li>reading only what is bound above that point, or another shared method.</li>
 * </ul>
 * A method that reads one that is not shared is not shared either. A method's binder is its {@code Owner.name}, which no
 * local and no generated {@code #} binder can be, so the bindings are found by name.
 * <p>
 * <b>Where.</b> The shared bindings wrap the smallest term that holds every binding, in dependency order. It is above
 * the dispatch and every handler's boundary check, and evaluated once per run.
 * <p>
 * <b>Why the program means the same.</b> Each value is the function every handler bound, reading the same binders, and
 * is total and has no effect: evaluating it once, before the handlers branch, instead of in the handler that runs, gives
 * the same results, failures and traces.
 * <p>
 * <b>Cost.</b> The assembly binds a handler's methods once, on the let spine at the start of the handler, after its
 * dispatch branch and boundary check. A run that reaches them evaluated each binding once, and now evaluates the same
 * bindings once on the way: the same steps. A run that fails before them, for want of a handler, at its boundary check
 * or in a static initializer, now evaluates the shared bindings first: an application, a lambda and the value's own
 * steps for each (ADR-076 I3).
 * <p>
 * <b>Size.</b> The result is kept only if its FLAT encoding is smaller. Anything else, an open term included, is left as
 * it is.
 */
final class HandlerBindingSharing {

    private HandlerBindingSharing() {
    }

    /**
     * The binders around a term, innermost first, and how many there are. One {@code Scope} per binder the walk meets,
     * shared by every term in its scope, so a binder is known by the object, not by its name and depth, which two
     * binders can share. {@code fixpoint} marks the binder of a {@code let} whose value is the Z combinator.
     */
    private record Scope(String name, Scope outer, int depth, boolean fixpoint) {
        static Scope push(Scope outer, String name, boolean fixpoint) {
            return new Scope(name, outer, depth(outer) + 1, fixpoint);
        }

        @Override
        public boolean equals(Object other) {
            return this == other;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(this);
        }

        static int depth(Scope scope) {
            return scope == null ? 0 : scope.depth();
        }

        /** The {@code index}th binder out, from 1, or null if there is none. */
        static Scope at(Scope scope, int index) {
            for (int i = 1; scope != null && i < index; i++) scope = scope.outer();
            return scope;
        }
    }

    /**
     * Where a term is: the child taken at each step from the root, the last step first. One {@code Path} per term of the
     * walk, shared by its descendants, so two paths meet at the same object.
     */
    private record Path(int index, Path parent, int length) {
        static Path child(Path parent, int index) {
            return new Path(index, parent, parent == null ? 1 : parent.length() + 1);
        }

        static int length(Path path) {
            return path == null ? 0 : path.length();
        }

        static Path up(Path path, int length) {
            while (length(path) > length) path = path.parent();
            return path;
        }

        /** The deepest path both lead through. */
        static Path common(Path a, Path b) {
            int length = Math.min(length(a), length(b));
            a = up(a, length);
            b = up(b, length);
            while (a != b) {
                a = a.parent();
                b = b.parent();
            }
            return a;
        }

        static int[] steps(Path path) {
            int[] steps = new int[length(path)];
            for (int i = steps.length - 1; path != null; i--, path = path.parent()) steps[i] = path.index();
            return steps;
        }
    }

    /**
     * What the walk knows of a term: its binders and path, the let spine it is on, how many lambdas other than a
     * {@code let}'s lie between it and the root. {@code lastValue} is the
     * depth of the last step into a {@code let}'s value on its path, and {@code deferredInValue} the deepest such step
     * that a deferring step (into a delay, a case branch or a lambda other than a {@code let}'s) follows.
     */
    private record Visit(Term term, Scope scope, Path path, int spine, int lambdas, boolean letFunction,
                         int lastValue, int deferredInValue, String binds, boolean fixpoint) {
        /** The visit of a child at {@code path}, reached by a step that defers it or not, or enters a let's value. */
        Visit child(Term term, Scope scope, Path path, int spine, int lambdas, boolean letFunction, boolean deferred,
                    boolean value) {
            return child(term, scope, path, spine, lambdas, letFunction, deferred, value, null, false);
        }

        /** {@link #child}, for a let's lambda: the method it binds, if a candidate, and whether its value is Z. */
        Visit child(Term term, Scope scope, Path path, int spine, int lambdas, boolean letFunction, boolean deferred,
                    boolean value, String binds, boolean fixpoint) {
            int position = Path.length(path);
            return new Visit(term, scope, path, spine, lambdas, letFunction, value ? position : lastValue,
                    deferred ? Math.max(deferredInValue, lastValue) : deferredInValue, binds, fixpoint);
        }
    }

    /** A binding {@code [(\name. body) value]} of a candidate: where it is, and what lies between it and the root. */
    private record Occurrence(String name, Term.Apply binding, Scope scope, Path path, int spine, int lambdas,
                              int deferredInValue) {
        Occurrence(String name, Term.Apply binding, Visit visit) {
            this(name, binding, visit.scope(), visit.path(), visit.spine(), visit.lambdas(), visit.deferredInValue());
        }

        Term value() {
            return binding.argument();
        }
    }

    /** What one walk of the term finds: each candidate's bindings, and the binder each binding declares. */
    private record Census(Map<String, List<Occurrence>> occurrences, Map<Scope, String> methodBinders) {
    }

    /** Where the shared bindings go: the term's steps from the root, the binders around it, and what lies above it. */
    private record Point(int[] steps, Scope scope, int lambdas) {
        int depth() {
            return Scope.depth(scope);
        }
    }

    /** A binder of the rebuilt term: its depth there, counted from the root. */
    private record Env(int level, Env outer) {
        static Env at(Env env, int index) {
            for (int i = 1; env != null && i < index; i++) env = env.outer();
            return env;
        }
    }

    /** {@code term} with the methods its handlers bind the same way bound once; {@code term} itself if none is. */
    static Term share(Term term, HandlerBindings handlers) {
        if (!handlers.any()) return term;
        var census = census(term, handlers.names());
        if (census == null) return term;
        var occurrences = census.occurrences();

        var shared = new LinkedHashSet<String>();
        for (var entry : occurrences.entrySet()) {
            var found = entry.getValue();
            var first = found.getFirst();
            var spines = new HashSet<Integer>();
            for (var occurrence : found) spines.add(occurrence.spine());
            if (found.size() == handlers.handlers() && spines.size() == found.size()
                    && isTotalValue(first.value(), first.scope())
                    && !UplcOptimizer.inlinesWhenUsedOnce(first.value())
                    && found.stream().allMatch(other -> sameFunction(first, other, handlers.names()))) {
                shared.add(entry.getKey());
            }
        }

        Point point;
        Map<String, Set<String>> dependencies;
        while (true) {
            if (shared.isEmpty()) return term;
            point = point(term, occurrences, shared);
            if (point == null) return term;
            dependencies = new HashMap<>();
            var excluded = new HashSet<String>();
            for (String name : shared) {
                var reads = new LinkedHashSet<String>();
                for (var occurrence : occurrences.get(name)) {
                    // A lambda between the point and the binding, other than a let's, could run it any number of times;
                    // so could a delay, case branch or lambda inside a let's value below the point, such as another
                    // method's value, which a binding inside it moves with.
                    boolean onItsSpine = occurrence.lambdas() == point.lambdas()
                            && occurrence.deferredInValue() <= point.steps().length;
                    if (!onItsSpine || !readsOnlyWhatIsInScope(occurrence, point.depth(), shared, census.methodBinders(), reads)) {
                        excluded.add(name);
                    }
                }
                dependencies.put(name, reads);
            }
            if (excluded.isEmpty()) {
                // Last, once the rest holds: every shared method in the same spines as the first, one per handler.
                var spines = spines(occurrences.get(shared.getFirst()));
                for (String name : shared) if (!spines(occurrences.get(name)).equals(spines)) excluded.add(name);
            }
            if (excluded.isEmpty()) break;
            shared = withoutWhatReads(shared, excluded, dependencies);
        }

        var order = dependencyOrder(shared, dependencies);
        if (order == null) return term;
        var levels = new HashMap<String, Integer>();
        for (int j = 0; j < order.size(); j++) levels.put(order.get(j), point.depth() + j + 1);
        var removed = new IdentityHashMap<Term, Integer>();
        for (String name : order) {
            for (var occurrence : occurrences.get(name)) removed.put(occurrence.binding(), levels.get(name));
        }

        Term inner = rebuild(node(term, point.steps()), env(point.scope(), point.depth(), levels), point.depth() + order.size(),
                removed).run();
        for (int j = order.size() - 1; j >= 0; j--) {
            String name = order.get(j);
            var first = occurrences.get(name).getFirst();
            Term value = rebuild(first.value(), env(first.scope(), point.depth(), levels), point.depth() + j, removed).run();
            inner = Term.apply(Term.lam(name, inner), value);
        }
        Term result = splice(term, point.steps(), inner);
        return encodedSize(result) < encodedSize(term) ? result : term;
    }

    /**
     * Every binding of a candidate, by name in the order first met, and every binder of the Z combinator; null if the
     * term reads a variable nothing binds.
     */
    private static Census census(Term term, Set<String> names) {
        var occurrences = new LinkedHashMap<String, List<Occurrence>>();
        var methodBinders = new HashMap<Scope, String>();
        var pending = new ArrayDeque<Visit>();
        pending.push(new Visit(term, null, null, 0, 0, false, 0, 0, null, false));
        // Each step that does not continue a let spine starts a new one, with a number of its own.
        int spines = 1;
        while (!pending.isEmpty()) {
            var visit = pending.pop();
            var scope = visit.scope();
            var path = visit.path();
            switch (visit.term()) {
                case Term.Var(var variable) -> {
                    if (variable.index() > Scope.depth(scope)) return null;
                }
                case Term.Apply apply -> {
                    boolean let = apply.function() instanceof Term.Lam;
                    String binds = null;
                    if (apply.function() instanceof Term.Lam(var name, var _) && names.contains(name)) {
                        occurrences.computeIfAbsent(name, n -> new ArrayList<>()).add(new Occurrence(name, apply, visit));
                        binds = name;
                    }
                    boolean fixpoint = let && UplcOptimizer.alphaEquals(apply.argument(), ClosedValueSharing.Z);
                    pending.push(visit.child(apply.argument(), scope, Path.child(path, 1), spines++, visit.lambdas(), false,
                            false, let));
                    pending.push(visit.child(apply.function(), scope, Path.child(path, 0), let ? visit.spine() : spines++,
                            visit.lambdas(), let, false, false, binds, fixpoint));
                }
                case Term.Lam(var name, var body) -> {
                    var inner = Scope.push(scope, name, visit.fixpoint());
                    if (visit.binds() != null) methodBinders.put(inner, visit.binds());
                    pending.push(visit.child(body, inner, Path.child(path, 0),
                            visit.letFunction() ? visit.spine() : spines++, visit.lambdas() + (visit.letFunction() ? 0 : 1),
                            false, !visit.letFunction(), false));
                }
                case Term.Force(var inner) -> pending.push(visit.child(inner, scope, Path.child(path, 0), spines++,
                        visit.lambdas(), false, false, false));
                case Term.Delay(var inner) -> pending.push(visit.child(inner, scope, Path.child(path, 0), spines++,
                        visit.lambdas(), false, true, false));
                case Term.Constr(var _, var fields) -> {
                    for (int i = fields.size() - 1; i >= 0; i--) {
                        pending.push(visit.child(fields.get(i), scope, Path.child(path, i), spines++, visit.lambdas(),
                                false, false, false));
                    }
                }
                case Term.Case(var scrutinee, var branches) -> {
                    for (int i = branches.size() - 1; i >= 0; i--) {
                        pending.push(visit.child(branches.get(i), scope, Path.child(path, i + 1), spines++, visit.lambdas(),
                                false, true, false));
                    }
                    pending.push(visit.child(scrutinee, scope, Path.child(path, 0), spines++, visit.lambdas(), false, false,
                            false));
                }
                default -> {
                }
            }
        }
        return new Census(occurrences, methodBinders);
    }

    /** The spines {@code occurrences} are on. */
    private static Set<Integer> spines(List<Occurrence> occurrences) {
        var found = new HashSet<Integer>();
        for (var occurrence : occurrences) found.add(occurrence.spine());
        return found;
    }

    /**
     * Whether evaluating {@code value} cannot fail or trace: a value, a pure term, or the fixpoint of a lambda over a
     * value, the Z combinator read from a binder of it, as closed-value sharing binds it at the root.
     */
    private static boolean isTotalValue(Term value, Scope scope) {
        if (UplcOptimizer.isPure(value)) return true;
        if (value instanceof Term.Apply(Term.Var(var z), Term.Lam(var _, var body)) && UplcOptimizer.isValue(body)) {
            var binder = Scope.at(scope, z.index());
            return binder != null && binder.fixpoint();
        }
        return false;
    }

    /**
     * The smallest term holding every binding of a shared method, or null if that is one of the bindings. It is an
     * application, a constructor or a case whose children lead to different bindings.
     */
    private static Point point(Term term, Map<String, List<Occurrence>> occurrences, Set<String> shared) {
        Path common = null;
        boolean first = true;
        for (String name : shared) {
            for (var occurrence : occurrences.get(name)) {
                common = first ? occurrence.path() : Path.common(common, occurrence.path());
                first = false;
            }
        }
        int length = Path.length(common);
        for (String name : shared) {
            for (var occurrence : occurrences.get(name)) {
                if (Path.length(occurrence.path()) <= length) return null;
            }
        }
        int[] steps = Path.steps(common);
        Scope scope = null;
        int lambdas = 0;
        boolean letFunction = false;
        Term node = term;
        for (int step : steps) {
            if (node instanceof Term.Lam(var name, var _)) {
                // Only the depths of this scope are read: the point's binders are not looked up by object.
                scope = Scope.push(scope, name, false);
                if (!letFunction) lambdas++;
            }
            letFunction = node instanceof Term.Apply(Term.Lam _, var _) && step == 0;
            node = child(node, step);
        }
        return new Point(steps, scope, lambdas);
    }

    /**
     * Whether every variable {@code occurrence}'s value reads from outside it is bound at {@code depth} or above, where
     * the shared bindings go, or is the binder of another shared method; those it reads go into {@code reads}.
     */
    private static boolean readsOnlyWhatIsInScope(Occurrence occurrence, int depth, Set<String> shared,
                                                  Map<Scope, String> methodBinders, Set<String> reads) {
        record Read(Term term, int local) {}
        var pending = new ArrayDeque<Read>();
        pending.push(new Read(occurrence.value(), 0));
        while (!pending.isEmpty()) {
            var read = pending.pop();
            int local = read.local();
            switch (read.term()) {
                case Term.Var(var variable) -> {
                    if (variable.index() > local) {
                        var binder = Scope.at(occurrence.scope(), variable.index() - local);
                        if (binder == null) return false;
                        if (binder.depth() > depth) {
                            // Below the point, only the binder a shared method's own binding declares.
                            String method = methodBinders.get(binder);
                            if (method == null || !shared.contains(method)) return false;
                            reads.add(method);
                        }
                    }
                }
                case Term.Lam(var _, var body) -> pending.push(new Read(body, local + 1));
                case Term.Apply(var function, var argument) -> {
                    pending.push(new Read(function, local));
                    pending.push(new Read(argument, local));
                }
                case Term.Force(var inner) -> pending.push(new Read(inner, local));
                case Term.Delay(var inner) -> pending.push(new Read(inner, local));
                case Term.Constr(var _, var fields) -> {
                    for (var field : fields) pending.push(new Read(field, local));
                }
                case Term.Case(var scrutinee, var branches) -> {
                    pending.push(new Read(scrutinee, local));
                    for (var branch : branches) pending.push(new Read(branch, local));
                }
                default -> {
                }
            }
        }
        return true;
    }

    /** {@code names}, less {@code excluded} and every name that reads one of them through {@code dependencies}. */
    private static LinkedHashSet<String> withoutWhatReads(Set<String> names, Set<String> excluded,
                                                          Map<String, Set<String>> dependencies) {
        var dependents = new HashMap<String, List<String>>();
        for (String name : names) {
            for (String dependency : dependencies.getOrDefault(name, Set.of())) {
                dependents.computeIfAbsent(dependency, d -> new ArrayList<>()).add(name);
            }
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
     * {@code names} with each after those it reads, by Kahn's algorithm, ties in the order given; null if they read
     * each other in a cycle.
     */
    private static List<String> dependencyOrder(Set<String> names, Map<String, Set<String>> dependencies) {
        var rank = new HashMap<String, Integer>();
        for (String name : names) rank.put(name, rank.size());
        var waiting = new HashMap<String, Integer>();
        var dependents = new HashMap<String, List<String>>();
        var ready = new TreeSet<String>((a, b) -> Integer.compare(rank.get(a), rank.get(b)));
        for (String name : names) {
            var reads = dependencies.get(name);
            waiting.put(name, reads.size());
            for (String read : reads) dependents.computeIfAbsent(read, r -> new ArrayList<>()).add(name);
            if (reads.isEmpty()) ready.add(name);
        }
        var order = new ArrayList<String>();
        while (!ready.isEmpty()) {
            String next = ready.pollFirst();
            order.add(next);
            for (String dependent : dependents.getOrDefault(next, List.of())) {
                if (waiting.merge(dependent, -1, Integer::sum) == 0) ready.add(dependent);
            }
        }
        return order.size() == names.size() ? order : null;
    }

    /**
     * The binders of {@code scope} as the rebuilt term has them: one at {@code depth} or above where it was, the binder of
     * a shared method where that method is now bound.
     */
    private static Env env(Scope scope, int depth, Map<String, Integer> levels) {
        var binders = new ArrayList<Scope>();
        for (var binder = scope; binder != null; binder = binder.outer()) binders.add(binder);
        Env env = null;
        for (int i = binders.size() - 1; i >= 0; i--) {
            var binder = binders.get(i);
            Integer level = binder.depth() <= depth ? Integer.valueOf(binder.depth()) : levels.get(binder.name());
            // A binder below the point that is not a shared method: a variable that reads it is not rebuilt from here.
            env = new Env(level == null ? -1 : level, env);
        }
        return env;
    }

    /** {@code term}, rebuilt at {@code depth}, with each binding in {@code removed} replaced by its body. */
    private static Trampoline<Term> rebuild(Term term, Env env, int depth, IdentityHashMap<Term, Integer> removed) {
        return Trampoline.defer(() -> switch (term) {
            case Term.Var(var variable) -> {
                var binder = Env.at(env, variable.index());
                if (binder == null || binder.level() < 0) {
                    throw new IllegalStateException("a shared binding reads a binder it cannot see: " + variable);
                }
                yield Trampoline.done(new Term.Var(new NamedDeBruijn(variable.name(), depth - binder.level() + 1)));
            }
            case Term.Lam(var name, var body) -> rebuild(body, new Env(depth + 1, env), depth + 1, removed)
                    .map(b -> (Term) Term.lam(name, b));
            case Term.Apply(var function, var argument) -> {
                Integer level = removed.get(term);
                if (level != null) yield rebuild(((Term.Lam) function).body(), new Env(level, env), depth, removed);
                yield rebuild(function, env, depth, removed)
                        .then(f -> rebuild(argument, env, depth, removed).map(a -> (Term) Term.apply(f, a)));
            }
            case Term.Force(var inner) -> rebuild(inner, env, depth, removed).map(i -> (Term) Term.force(i));
            case Term.Delay(var inner) -> rebuild(inner, env, depth, removed).map(i -> (Term) Term.delay(i));
            case Term.Constr constr -> Trampoline.each(constr.fields(), f -> rebuild(f, env, depth, removed))
                    .map(fields -> (Term) new Term.Constr(constr.tag(), fields));
            case Term.Case match -> rebuild(match.scrutinee(), env, depth, removed)
                    .then(s -> Trampoline.each(match.branches(), b -> rebuild(b, env, depth, removed))
                            .map(branches -> (Term) new Term.Case(s, branches)));
            case Term.Const _, Term.Builtin _, Term.Error _ -> Trampoline.done(term);
        });
    }

    private static Term child(Term node, int index) {
        return switch (node) {
            case Term.Lam(var _, var body) -> body;
            case Term.Apply(var function, var argument) -> index == 0 ? function : argument;
            case Term.Force(var inner) -> inner;
            case Term.Delay(var inner) -> inner;
            case Term.Constr(var _, var fields) -> fields.get(index);
            case Term.Case(var scrutinee, var branches) -> index == 0 ? scrutinee : branches.get(index - 1);
            default -> throw new IllegalStateException("a leaf has no child");
        };
    }

    private static Term node(Term term, int[] steps) {
        for (int step : steps) term = child(term, step);
        return term;
    }

    /** {@code term} with the term at {@code steps} replaced by {@code replacement}. */
    private static Term splice(Term term, int[] steps, Term replacement) {
        var nodes = new Term[steps.length];
        Term node = term;
        for (int i = 0; i < steps.length; i++) {
            nodes[i] = node;
            node = child(node, steps[i]);
        }
        Term current = replacement;
        for (int i = steps.length - 1; i >= 0; i--) current = withChild(nodes[i], steps[i], current);
        return current;
    }

    private static Term withChild(Term node, int index, Term child) {
        return switch (node) {
            case Term.Lam(var name, var _) -> Term.lam(name, child);
            case Term.Apply(var function, var argument) -> index == 0 ? Term.apply(child, argument) : Term.apply(function, child);
            case Term.Force _ -> Term.force(child);
            case Term.Delay _ -> Term.delay(child);
            case Term.Constr(var tag, var fields) -> {
                var copy = new ArrayList<>(fields);
                copy.set(index, child);
                yield new Term.Constr(tag, copy);
            }
            case Term.Case(var scrutinee, var branches) -> {
                if (index == 0) yield new Term.Case(child, branches);
                var copy = new ArrayList<>(branches);
                copy.set(index - 1, child);
                yield new Term.Case(scrutinee, copy);
            }
            default -> throw new IllegalStateException("a leaf has no child");
        };
    }

    private static int encodedSize(Term term) {
        return UplcFlatEncoder.encodeProgram(Program.plutusV3(term)).length;
    }

    /**
     * Whether two of a method's bindings are the same function: the same term, where a variable bound inside it is read
     * by the same index and one bound outside it names the same binder. A candidate's binder carries the method's name,
     * and {@link #readsOnlyWhatIsInScope} checks it is that method's binding; any other must be the very binder, as one
     * bound above both handlers is.
     */
    private static boolean sameFunction(Occurrence a, Occurrence b, Set<String> candidates) {
        record Pair(Term a, Term b, int depth) {}
        var pending = new ArrayDeque<Pair>();
        pending.push(new Pair(a.value(), b.value(), 0));
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
                        // A method's binder in each handler, checked later to be its binding's; any other must be one
                        // binder, above both.
                        if (!candidates.contains(outerA.name()) && outerA != outerB) return false;
                    }
                }
                case Term.Lam(var _, var body) -> {
                    if (!(pair.b() instanceof Term.Lam(var _, var otherBody))) return false;
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
}
