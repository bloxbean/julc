package com.bloxbean.cardano.julc.decompiler.lift;

import com.bloxbean.cardano.julc.core.Term;

import java.util.ArrayList;
import java.util.List;

/**
 * Recognizes V3 SOP (Sums of Products) patterns.
 * <p>
 * V3 scripts use:
 * - {@link Term.Constr}: constructor application ({@code Constr(tag, fields...)})
 * - {@link Term.Case}: case expression ({@code Case(scrutinee, branches...)})
 * <p>
 * These map directly to constructors and switch statements.
 */
public final class SopRecognizer {

    private SopRecognizer() {}

    /**
     * Check if a term is a Constr (SOP constructor).
     */
    public static ConstrResult matchConstr(Term term) {
        if (term instanceof Term.Constr c) {
            return new ConstrResult((int) c.tag(), c.fields());
        }
        return null;
    }

    /**
     * Check if a term is a Case (SOP pattern match).
     * Case(scrutinee, branch0, branch1, ...) where each branch is a lambda
     * receiving the constructor fields.
     */
    public static CaseResult matchCase(Term term) {
        if (term instanceof Term.Case cs) {
            List<CaseBranch> branches = new ArrayList<>();
            for (int i = 0; i < cs.branches().size(); i++) {
                Term branch = cs.branches().get(i);
                // Each branch is typically a lambda that receives the constructor fields
                List<String> params = new ArrayList<>();
                Term body = branch;
                while (body instanceof Term.Lam lam) {
                    params.add(lam.paramName());
                    body = lam.body();
                }
                branches.add(new CaseBranch(i, params, body));
            }
            return new CaseResult(cs.scrutinee(), branches);
        }
        return null;
    }

    public record ConstrResult(int tag, List<Term> fields) {}
    public record CaseResult(Term scrutinee, List<CaseBranch> branches) {
        public CaseResult { branches = List.copyOf(branches); }
    }
    public record CaseBranch(int tag, List<String> fieldNames, Term body) {
        public CaseBranch { fieldNames = List.copyOf(fieldNames); }
    }
}
