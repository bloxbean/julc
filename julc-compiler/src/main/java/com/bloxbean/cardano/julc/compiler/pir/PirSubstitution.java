package com.bloxbean.cardano.julc.compiler.pir;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Capture-avoiding substitution for PIR terms.
 * <p>
 * Used by the multi-binding LetRec transformation (Bekic's theorem)
 * to replace free occurrences of a variable with a replacement term.
 */
public final class PirSubstitution {

    private PirSubstitution() {}

    /**
     * Replace all free occurrences of {@code varName} in {@code term} with {@code replacement}.
     * Respects variable shadowing: if a binding introduces a variable with the same name,
     * the replacement does NOT occur inside that binding's body.
     */
    public static PirTerm substitute(PirTerm term, String varName, PirTerm replacement) {
        return switch (term) {
            case PirTerm.Var(var name, var type) ->
                    name.equals(varName) ? replacement : term;

            case PirTerm.Const _ -> term;

            case PirTerm.Builtin _ -> term;

            case PirTerm.Error _ -> term;

            case PirTerm.Let(var name, var value, var body) -> {
                var newValue = substitute(value, varName, replacement);
                // If Let binds the same name, it shadows — don't substitute in body
                var newBody = name.equals(varName) ? body : substitute(body, varName, replacement);
                yield new PirTerm.Let(name, newValue, newBody);
            }

            case PirTerm.Lam(var param, var paramType, var body) -> {
                // If lambda parameter shadows varName, don't substitute in body
                if (param.equals(varName)) {
                    yield term;
                }
                yield new PirTerm.Lam(param, paramType, substitute(body, varName, replacement));
            }

            case PirTerm.App(var function, var argument) ->
                    new PirTerm.App(
                            substitute(function, varName, replacement),
                            substitute(argument, varName, replacement));

            case PirTerm.IfThenElse(var cond, var thenBranch, var elseBranch) ->
                    new PirTerm.IfThenElse(
                            substitute(cond, varName, replacement),
                            substitute(thenBranch, varName, replacement),
                            substitute(elseBranch, varName, replacement));

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
                    newBindings.add(new PirTerm.Binding(
                            binding.name(),
                            substitute(binding.value(), varName, replacement)));
                }
                yield new PirTerm.LetRec(newBindings, substitute(body, varName, replacement));
            }

            case PirTerm.DataConstr(var tag, var dataType, var fields) -> {
                var newFields = new ArrayList<PirTerm>();
                for (var field : fields) {
                    newFields.add(substitute(field, varName, replacement));
                }
                yield new PirTerm.DataConstr(tag, dataType, newFields);
            }

            case PirTerm.DataMatch(var scrutinee, var branches) -> {
                var newScrutinee = substitute(scrutinee, varName, replacement);
                var newBranches = new ArrayList<PirTerm.MatchBranch>();
                for (var branch : branches) {
                    // If varName is bound in this branch's bindings, don't substitute in body
                    boolean boundInBranch = branch.bindings().contains(varName)
                            || varName.equals(branch.patternVar());
                    var newBody = boundInBranch ? branch.body()
                            : substitute(branch.body(), varName, replacement);
                    newBranches.add(new PirTerm.MatchBranch(
                            branch.constructorName(), branch.bindings(),
                            branch.bindingTypes(), newBody, branch.patternVar()));
                }
                yield new PirTerm.DataMatch(newScrutinee, newBranches);
            }

            case PirTerm.Trace(var message, var body) ->
                    new PirTerm.Trace(
                            substitute(message, varName, replacement),
                            substitute(body, varName, replacement));
        };
    }

    /**
     * Collect the names of all free variables in a PIR term.
     * Used for dependency analysis in multi-binding LetRec.
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
