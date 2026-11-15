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

/**
 * Binds once, where a multi-purpose validator's handlers branch apart, a method every handler binds to the same function
 * (issue #325, ADR-076 D1). It runs on the optimizer's output, after closed-value sharing, so each handler's code is what
 * it is without D1: only the duplicate bindings are replaced by one.
 * <p>
 * <b>What is shared.</b> A candidate method ({@link HandlerBindings}) is shared when the term binds it, as
 * {@code [(\m. body) value]}, exactly once per handler; each binding is the same function (the same term, with a variable
 * bound outside it naming the same binder); that function is not a value the optimizer inlines into a single use; and
 * every variable it reads is bound where the handlers branch apart or above, or is another shared method. A method that
 * reads one that is not shared is not shared either. A method's binder is its {@code Owner.name}, which no local and no
 * generated {@code #} binder can be, so the bindings are found by name.
 * <p>
 * <b>Where.</b> The shared bindings wrap the smallest term that holds every binding, in dependency order. That term is
 * evaluated before any handler runs, on every path to one.
 * <p>
 * <b>Why the program means the same.</b> The candidates are methods the PIR shows to be functions, so each value is total
 * and has no effect: evaluating it once, before the handlers branch, instead of in the handler that runs, gives the same
 * results, failures and traces.
 * <p>
 * <b>Cost.</b> A handler evaluated each of its bindings once; now the same bindings are evaluated once on the way to it,
 * so a run that reaches its handler's bindings takes the same steps. A run that fails before reaching them, for want of a
 * handler or at its boundary check, now evaluates the shared bindings first: an application, a lambda and the value's
 * own steps for each (ADR-076 I3).
 * <p>
 * <b>Size.</b> The result is kept only if its FLAT encoding is smaller.
 */
final class HandlerBindingSharing {

    private HandlerBindingSharing() {
    }

    /** The binders around a term, innermost first, and how many there are. */
    private record Scope(String name, Scope outer, int depth) {
        static Scope push(Scope outer, String name) {
            return new Scope(name, outer, depth(outer) + 1);
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

    /** Where a term is: the child taken at each step from the root, the last step first. */
    private record Path(int index, Path parent, int length) {
        static Path child(Path parent, int index) {
            return new Path(index, parent, parent == null ? 1 : parent.length() + 1);
        }

        static int[] steps(Path path) {
            int[] steps = new int[path == null ? 0 : path.length()];
            for (int i = steps.length - 1; path != null; i--, path = path.parent()) steps[i] = path.index();
            return steps;
        }
    }

    /** A binding {@code [(\name. body) value]} of a candidate, where it is and under which binders. */
    private record Occurrence(String name, Term.Apply binding, Scope scope, Path path) {
        Term value() {
            return binding.argument();
        }

        /** The binder's own depth: one more than the binders around the binding. */
        int binderDepth() {
            return Scope.depth(scope) + 1;
        }
    }

    private record Frame(Term term, Scope scope, Path path) {
    }

    private record Binder(String name, int depth) {
    }

    /** Where the shared bindings go: the term's steps from the root, and the binders around it. */
    private record Point(int[] steps, Scope scope) {
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
        var occurrences = occurrences(term, handlers.names());

        var shared = new LinkedHashSet<String>();
        for (var entry : occurrences.entrySet()) {
            var found = entry.getValue();
            var first = found.getFirst();
            if (found.size() == handlers.handlers() && !UplcOptimizer.inlinesWhenUsedOnce(first.value())
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
            var binders = new HashSet<Binder>();
            for (String name : shared) {
                for (var occurrence : occurrences.get(name)) binders.add(new Binder(name, occurrence.binderDepth()));
            }
            dependencies = new HashMap<>();
            var excluded = new HashSet<String>();
            for (String name : shared) {
                var reads = new LinkedHashSet<String>();
                for (var occurrence : occurrences.get(name)) {
                    if (!readsOnlyWhatIsInScope(occurrence, point.depth(), shared, binders, reads)
                            || holdsAnother(occurrence, occurrences, shared)) {
                        excluded.add(name);
                    }
                }
                dependencies.put(name, reads);
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

    /** Every binding of a candidate, by name in the order first met, with where it is. */
    private static Map<String, List<Occurrence>> occurrences(Term term, Set<String> names) {
        var occurrences = new LinkedHashMap<String, List<Occurrence>>();
        var pending = new ArrayDeque<Frame>();
        pending.push(new Frame(term, null, null));
        while (!pending.isEmpty()) {
            var frame = pending.pop();
            var scope = frame.scope();
            var path = frame.path();
            switch (frame.term()) {
                case Term.Apply apply -> {
                    if (apply.function() instanceof Term.Lam(var name, var _) && names.contains(name)) {
                        occurrences.computeIfAbsent(name, n -> new ArrayList<>()).add(new Occurrence(name, apply, scope, path));
                    }
                    pending.push(new Frame(apply.argument(), scope, Path.child(path, 1)));
                    pending.push(new Frame(apply.function(), scope, Path.child(path, 0)));
                }
                case Term.Lam(var name, var body) -> pending.push(new Frame(body, Scope.push(scope, name), Path.child(path, 0)));
                case Term.Force(var inner) -> pending.push(new Frame(inner, scope, Path.child(path, 0)));
                case Term.Delay(var inner) -> pending.push(new Frame(inner, scope, Path.child(path, 0)));
                case Term.Constr(var _, var fields) -> {
                    for (int i = fields.size() - 1; i >= 0; i--) pending.push(new Frame(fields.get(i), scope, Path.child(path, i)));
                }
                case Term.Case(var scrutinee, var branches) -> {
                    for (int i = branches.size() - 1; i >= 0; i--) {
                        pending.push(new Frame(branches.get(i), scope, Path.child(path, i + 1)));
                    }
                    pending.push(new Frame(scrutinee, scope, Path.child(path, 0)));
                }
                default -> {
                }
            }
        }
        return occurrences;
    }

    /**
     * The smallest term holding every binding of a shared method, or null if that is one of the bindings. It is an
     * application, a constructor or a case whose children lead to different bindings.
     */
    private static Point point(Term term, Map<String, List<Occurrence>> occurrences, Set<String> shared) {
        int[] common = null;
        for (String name : shared) {
            for (var occurrence : occurrences.get(name)) {
                int[] steps = Path.steps(occurrence.path());
                if (common == null) {
                    common = steps;
                } else {
                    int length = 0;
                    while (length < common.length && length < steps.length && common[length] == steps[length]) length++;
                    if (length < common.length) common = Arrays.copyOf(common, length);
                }
            }
        }
        for (String name : shared) {
            for (var occurrence : occurrences.get(name)) {
                if (occurrence.path() == null || occurrence.path().length() <= common.length) return null;
            }
        }
        Scope scope = null;
        Term node = term;
        for (int step : common) {
            if (node instanceof Term.Lam(var name, var _)) scope = Scope.push(scope, name);
            node = child(node, step);
        }
        return new Point(common, scope);
    }

    /**
     * Whether every variable {@code occurrence}'s value reads from outside it is bound at {@code depth} or above, where
     * the shared bindings go, or is the binder of another shared method; those it reads go into {@code reads}.
     */
    private static boolean readsOnlyWhatIsInScope(Occurrence occurrence, int depth, Set<String> shared, Set<Binder> binders,
                                                  Set<String> reads) {
        record Visit(Term term, int local) {}
        var pending = new ArrayDeque<Visit>();
        pending.push(new Visit(occurrence.value(), 0));
        while (!pending.isEmpty()) {
            var visit = pending.pop();
            int local = visit.local();
            switch (visit.term()) {
                case Term.Var(var variable) -> {
                    if (variable.index() > local) {
                        var binder = Scope.at(occurrence.scope(), variable.index() - local);
                        if (binder == null) return false;
                        if (binder.depth() > depth) {
                            if (!shared.contains(binder.name()) || !binders.contains(new Binder(binder.name(), binder.depth()))) {
                                return false;
                            }
                            reads.add(binder.name());
                        }
                    }
                }
                case Term.Lam(var _, var body) -> pending.push(new Visit(body, local + 1));
                case Term.Apply(var function, var argument) -> {
                    pending.push(new Visit(function, local));
                    pending.push(new Visit(argument, local));
                }
                case Term.Force(var inner) -> pending.push(new Visit(inner, local));
                case Term.Delay(var inner) -> pending.push(new Visit(inner, local));
                case Term.Constr(var _, var fields) -> {
                    for (var field : fields) pending.push(new Visit(field, local));
                }
                case Term.Case(var scrutinee, var branches) -> {
                    pending.push(new Visit(scrutinee, local));
                    for (var branch : branches) pending.push(new Visit(branch, local));
                }
                default -> {
                }
            }
        }
        return true;
    }

    /** Whether {@code occurrence}'s value holds a binding of a shared method, which would move with it. */
    private static boolean holdsAnother(Occurrence occurrence, Map<String, List<Occurrence>> occurrences, Set<String> shared) {
        int[] value = Path.steps(Path.child(occurrence.path(), 1));
        for (String name : shared) {
            for (var other : occurrences.get(name)) {
                int[] steps = Path.steps(other.path());
                if (steps.length <= value.length) continue;
                boolean inside = true;
                for (int i = 0; inside && i < value.length; i++) inside = steps[i] == value[i];
                if (inside) return true;
            }
        }
        return false;
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

    /** {@code names} with each after those it reads, ties in the order given; null if they read each other in a cycle. */
    private static List<String> dependencyOrder(Set<String> names, Map<String, Set<String>> dependencies) {
        var order = new ArrayList<String>();
        var placed = new HashSet<String>();
        while (order.size() < names.size()) {
            String next = null;
            for (String name : names) {
                if (!placed.contains(name) && placed.containsAll(dependencies.get(name))) {
                    next = name;
                    break;
                }
            }
            if (next == null) return null;
            order.add(next);
            placed.add(next);
        }
        return order;
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
     * which no other binder has; any other binder must also be at the same depth, as one bound above both handlers is.
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
                        if (!candidates.contains(outerA.name()) && outerA.depth() != outerB.depth()) return false;
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
