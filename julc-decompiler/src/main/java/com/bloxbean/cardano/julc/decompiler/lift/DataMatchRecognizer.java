package com.bloxbean.cardano.julc.decompiler.lift;

import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.DefaultFun;
import com.bloxbean.cardano.julc.core.Term;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * Recognizes Data-based pattern matching (the pre-V3 way).
 * <p>
 * The compiler generates this pattern for switch statements on data-encoded types:
 * <pre>
 * Let(d, scrutinee,
 *   Let(p, UnConstrData(d),
 *     Let(tag, FstPair(p),
 *       Let(fields, SndPair(p),
 *         IfThenElse(EqualsInteger(tag, 0), branch0,
 *           IfThenElse(EqualsInteger(tag, 1), branch1,
 *             ...Error))))))
 * </pre>
 */
public final class DataMatchRecognizer {

    private DataMatchRecognizer() {}

    /**
     * Try to match a data pattern-match chain.
     * Returns null if the term doesn't match the pattern.
     */
    public static DataMatchResult match(Term term) {
        // Step 1: Look for Let(d, scrutinee, Let(p, UnConstrData(d), ...))
        var outerLet = LetRecognizer.match(term);
        if (outerLet == null) return null;

        String dataVarName = outerLet.name();
        Term scrutinee = outerLet.value();

        // Look for Let(p, UnConstrData(d), ...)
        var pairLet = LetRecognizer.match(outerLet.body());
        if (pairLet == null) return null;

        // Check that the value is UnConstrData applied to the data var
        if (!isUnConstrData(pairLet.value())) return null;

        String pairVarName = pairLet.name();

        // Look for Let(tag, FstPair(p), ...)
        var tagLet = LetRecognizer.match(pairLet.body());
        if (tagLet == null) return null;

        if (!isFstPair(tagLet.value())) return null;

        String tagVarName = tagLet.name();

        // Look for Let(fields, SndPair(p), ...)
        var fieldsLet = LetRecognizer.match(tagLet.body());
        if (fieldsLet == null) return null;

        if (!isSndPair(fieldsLet.value())) return null;

        String fieldsVarName = fieldsLet.name();

        // Now parse the dispatch chain: IfThenElse(EqualsInteger(tag, N), branchN, ...)
        List<DataMatchBranch> branches = new ArrayList<>();
        Term remaining = fieldsLet.body();
        parseDispatchChain(remaining, tagVarName, fieldsVarName, branches);

        if (branches.isEmpty()) return null;

        return new DataMatchResult(scrutinee, dataVarName, fieldsVarName, branches);
    }

    private static void parseDispatchChain(Term term, String tagVarName, String fieldsVarName,
                                           List<DataMatchBranch> branches) {
        // Try to match IfThenElse(EqualsInteger(tag, N), branchBody, rest)
        var ifMatch = IfThenElseRecognizer.match(term);
        if (ifMatch == null) {
            // If it's a single branch (no dispatch), the remaining term IS the branch body
            // This happens when there's only one constructor
            if (!branches.isEmpty()) return;
            // Check if there's a direct EqualsInteger check or the body uses fields
            branches.add(new DataMatchBranch(0, term));
            return;
        }

        // Check if condition is EqualsInteger(tag, N)
        Integer tag = matchTagComparison(ifMatch.condition(), tagVarName);
        if (tag != null) {
            branches.add(new DataMatchBranch(tag, ifMatch.thenBranch()));
            // Continue with else branch
            parseDispatchChain(ifMatch.elseBranch(), tagVarName, fieldsVarName, branches);
        } else {
            // Not a tag dispatch — treat as a regular if-then-else
            // Add the whole thing as a single branch fallback
            if (branches.isEmpty()) {
                branches.add(new DataMatchBranch(0, term));
            }
        }
    }

    /**
     * Try to match EqualsInteger(tagVar, N) and return N.
     */
    private static Integer matchTagComparison(Term cond, String tagVarName) {
        var fb = ForceCollapser.matchForcedBuiltin(cond);
        if (fb != null && fb.fun() == DefaultFun.EqualsInteger && fb.args().size() == 2) {
            // One arg should be the tag var, the other should be a constant
            Term arg0 = fb.args().get(0);
            Term arg1 = fb.args().get(1);

            if (isVarNamed(arg0, tagVarName) && arg1 instanceof Term.Const c
                    && c.value() instanceof Constant.IntegerConst ic) {
                return ic.value().intValueExact();
            }
            if (isVarNamed(arg1, tagVarName) && arg0 instanceof Term.Const c
                    && c.value() instanceof Constant.IntegerConst ic) {
                return ic.value().intValueExact();
            }
        }

        // Also try: Apply(Apply(Builtin(EqualsInteger), tagVar), N)
        if (cond instanceof Term.Apply a1 && a1.function() instanceof Term.Apply a2
                && a2.function() instanceof Term.Builtin b && b.fun() == DefaultFun.EqualsInteger) {
            Term left = a2.argument();
            Term right = a1.argument();
            if (right instanceof Term.Const c && c.value() instanceof Constant.IntegerConst ic) {
                return ic.value().intValueExact();
            }
            if (left instanceof Term.Const c && c.value() instanceof Constant.IntegerConst ic) {
                return ic.value().intValueExact();
            }
        }

        return null;
    }

    private static boolean isVarNamed(Term term, String name) {
        // In De Bruijn world, we can't check by name easily.
        // But we know the tag var is at a specific scope depth.
        // For now, accept any Var.
        return term instanceof Term.Var;
    }

    private static boolean isUnConstrData(Term term) {
        var fb = ForceCollapser.matchForcedBuiltinPartial(term);
        if (fb != null && fb.fun() == DefaultFun.UnConstrData && fb.args().size() == 1) {
            return true;
        }
        // Direct pattern: Apply(Builtin(UnConstrData), arg)
        return term instanceof Term.Apply a
                && a.function() instanceof Term.Builtin b
                && b.fun() == DefaultFun.UnConstrData;
    }

    private static boolean isFstPair(Term term) {
        var fb = ForceCollapser.matchForcedBuiltinPartial(term);
        return fb != null && fb.fun() == DefaultFun.FstPair && fb.args().size() == 1;
    }

    private static boolean isSndPair(Term term) {
        var fb = ForceCollapser.matchForcedBuiltinPartial(term);
        return fb != null && fb.fun() == DefaultFun.SndPair && fb.args().size() == 1;
    }

    public record DataMatchResult(Term scrutinee, String dataVarName, String fieldsVarName,
                                  List<DataMatchBranch> branches) {
        public DataMatchResult { branches = List.copyOf(branches); }
    }

    public record DataMatchBranch(int tag, Term body) {}
}
