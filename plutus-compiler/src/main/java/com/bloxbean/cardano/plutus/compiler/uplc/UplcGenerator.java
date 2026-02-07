package com.bloxbean.cardano.plutus.compiler.uplc;

import com.bloxbean.cardano.plutus.compiler.CompilerException;
import com.bloxbean.cardano.plutus.compiler.pir.PirTerm;
import com.bloxbean.cardano.plutus.core.DefaultFun;
import com.bloxbean.cardano.plutus.core.Term;

import java.util.*;

/**
 * Translates PIR terms to UPLC terms.
 * Performs type erasure and De Bruijn index computation.
 */
public class UplcGenerator {

    private final Deque<String> scope = new ArrayDeque<>();

    public Term generate(PirTerm pir) {
        return switch (pir) {
            case PirTerm.Var(var name, _) -> {
                // Field accessor pseudo-variables are handled by their containing App
                if (name.startsWith(".")) {
                    throw new CompilerException("Bare field accessor not supported: " + name);
                }
                yield Term.var(deBruijnIndex(name));
            }

            case PirTerm.Const(var value) -> Term.const_(value);

            case PirTerm.Builtin(var fun) -> wrapForces(Term.builtin(fun), forceCount(fun));

            case PirTerm.Lam(var param, _, var body) -> {
                scope.push(param);
                var bodyTerm = generate(body);
                scope.pop();
                yield Term.lam(param, bodyTerm);
            }

            case PirTerm.App(var function, var argument) -> {
                // Handle field accessor: App(Var(".field"), scope) -> field extraction
                if (function instanceof PirTerm.Var(var name, _) && name.startsWith(".")) {
                    // For MVP, field access on Data-typed values is just passed through
                    // The ValidatorWrapper/DataCodecGenerator handles the actual field extraction
                    yield Term.apply(
                            Term.var(deBruijnIndex(name.substring(1))),
                            generate(argument));
                }
                yield Term.apply(generate(function), generate(argument));
            }

            case PirTerm.Let(var name, var value, var body) -> {
                // Let(name, val, body) -> Apply(Lam(name, body'), val')
                var valTerm = generate(value);
                scope.push(name);
                var bodyTerm = generate(body);
                scope.pop();
                yield Term.apply(Term.lam(name, bodyTerm), valTerm);
            }

            case PirTerm.LetRec letRec -> generateLetRec(letRec);

            case PirTerm.IfThenElse(var cond, var thenBranch, var elseBranch) -> {
                // Force(Apply(Apply(Apply(Force(Builtin(IfThenElse)), cond), Delay(then)), Delay(else)))
                var ifBuiltin = Term.force(Term.builtin(DefaultFun.IfThenElse));
                yield Term.force(
                        Term.apply(
                                Term.apply(
                                        Term.apply(ifBuiltin, generate(cond)),
                                        Term.delay(generate(thenBranch))),
                                Term.delay(generate(elseBranch))));
            }

            case PirTerm.DataConstr(var tag, _, var fields) -> {
                var fieldTerms = new ArrayList<Term>();
                for (var field : fields) {
                    fieldTerms.add(generate(field));
                }
                yield new Term.Constr(tag, fieldTerms);
            }

            case PirTerm.DataMatch(var scrutinee, var branches) -> {
                var branchTerms = new ArrayList<Term>();
                for (var branch : branches) {
                    // Push binding names into scope (innermost = last binding)
                    for (var binding : branch.bindings()) {
                        scope.push(binding);
                    }
                    var branchBody = generate(branch.body());
                    // Pop bindings
                    for (int i = 0; i < branch.bindings().size(); i++) {
                        scope.pop();
                    }
                    // Wrap in lambdas for each binding (outermost = first binding)
                    for (int i = branch.bindings().size() - 1; i >= 0; i--) {
                        branchBody = Term.lam(branch.bindings().get(i), branchBody);
                    }
                    branchTerms.add(branchBody);
                }
                yield new Term.Case(generate(scrutinee), branchTerms);
            }

            case PirTerm.Error _ -> Term.error();

            case PirTerm.Trace(var message, var body) -> {
                // Force(Apply(Apply(Force(Builtin(Trace)), msg), Delay(body)))
                // Trace is polymorphic (1 Force), so: Force(Builtin(Trace))
                var traceBuiltin = Term.force(Term.builtin(DefaultFun.Trace));
                yield Term.force(
                        Term.apply(
                                Term.apply(traceBuiltin, generate(message)),
                                Term.delay(generate(body))));
            }
        };
    }

    private Term generateLetRec(PirTerm.LetRec letRec) {
        // Z-combinator implementation for recursive bindings.
        // For single binding: LetRec([name = body], expr)
        //   → Let(name, Apply(fix, Lam(name, body')), expr')
        // where fix = \f -> (\x -> f (\v -> x x v)) (\x -> f (\v -> x x v))

        if (letRec.bindings().size() == 1) {
            var binding = letRec.bindings().getFirst();
            var name = binding.name();
            var value = binding.value();

            // Build the Z-combinator:
            // fix = \f -> (\x -> f (\v -> x x v)) (\x -> f (\v -> x x v))
            // In UPLC with De Bruijn indices:
            // fix = Lam("f", Apply(
            //   Lam("x", Apply(Var(2), Lam("v", Apply(Apply(Var(2), Var(2)), Var(1))))),
            //   Lam("x", Apply(Var(2), Lam("v", Apply(Apply(Var(2), Var(2)), Var(1)))))))

            var innerBody = Term.lam("v",
                    Term.apply(Term.apply(Term.var(2), Term.var(2)), Term.var(1)));
            var branch = Term.lam("x", Term.apply(Term.var(2), innerBody));
            var fix = Term.lam("f", Term.apply(branch, branch));

            // Generate the recursive function body: Lam(name, body')
            // The body references 'name' which is the recursive reference
            scope.push(name);
            var bodyTerm = generate(value);
            scope.pop();
            var recursiveLam = Term.lam(name, bodyTerm);

            // Apply fix to the recursive lambda
            var fixedFn = Term.apply(fix, recursiveLam);

            // Now bind name = fixedFn and generate the expression
            scope.push(name);
            var exprTerm = generate(letRec.body());
            scope.pop();

            return Term.apply(Term.lam(name, exprTerm), fixedFn);
        }

        // For multiple bindings, treat as sequential lets (fallback)
        PirTerm result = letRec.body();
        for (int i = letRec.bindings().size() - 1; i >= 0; i--) {
            var binding = letRec.bindings().get(i);
            result = new PirTerm.Let(binding.name(), binding.value(), result);
        }
        return generate(result);
    }

    private int deBruijnIndex(String name) {
        int index = 1; // De Bruijn indices are 1-based
        for (var n : scope) {
            if (n.equals(name)) return index;
            index++;
        }
        throw new CompilerException("Unbound variable: " + name);
    }

    /**
     * Get the number of Force wrappers needed for a polymorphic builtin.
     */
    static int forceCount(DefaultFun fun) {
        return switch (fun) {
            // 2 Forces (2 type variables: ∀ a b)
            case FstPair, SndPair, ChooseList -> 2;
            // 1 Force (1 type variable: ∀ a)
            case IfThenElse, ChooseUnit, Trace, ChooseData,
                 SerialiseData, MkCons, HeadList, TailList, NullList -> 1;
            // 0 Forces (monomorphic)
            default -> 0;
        };
    }

    private static Term wrapForces(Term term, int count) {
        for (int i = 0; i < count; i++) {
            term = Term.force(term);
        }
        return term;
    }
}
