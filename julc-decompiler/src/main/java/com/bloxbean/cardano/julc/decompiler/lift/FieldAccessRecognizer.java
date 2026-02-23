package com.bloxbean.cardano.julc.decompiler.lift;

import com.bloxbean.cardano.julc.core.DefaultFun;
import com.bloxbean.cardano.julc.core.Term;

/**
 * Recognizes field access chains (HeadList/TailList) and determines the field index.
 * <p>
 * Field access at index N on a Data list is:
 * {@code HeadList(TailList^N(fields))}
 * <p>
 * For a record with fields [f0, f1, f2]:
 * - f0: HeadList(fields)
 * - f1: HeadList(TailList(fields))
 * - f2: HeadList(TailList(TailList(fields)))
 */
public final class FieldAccessRecognizer {

    private FieldAccessRecognizer() {}

    /**
     * Try to match a HeadList/TailList chain and return the field index.
     * Returns null if the term doesn't match.
     */
    public static FieldAccessResult match(Term term) {
        // Pattern: Force(Apply(Force(Builtin(HeadList)), TailList^N(source)))
        // Using ForceCollapser: HeadList with 1 arg, where arg is TailList^N(source)
        var fb = ForceCollapser.matchForcedBuiltin(term);
        if (fb != null && fb.fun() == DefaultFun.HeadList && fb.args().size() == 1) {
            Term inner = fb.args().getFirst();
            int tailCount = 0;
            Term source = inner;

            while (true) {
                var tailFb = ForceCollapser.matchForcedBuiltin(source);
                if (tailFb != null && tailFb.fun() == DefaultFun.TailList && tailFb.args().size() == 1) {
                    tailCount++;
                    source = tailFb.args().getFirst();
                } else {
                    break;
                }
            }

            return new FieldAccessResult(source, tailCount);
        }

        return null;
    }

    /**
     * Try to match just a TailList chain (without HeadList).
     * This is useful for recognizing the "rest of fields" pattern.
     */
    public static TailChainResult matchTailChain(Term term) {
        int tailCount = 0;
        Term source = term;

        while (true) {
            var tailFb = ForceCollapser.matchForcedBuiltin(source);
            if (tailFb != null && tailFb.fun() == DefaultFun.TailList && tailFb.args().size() == 1) {
                tailCount++;
                source = tailFb.args().getFirst();
            } else {
                break;
            }
        }

        if (tailCount == 0) return null;
        return new TailChainResult(source, tailCount);
    }

    /**
     * Result of field access recognition.
     *
     * @param source     the base term (the list/fields var being indexed)
     * @param fieldIndex the 0-based field index
     */
    public record FieldAccessResult(Term source, int fieldIndex) {}

    public record TailChainResult(Term source, int tailCount) {}
}
