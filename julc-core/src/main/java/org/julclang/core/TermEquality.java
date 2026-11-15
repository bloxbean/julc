package org.julclang.core;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;

/**
 * The structural equality of {@link Term}s that a record's {@code equals} would give, computed with a work list
 * instead of recursion, so that comparing two deep terms needs no more Java stack than comparing two shallow ones
 * (issue #327). A record compares its components in order and a list its elements; which pair is compared first
 * cannot change whether all are equal, so this gives the same answer.
 */
final class TermEquality {

    private TermEquality() {
    }

    static boolean equal(Term left, Term right) {
        var pending = new ArrayDeque<Term>();
        pending.push(right);
        pending.push(left);
        while (!pending.isEmpty()) {
            Term a = pending.pop();
            Term b = pending.pop();
            if (a == b) continue;
            if (a.getClass() != b.getClass()) return false;
            switch (a) {
                case Term.Lam lam -> {
                    var other = (Term.Lam) b;
                    if (!lam.paramName().equals(other.paramName())) return false;
                    push(pending, lam.body(), other.body());
                }
                case Term.Apply apply -> {
                    var other = (Term.Apply) b;
                    push(pending, apply.argument(), other.argument());
                    push(pending, apply.function(), other.function());
                }
                case Term.Force force -> push(pending, force.term(), ((Term.Force) b).term());
                case Term.Delay delay -> push(pending, delay.term(), ((Term.Delay) b).term());
                case Term.Constr constr -> {
                    var other = (Term.Constr) b;
                    if (constr.tag() != other.tag() || !pushAll(pending, constr.fields(), other.fields())) return false;
                }
                case Term.Case match -> {
                    var other = (Term.Case) b;
                    if (!pushAll(pending, match.branches(), other.branches())) return false;
                    push(pending, match.scrutinee(), other.scrutinee());
                }
                case Term.Var _, Term.Const _, Term.Builtin _, Term.Error _ -> {
                    if (!Objects.equals(a, b)) return false;
                }
            }
        }
        return true;
    }

    private static void push(ArrayDeque<Term> pending, Term a, Term b) {
        pending.push(b);
        pending.push(a);
    }

    private static boolean pushAll(ArrayDeque<Term> pending, List<Term> a, List<Term> b) {
        if (a.size() != b.size()) return false;
        for (int i = a.size() - 1; i >= 0; i--) push(pending, a.get(i), b.get(i));
        return true;
    }
}
