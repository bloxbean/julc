package org.julclang.compiler.pir;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Capture-avoiding substitution for PIR terms.
 *
 * <p>Used by the multi-binding LetRec transformation (Bekic's theorem) to replace free occurrences
 * of a variable with a replacement term.
 */
public final class PirSubstitution {

    private PirSubstitution() {}

    /**
     * Replace all free occurrences of {@code varName} in {@code term} with {@code replacement}.
     * Respects variable shadowing: if a binding introduces a variable with the same name, the
     * replacement does NOT occur inside that binding's body.
     */
    public static PirTerm substitute(PirTerm term, String varName, PirTerm replacement) {
        if (!collectFreeVarNames(term).contains(varName)) return term;
        var hygiene = new Hygiene(collectFreeVarNames(replacement));
        hygiene.used.add(varName);
        hygiene.rewrite(term, Map.of());
        hygiene.rewrite(replacement, Map.of());
        hygiene.collecting = false;
        return substituteUnchecked(hygiene.rewrite(term, Map.of()), varName, replacement);
    }

    private static PirTerm substituteUnchecked(PirTerm term, String varName, PirTerm replacement) {
        return switch (term) {
            case PirTerm.Var(var name, var type) -> name.equals(varName) ? replacement : term;

            case PirTerm.Const _ -> term;

            case PirTerm.Builtin _ -> term;

            case PirTerm.Error _ -> term;

            case PirTerm.Let(var name, var value, var body) -> {
                var newValue = substituteUnchecked(value, varName, replacement);
                // If Let binds the same name, it shadows — don't substitute in body
                var newBody =
                        name.equals(varName)
                                ? body
                                : substituteUnchecked(body, varName, replacement);
                yield new PirTerm.Let(name, newValue, newBody);
            }

            case PirTerm.Lam(var param, var paramType, var body) -> {
                // If lambda parameter shadows varName, don't substitute in body
                if (param.equals(varName)) {
                    yield term;
                }
                yield new PirTerm.Lam(
                        param, paramType, substituteUnchecked(body, varName, replacement));
            }

            case PirTerm.App(var function, var argument) ->
                    new PirTerm.App(
                            substituteUnchecked(function, varName, replacement),
                            substituteUnchecked(argument, varName, replacement));

            case PirTerm.IfThenElse(var cond, var thenBranch, var elseBranch) ->
                    new PirTerm.IfThenElse(
                            substituteUnchecked(cond, varName, replacement),
                            substituteUnchecked(thenBranch, varName, replacement),
                            substituteUnchecked(elseBranch, varName, replacement));

            case PirTerm.LetRec(var bindings, var body) -> {
                // If any binding name matches varName, all bindings are in mutual scope —
                // varName is shadowed throughout the entire LetRec
                boolean shadowed = bindings.stream().anyMatch(b -> b.name().equals(varName));
                if (shadowed) {
                    yield term;
                }
                // Substitute in all binding values and body
                var newBindings = new ArrayList<PirTerm.Binding>();
                for (var binding : bindings) {
                    newBindings.add(
                            new PirTerm.Binding(
                                    binding.name(),
                                    substituteUnchecked(binding.value(), varName, replacement)));
                }
                yield new PirTerm.LetRec(
                        newBindings, substituteUnchecked(body, varName, replacement));
            }

            case PirTerm.DataConstr(var tag, var dataType, var fields) -> {
                var newFields = new ArrayList<PirTerm>();
                for (var field : fields) {
                    newFields.add(substituteUnchecked(field, varName, replacement));
                }
                yield new PirTerm.DataConstr(tag, dataType, newFields);
            }

            case PirTerm.IntegerCase(var scrutinee, var branches) ->
                    new PirTerm.IntegerCase(
                            substituteUnchecked(scrutinee, varName, replacement),
                            branches.stream()
                                    .map(b -> substituteUnchecked(b, varName, replacement))
                                    .toList());

            case PirTerm.PairMatch(var pair, var type, var first, var second, var body) ->
                    new PirTerm.PairMatch(
                            substituteUnchecked(pair, varName, replacement),
                            type,
                            first,
                            second,
                            first.equals(varName) || second.equals(varName)
                                    ? body
                                    : substituteUnchecked(body, varName, replacement));

            case PirTerm.ListMatch(var xs, var head, var tail, var nil, var cons) ->
                    new PirTerm.ListMatch(
                            substituteUnchecked(xs, varName, replacement),
                            head,
                            tail,
                            substituteUnchecked(nil, varName, replacement),
                            head.equals(varName) || tail.equals(varName)
                                    ? cons
                                    : substituteUnchecked(cons, varName, replacement));

            case PirTerm.DataMatch(var scrutinee, var branches) -> {
                var newScrutinee = substituteUnchecked(scrutinee, varName, replacement);
                var newBranches = new ArrayList<PirTerm.MatchBranch>();
                for (var branch : branches) {
                    // If varName is bound in this branch's bindings, don't substitute in body
                    boolean boundInBranch =
                            branch.bindings().contains(varName)
                                    || varName.equals(branch.patternVar());
                    var newBody =
                            boundInBranch
                                    ? branch.body()
                                    : substituteUnchecked(branch.body(), varName, replacement);
                    newBranches.add(
                            new PirTerm.MatchBranch(
                                    branch.constructorName(),
                                    branch.bindings(),
                                    branch.bindingTypes(),
                                    newBody,
                                    branch.patternVar()));
                }
                yield new PirTerm.DataMatch(newScrutinee, newBranches);
            }

            case PirTerm.Trace(var message, var body) ->
                    new PirTerm.Trace(
                            substituteUnchecked(message, varName, replacement),
                            substituteUnchecked(body, varName, replacement));
        };
    }

    /** Rename conflicting binders, retaining each variable's original PIR type. */
    private static final class Hygiene {
        private final Set<String> forbidden;
        private final Set<String> used = new LinkedHashSet<>();
        private boolean collecting = true;
        private int next;

        private Hygiene(Set<String> forbidden) {
            this.forbidden = forbidden;
        }

        private String bind(String name, Map<String, String> scope) {
            used.add(name);
            String renamed = name;
            if (!collecting && forbidden.contains(name)) {
                do {
                    renamed = "$pir$subst$" + next++;
                } while (!used.add(renamed));
            }
            scope.put(name, renamed);
            return renamed;
        }

        private PirTerm rewrite(PirTerm term, Map<String, String> scope) {
            return switch (term) {
                case PirTerm.Var(var name, var type) -> {
                    used.add(name);
                    // Keep unrenamed occurrences identical: source maps and Java debug
                    // provenance are keyed by PIR node identity.
                    var renamed = scope.get(name);
                    yield renamed == null || renamed.equals(name)
                            ? term
                            : new PirTerm.Var(renamed, type);
                }
                case PirTerm.Const _, PirTerm.Builtin _, PirTerm.Error _ -> term;
                case PirTerm.Lam(var name, var type, var body) -> {
                    var local = new HashMap<>(scope);
                    var renamed = bind(name, local);
                    yield new PirTerm.Lam(renamed, type, rewrite(body, local));
                }
                case PirTerm.Let(var name, var value, var body) -> {
                    var local = new HashMap<>(scope);
                    var renamed = bind(name, local);
                    yield new PirTerm.Let(renamed, rewrite(value, scope), rewrite(body, local));
                }
                case PirTerm.LetRec(var bindings, var body) -> {
                    var local = new HashMap<>(scope);
                    for (var binding : bindings) bind(binding.name(), local);
                    yield new PirTerm.LetRec(
                            bindings.stream()
                                    .map(
                                            b ->
                                                    new PirTerm.Binding(
                                                            local.get(b.name()),
                                                            rewrite(b.value(), local)))
                                    .toList(),
                            rewrite(body, local));
                }
                case PirTerm.App(var f, var a) ->
                        new PirTerm.App(rewrite(f, scope), rewrite(a, scope));
                case PirTerm.IfThenElse(var c, var y, var n) ->
                        new PirTerm.IfThenElse(
                                rewrite(c, scope), rewrite(y, scope), rewrite(n, scope));
                case PirTerm.DataConstr(var tag, var type, var fields) ->
                        new PirTerm.DataConstr(
                                tag, type, fields.stream().map(f -> rewrite(f, scope)).toList());
                case PirTerm.IntegerCase(var value, var branches) ->
                        new PirTerm.IntegerCase(
                                rewrite(value, scope),
                                branches.stream().map(b -> rewrite(b, scope)).toList());
                case PirTerm.PairMatch(var value, var type, var first, var second, var body) -> {
                    var local = new HashMap<>(scope);
                    var a = bind(first, local);
                    var b = bind(second, local);
                    yield new PirTerm.PairMatch(
                            rewrite(value, scope), type, a, b, rewrite(body, local));
                }
                case PirTerm.ListMatch(var value, var head, var tail, var nil, var cons) -> {
                    var local = new HashMap<>(scope);
                    var h = bind(head, local);
                    var t = bind(tail, local);
                    yield new PirTerm.ListMatch(
                            rewrite(value, scope), h, t, rewrite(nil, scope), rewrite(cons, local));
                }
                case PirTerm.DataMatch(var value, var branches) -> {
                    var renamed = new ArrayList<PirTerm.MatchBranch>();
                    for (var branch : branches) {
                        var local = new HashMap<>(scope);
                        var names = branch.bindings().stream().map(n -> bind(n, local)).toList();
                        var pattern =
                                branch.patternVar() == null
                                        ? null
                                        : bind(branch.patternVar(), local);
                        renamed.add(
                                new PirTerm.MatchBranch(
                                        branch.constructorName(),
                                        names,
                                        branch.bindingTypes(),
                                        rewrite(branch.body(), local),
                                        pattern));
                    }
                    yield new PirTerm.DataMatch(rewrite(value, scope), renamed);
                }
                case PirTerm.Trace(var message, var body) ->
                        new PirTerm.Trace(rewrite(message, scope), rewrite(body, scope));
            };
        }
    }

    /**
     * Collect the names of all free variables in a PIR term. Used for dependency analysis in
     * multi-binding LetRec.
     */
    public static Set<String> collectFreeVarNames(PirTerm term) {
        var result = new LinkedHashSet<String>();
        collectFreeVars(term, new LinkedHashSet<>(), result);
        return result;
    }

    private static void collectFreeVars(PirTerm term, Set<String> bound, Set<String> free) {
        switch (term) {
            case PirTerm.Var(var name, _) -> {
                if (!bound.contains(name)) free.add(name);
            }
            case PirTerm.Const _ -> {}
            case PirTerm.Builtin _ -> {}
            case PirTerm.Error _ -> {}
            case PirTerm.Let(var name, var value, var body) -> {
                collectFreeVars(value, bound, free);
                var innerBound = new LinkedHashSet<>(bound);
                innerBound.add(name);
                collectFreeVars(body, innerBound, free);
            }
            case PirTerm.Lam(var param, _, var body) -> {
                var innerBound = new LinkedHashSet<>(bound);
                innerBound.add(param);
                collectFreeVars(body, innerBound, free);
            }
            case PirTerm.App(var function, var argument) -> {
                collectFreeVars(function, bound, free);
                collectFreeVars(argument, bound, free);
            }
            case PirTerm.IfThenElse(var cond, var thenBranch, var elseBranch) -> {
                collectFreeVars(cond, bound, free);
                collectFreeVars(thenBranch, bound, free);
                collectFreeVars(elseBranch, bound, free);
            }
            case PirTerm.LetRec(var bindings, var body) -> {
                var innerBound = new LinkedHashSet<>(bound);
                for (var binding : bindings) innerBound.add(binding.name());
                for (var binding : bindings) collectFreeVars(binding.value(), innerBound, free);
                collectFreeVars(body, innerBound, free);
            }
            case PirTerm.DataConstr(_, _, var fields) -> {
                for (var field : fields) collectFreeVars(field, bound, free);
            }
            case PirTerm.IntegerCase(var scrutinee, var branches) -> {
                collectFreeVars(scrutinee, bound, free);
                for (var branch : branches) collectFreeVars(branch, bound, free);
            }
            case PirTerm.PairMatch(var pair, _, var first, var second, var body) -> {
                collectFreeVars(pair, bound, free);
                var innerBound = new LinkedHashSet<>(bound);
                innerBound.add(first);
                innerBound.add(second);
                collectFreeVars(body, innerBound, free);
            }
            case PirTerm.ListMatch(var xs, var head, var tail, var nil, var cons) -> {
                collectFreeVars(xs, bound, free);
                collectFreeVars(nil, bound, free);
                var innerBound = new LinkedHashSet<>(bound);
                innerBound.add(head);
                innerBound.add(tail);
                collectFreeVars(cons, innerBound, free);
            }

            case PirTerm.DataMatch(var scrutinee, var branches) -> {
                collectFreeVars(scrutinee, bound, free);
                for (var branch : branches) {
                    var innerBound = new LinkedHashSet<>(bound);
                    innerBound.addAll(branch.bindings());
                    if (branch.patternVar() != null) innerBound.add(branch.patternVar());
                    collectFreeVars(branch.body(), innerBound, free);
                }
            }
            case PirTerm.Trace(var message, var body) -> {
                collectFreeVars(message, bound, free);
                collectFreeVars(body, bound, free);
            }
        }
    }
}
