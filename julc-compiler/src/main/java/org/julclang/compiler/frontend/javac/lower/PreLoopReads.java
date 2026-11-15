package org.julclang.compiler.frontend.javac.lower;

import org.julclang.compiler.pir.PirTerm;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The reads a term makes of some names, in the order the reference's {@code collectReferencedVars} meets them. It reads
 * spelling and not scope, as there: a binder inside the term hides nothing. A {@code PairMatch}'s binders, which there hide
 * their names, are generated, and no candidate is one. {@link Loops} orders its {@code let}s by this, so the bytes follow
 * it. Walking a term has no evaluation order to state.
 */
final class PreLoopReads {

    private PreLoopReads() {
    }

    /**
     * Walks {@code term} in pre-order with a heap stack, since {@code term} is what follows a loop, as deep as the rest of
     * its block (issue #327). Each node's children are pushed last first, so they are met in the order the recursive form
     * met them, which {@link #children} spells out.
     */
    static void collect(PirTerm term, Set<String> candidates, Map<String, PirTerm.Var> reads) {
        var pending = new ArrayDeque<PirTerm>();
        pending.push(term);
        while (!pending.isEmpty()) {
            var next = pending.pop();
            if (next instanceof PirTerm.Var read) {
                if (candidates.contains(read.name())) reads.putIfAbsent(read.name(), read);
                continue;
            }
            var children = children(next);
            for (int i = children.size() - 1; i >= 0; i--) pending.push(children.get(i));
        }
    }

    /** The terms {@code term} holds, in the order the reference reads them. */
    private static List<PirTerm> children(PirTerm term) {
        var children = new ArrayList<PirTerm>();
        switch (term) {
            case PirTerm.Let let -> {
                children.add(let.value());
                children.add(let.body());
            }
            case PirTerm.LetRec rec -> {
                rec.bindings().forEach(binding -> children.add(binding.value()));
                children.add(rec.body());
            }
            case PirTerm.Lam lam -> children.add(lam.body());
            case PirTerm.App app -> {
                children.add(app.function());
                children.add(app.argument());
            }
            case PirTerm.IfThenElse branch -> {
                children.add(branch.cond());
                children.add(branch.thenBranch());
                children.add(branch.elseBranch());
            }
            case PirTerm.DataConstr constr -> children.addAll(constr.fields());
            case PirTerm.IntegerCase select -> {
                children.add(select.scrutinee());
                children.addAll(select.branches());
            }
            case PirTerm.PairMatch match -> {
                children.add(match.scrutinee());
                children.add(match.body());
            }
            case PirTerm.ListMatch match -> {
                children.add(match.scrutinee());
                children.add(match.nilBranch());
                children.add(match.consBranch());
            }
            case PirTerm.DataMatch match -> {
                children.add(match.scrutinee());
                match.branches().forEach(branch -> children.add(branch.body()));
            }
            case PirTerm.Trace trace -> {
                children.add(trace.message());
                children.add(trace.body());
            }
            case PirTerm.Var ignored -> {
            }
            case PirTerm.Const ignored -> {
            }
            case PirTerm.Builtin ignored -> {
            }
            case PirTerm.Error ignored -> {
            }
        }
        return children;
    }
}
