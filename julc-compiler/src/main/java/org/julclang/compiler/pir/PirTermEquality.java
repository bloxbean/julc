package org.julclang.compiler.pir;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;

/**
 * The structural equality of {@link PirTerm}s that a record's {@code equals} would give, computed with a work list
 * instead of recursion, so that comparing two deep terms needs no more Java stack than comparing two shallow ones
 * (issue #327). A record compares its components and a list its elements; which pair is compared first cannot change
 * whether all are equal, so this gives the same answer. The components that hold no term (names, types, constants,
 * tags) are compared as the record compares them.
 */
final class PirTermEquality {

    private PirTermEquality() {
    }

    static boolean equal(PirTerm left, PirTerm right) {
        var pending = new ArrayDeque<PirTerm>();
        push(pending, left, right);
        while (!pending.isEmpty()) {
            PirTerm a = pending.pop();
            PirTerm b = pending.pop();
            if (a == b) continue;
            if (a.getClass() != b.getClass()) return false;
            boolean same = switch (a) {
                case PirTerm.Let let -> {
                    var other = (PirTerm.Let) b;
                    push(pending, let.body(), other.body());
                    push(pending, let.value(), other.value());
                    yield Objects.equals(let.name(), other.name());
                }
                case PirTerm.LetRec rec -> {
                    var other = (PirTerm.LetRec) b;
                    if (rec.bindings().size() != other.bindings().size()) yield false;
                    push(pending, rec.body(), other.body());
                    boolean names = true;
                    for (int i = rec.bindings().size() - 1; i >= 0; i--) {
                        var mine = rec.bindings().get(i);
                        var theirs = other.bindings().get(i);
                        names &= Objects.equals(mine.name(), theirs.name());
                        push(pending, mine.value(), theirs.value());
                    }
                    yield names;
                }
                case PirTerm.Lam lam -> {
                    var other = (PirTerm.Lam) b;
                    push(pending, lam.body(), other.body());
                    yield Objects.equals(lam.param(), other.param()) && Objects.equals(lam.paramType(), other.paramType());
                }
                case PirTerm.App app -> {
                    var other = (PirTerm.App) b;
                    push(pending, app.argument(), other.argument());
                    push(pending, app.function(), other.function());
                    yield true;
                }
                case PirTerm.IfThenElse ite -> {
                    var other = (PirTerm.IfThenElse) b;
                    push(pending, ite.elseBranch(), other.elseBranch());
                    push(pending, ite.thenBranch(), other.thenBranch());
                    push(pending, ite.cond(), other.cond());
                    yield true;
                }
                case PirTerm.IntegerCase c -> {
                    var other = (PirTerm.IntegerCase) b;
                    if (!pushAll(pending, c.branches(), other.branches())) yield false;
                    push(pending, c.scrutinee(), other.scrutinee());
                    yield true;
                }
                case PirTerm.PairMatch m -> {
                    var other = (PirTerm.PairMatch) b;
                    push(pending, m.body(), other.body());
                    push(pending, m.scrutinee(), other.scrutinee());
                    yield Objects.equals(m.pairType(), other.pairType()) && m.firstName().equals(other.firstName())
                            && m.secondName().equals(other.secondName());
                }
                case PirTerm.ListMatch m -> {
                    var other = (PirTerm.ListMatch) b;
                    push(pending, m.consBranch(), other.consBranch());
                    push(pending, m.nilBranch(), other.nilBranch());
                    push(pending, m.scrutinee(), other.scrutinee());
                    yield m.headName().equals(other.headName()) && m.tailName().equals(other.tailName());
                }
                case PirTerm.DataConstr c -> {
                    var other = (PirTerm.DataConstr) b;
                    yield c.tag() == other.tag() && Objects.equals(c.dataType(), other.dataType())
                            && pushAll(pending, c.fields(), other.fields());
                }
                case PirTerm.DataMatch m -> {
                    var other = (PirTerm.DataMatch) b;
                    if (m.branches().size() != other.branches().size()) yield false;
                    boolean shapes = true;
                    for (int i = m.branches().size() - 1; i >= 0; i--) {
                        var mine = m.branches().get(i);
                        var theirs = other.branches().get(i);
                        shapes &= Objects.equals(mine.constructorName(), theirs.constructorName())
                                && mine.bindings().equals(theirs.bindings())
                                && mine.bindingTypes().equals(theirs.bindingTypes())
                                && Objects.equals(mine.patternVar(), theirs.patternVar());
                        push(pending, mine.body(), theirs.body());
                    }
                    push(pending, m.scrutinee(), other.scrutinee());
                    yield shapes;
                }
                case PirTerm.Trace t -> {
                    var other = (PirTerm.Trace) b;
                    push(pending, t.body(), other.body());
                    push(pending, t.message(), other.message());
                    yield true;
                }
                case PirTerm.Var _, PirTerm.Const _, PirTerm.Builtin _, PirTerm.Error _ -> Objects.equals(a, b);
            };
            if (!same) return false;
        }
        return true;
    }

    /** Pushes the pair so that {@code a} and {@code b} are popped together, {@code a} first. */
    private static void push(ArrayDeque<PirTerm> pending, PirTerm a, PirTerm b) {
        pending.push(b);
        pending.push(a);
    }

    private static boolean pushAll(ArrayDeque<PirTerm> pending, List<PirTerm> a, List<PirTerm> b) {
        if (a.size() != b.size()) return false;
        for (int i = a.size() - 1; i >= 0; i--) push(pending, a.get(i), b.get(i));
        return true;
    }
}
