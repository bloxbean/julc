package org.julclang.compiler.frontend.javac.lower;

import org.julclang.compiler.pir.PirTerm;

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

    static void collect(PirTerm term, Set<String> candidates, Map<String, PirTerm.Var> reads) {
        switch (term) {
            case PirTerm.Var read -> {
                if (candidates.contains(read.name())) reads.putIfAbsent(read.name(), read);
            }
            case PirTerm.Let let -> {
                collect(let.value(), candidates, reads);
                collect(let.body(), candidates, reads);
            }
            case PirTerm.LetRec rec -> {
                rec.bindings().forEach(binding -> collect(binding.value(), candidates, reads));
                collect(rec.body(), candidates, reads);
            }
            case PirTerm.Lam lam -> collect(lam.body(), candidates, reads);
            case PirTerm.App app -> {
                collect(app.function(), candidates, reads);
                collect(app.argument(), candidates, reads);
            }
            case PirTerm.IfThenElse branch -> {
                collect(branch.cond(), candidates, reads);
                collect(branch.thenBranch(), candidates, reads);
                collect(branch.elseBranch(), candidates, reads);
            }
            case PirTerm.DataConstr constr -> constr.fields().forEach(field -> collect(field, candidates, reads));
            case PirTerm.IntegerCase select -> {
                collect(select.scrutinee(), candidates, reads);
                select.branches().forEach(branch -> collect(branch, candidates, reads));
            }
            case PirTerm.PairMatch match -> {
                collect(match.scrutinee(), candidates, reads);
                collect(match.body(), candidates, reads);
            }
            case PirTerm.ListMatch match -> {
                collect(match.scrutinee(), candidates, reads);
                collect(match.nilBranch(), candidates, reads);
                collect(match.consBranch(), candidates, reads);
            }
            case PirTerm.DataMatch match -> {
                collect(match.scrutinee(), candidates, reads);
                match.branches().forEach(branch -> collect(branch.body(), candidates, reads));
            }
            case PirTerm.Trace trace -> {
                collect(trace.message(), candidates, reads);
                collect(trace.body(), candidates, reads);
            }
            case PirTerm.Const ignored -> {
            }
            case PirTerm.Builtin ignored -> {
            }
            case PirTerm.Error ignored -> {
            }
        }
    }
}
