package org.julclang.compiler.uplc;

import org.julclang.core.NamedDeBruijn;
import org.julclang.core.Program;
import org.julclang.core.Term;
import org.julclang.core.flat.UplcFlatEncoder;

import java.util.HexFormat;

/**
 * The oracle for ADR-076 D1, which only moves method bindings: a program with every method binding,
 * {@code [(\Owner.m. body) value]}, inlined. A method's binder is the only one whose name has a dot.
 */
public final class InlinedMethods {

    private InlinedMethods() {
    }

    /**
     * {@code term} with every method binding, {@code [(\Owner.m. body) value]}, replaced by {@code body} with
     * {@code value} substituted: the oracle for D1, which only moves those bindings.
     */
    public static Term inline(Term term) {
        return switch (term) {
            case Term.Apply(var function, var argument) -> {
                var value = inline(argument);
                if (function instanceof Term.Lam(var name, var body) && name.contains(".")) {
                    yield substitute(inline(body), 0, value);
                }
                yield Term.apply(inline(function), value);
            }
            case Term.Lam(var name, var body) -> Term.lam(name, inline(body));
            case Term.Force(var inner) -> Term.force(inline(inner));
            case Term.Delay(var inner) -> Term.delay(inline(inner));
            case Term.Constr(var tag, var fields) -> new Term.Constr(tag, fields.stream().map(InlinedMethods::inline).toList());
            case Term.Case(var scrutinee, var branches) ->
                    new Term.Case(inline(scrutinee), branches.stream().map(InlinedMethods::inline).toList());
            default -> term;
        };
    }

    /** {@code term} with every method binding inlined, as FLAT hex: a form that keeps no binder names. */
    public static String flat(Term term) {
        return HexFormat.of().formatHex(UplcFlatEncoder.encodeProgram(Program.plutusV3(inline(term))));
    }

    /** {@code term}, under {@code depth} binders of its own, with the variable just outside them replaced by {@code value}. */
    private static Term substitute(Term term, int depth, Term value) {
        return switch (term) {
            case Term.Var(var variable) -> {
                int index = variable.index();
                if (index == depth + 1) yield shift(value, depth, 0);
                yield index > depth + 1 ? new Term.Var(new NamedDeBruijn(variable.name(), index - 1)) : term;
            }
            case Term.Lam(var name, var body) -> Term.lam(name, substitute(body, depth + 1, value));
            case Term.Apply(var function, var argument) -> Term.apply(substitute(function, depth, value), substitute(argument, depth, value));
            case Term.Force(var inner) -> Term.force(substitute(inner, depth, value));
            case Term.Delay(var inner) -> Term.delay(substitute(inner, depth, value));
            case Term.Constr(var tag, var fields) -> new Term.Constr(tag, fields.stream().map(f -> substitute(f, depth, value)).toList());
            case Term.Case(var scrutinee, var branches) -> new Term.Case(substitute(scrutinee, depth, value),
                    branches.stream().map(b -> substitute(b, depth, value)).toList());
            default -> term;
        };
    }

    /** {@code term} with each variable bound outside its first {@code cutoff} binders moved out by {@code by}. */
    private static Term shift(Term term, int by, int cutoff) {
        if (by == 0) return term;
        return switch (term) {
            case Term.Var(var variable) -> variable.index() > cutoff
                    ? new Term.Var(new NamedDeBruijn(variable.name(), variable.index() + by)) : term;
            case Term.Lam(var name, var body) -> Term.lam(name, shift(body, by, cutoff + 1));
            case Term.Apply(var function, var argument) -> Term.apply(shift(function, by, cutoff), shift(argument, by, cutoff));
            case Term.Force(var inner) -> Term.force(shift(inner, by, cutoff));
            case Term.Delay(var inner) -> Term.delay(shift(inner, by, cutoff));
            case Term.Constr(var tag, var fields) -> new Term.Constr(tag, fields.stream().map(f -> shift(f, by, cutoff)).toList());
            case Term.Case(var scrutinee, var branches) -> new Term.Case(shift(scrutinee, by, cutoff),
                    branches.stream().map(b -> shift(b, by, cutoff)).toList());
            default -> term;
        };
    }

}
