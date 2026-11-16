package org.julclang.compiler.uplc;

import org.julclang.core.Term;
import org.julclang.core.recursion.Trampoline;

import java.util.ArrayList;
import java.util.List;

/**
 * The inverse of {@link ClosedValueSharing} (ADR-076 D2), for tests: the root bindings it adds, counted and substituted
 * back into the term they wrap.
 */
public final class SharedClosedValues {

    private SharedClosedValues() {
    }

    /** The values bound at the root of a term, outermost first, and the term they wrap. */
    private record Peeled(List<Term> values, Term body) {}

    private static Peeled peel(Term term) {
        var values = new ArrayList<Term>();
        while (term instanceof Term.Apply(Term.Lam(var name, var body), var value) && name.startsWith("#")
                && (value.equals(ClosedValueSharing.Z) || isForcedBuiltin(value))) {
            values.add(value);
            term = body;
        }
        return new Peeled(values, term);
    }

    /** How many closed values {@link ClosedValueSharing} bound at the root of {@code term}. */
    public static int count(Term term) {
        return peel(term).values().size();
    }

    /** {@code term} with the bindings {@link ClosedValueSharing} put at its root substituted back. */
    public static Term unshare(Term term) {
        var peeled = peel(term);
        if (peeled.values().isEmpty()) return term;
        // Innermost first: index 1 is the last one bound.
        return substitute(peeled.body(), 0, peeled.values().reversed()).run();
    }

    private static boolean isForcedBuiltin(Term term) {
        int forces = 0;
        while (term instanceof Term.Force(var inner)) {
            forces++;
            term = inner;
        }
        return forces > 0 && term instanceof Term.Builtin;
    }

    /** Replaces each variable that reaches past the {@code depth} lambdas inside the term with its root value. */
    private static Trampoline<Term> substitute(Term term, int depth, List<Term> values) {
        return Trampoline.defer(() -> switch (term) {
            case Term.Var(var name) -> Trampoline.done(name.index() > depth ? values.get(name.index() - depth - 1) : term);
            case Term.Lam(var name, var body) -> substitute(body, depth + 1, values).map(b -> (Term) Term.lam(name, b));
            case Term.Apply(var f, var a) -> substitute(f, depth, values)
                    .then(nf -> substitute(a, depth, values).map(na -> (Term) Term.apply(nf, na)));
            case Term.Force(var inner) -> substitute(inner, depth, values).map(i -> (Term) Term.force(i));
            case Term.Delay(var inner) -> substitute(inner, depth, values).map(i -> (Term) Term.delay(i));
            case Term.Constr c -> Trampoline.each(c.fields(), f -> substitute(f, depth, values))
                    .map(fields -> (Term) new Term.Constr(c.tag(), fields));
            case Term.Case c -> substitute(c.scrutinee(), depth, values).then(sc -> Trampoline.each(c.branches(),
                    br -> substitute(br, depth, values)).map(branches -> (Term) new Term.Case(sc, branches)));
            default -> Trampoline.done(term);
        });
    }
}
