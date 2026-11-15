package org.julclang.decompiler.input;

import org.julclang.core.BuiltinSemantics;
import org.julclang.core.Program;
import org.julclang.core.Term;
import org.julclang.core.recursion.Trampoline;

import java.util.ArrayList;
import java.util.List;

/**
 * Undoes JuLC's closed-value sharing (ADR-076 D2) before a script is decompiled. JuLC binds a repeated forced builtin
 * and its fixpoint combinator once, at the script's root, so the root of such a script is a chain of
 * {@code [(\s. body) value]} before the validator's own lambdas. The decompiler reads a validator's parameters from
 * those lambdas, so this substitutes each root binding back into the body, which gives the term as it was before the
 * sharing, exactly: the values are closed, and the transformation was a beta-expansion.
 */
public final class SharedRootBindings {

    private SharedRootBindings() {
    }

    /**
     * Whether {@code term} is the Z combinator as JuLC writes it, {@code \f. (\x. f (\v. x x v)) (\x. f (\v. x x v))},
     * compared by de Bruijn indices only: a script decoded from FLAT names its lambdas afresh.
     */
    static boolean isZ(Term term) {
        return term instanceof Term.Lam(_, Term.Apply(var left, var right)) && isZBranch(left) && isZBranch(right);
    }

    /** {@code \x. f (\v. x x v)} under the binder of {@code f}: {@code \ [2 (\ [[2 2] 1])]}. */
    private static boolean isZBranch(Term term) {
        return term instanceof Term.Lam(_, Term.Apply(Term.Var(var f), Term.Lam(_,
                Term.Apply(Term.Apply(Term.Var(var x1), Term.Var(var x2)), Term.Var(var v1)))))
                && f.index() == 2 && x1.index() == 2 && x2.index() == 2 && v1.index() == 1;
    }

    /** {@code program} with its shared root bindings substituted back; {@code program} itself if it has none. */
    public static Program inline(Program program) {
        var values = new ArrayList<Term>();
        Term term = program.term();
        while (term instanceof Term.Apply(Term.Lam(var name, var body), var value) && isSharedValue(value)) {
            values.addFirst(value); // innermost first: de Bruijn index 1 is the last one peeled
            term = body;
        }
        if (values.isEmpty()) return program;
        return new Program(program.major(), program.minor(), program.patch(), substitute(term, 0, values).run());
    }

    private static boolean isSharedValue(Term value) {
        if (value instanceof Term.Lam) return isZ(value);
        int forces = 0;
        Term inner = value;
        while (inner instanceof Term.Force(var next)) {
            forces++;
            inner = next;
        }
        if (forces == 0 || !(inner instanceof Term.Builtin(var fun))) return false;
        var signature = BuiltinSemantics.find(fun);
        return signature != null && signature.typeArity() == forces;
    }

    /** Each variable that reaches past the {@code depth} lambdas inside the body replaced by its root value. */
    private static Trampoline<Term> substitute(Term term, int depth, List<Term> values) {
        return Trampoline.defer(() -> switch (term) {
            case Term.Var(var name) -> Trampoline.done(name.index() > depth && name.index() - depth <= values.size()
                    ? values.get(name.index() - depth - 1) : term);
            case Term.Lam(var name, var body) -> substitute(body, depth + 1, values).map(b -> (Term) Term.lam(name, b));
            case Term.Apply(var function, var argument) -> substitute(function, depth, values)
                    .then(f -> substitute(argument, depth, values).map(a -> (Term) Term.apply(f, a)));
            case Term.Force(var inner) -> substitute(inner, depth, values).map(i -> (Term) Term.force(i));
            case Term.Delay(var inner) -> substitute(inner, depth, values).map(i -> (Term) Term.delay(i));
            case Term.Constr constr -> Trampoline.each(constr.fields(), f -> substitute(f, depth, values))
                    .map(fields -> (Term) new Term.Constr(constr.tag(), fields));
            case Term.Case match -> substitute(match.scrutinee(), depth, values).then(s -> Trampoline.each(
                    match.branches(), b -> substitute(b, depth, values)).map(b -> (Term) new Term.Case(s, b)));
            case Term.Const _, Term.Builtin _, Term.Error _ -> Trampoline.done(term);
        });
    }
}
