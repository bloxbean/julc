package org.julclang.compiler;

import org.julclang.vm.EvalResult;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Failure-text equivalence for before/after comparisons under ADR-043 O9.
 * <p>
 * A promoted {@code JulcList.get} site fails on an out-of-range index at the {@code IndexArray}
 * builtin instead of inside the recursive traversal ({@code HeadList} on the exhausted list
 * when the index equals the length, {@code TailList} when it is negative or larger). Both fail
 * at the same semantic point, after the index is evaluated and before anything else runs;
 * only the off-chain text (and the budget, which is smaller) differs. This helper accepts
 * exactly that substitution, in the promoted direction only and within one VM family's
 * wording (Java and Truffle share one, Scalus has its own), and nothing else.
 */
final class IndexFailureEquivalence {

    private record Wording(Pattern legacy, Pattern indexArray, Pattern beyondMachineRange) {
        boolean isSubstitution(String before, String after) {
            return legacy.matcher(before).matches()
                    && (indexArray.matcher(after).matches() || beyondMachineRange.matcher(after).matches());
        }
    }

    /** Java and Truffle: index in group 1, array size in group 2. */
    private static final Wording JAVA = new Wording(
            Pattern.compile("(HeadList|TailList): empty list"),
            Pattern.compile("IndexArray: index (-?\\d+) out of bounds for array of size (\\d+)"),
            Pattern.compile("IndexArray: index out of range: (-?\\d+)"));
    /** Scalus: index in group 1, array size in group 2; the text embeds the failing term. */
    private static final Wording SCALUS = new Wording(
            Pattern.compile("Builtin error: (HeadList|TailList) .*(head|tail) of empty list", Pattern.DOTALL),
            Pattern.compile("Builtin error: IndexArray .*indexArray: index (-?\\d+) out of bounds for array of length (\\d+)", Pattern.DOTALL),
            Pattern.compile("(?!)"));

    private IndexFailureEquivalence() {
    }

    /** True when the two texts are the documented O9 substitution, legacy before and array after. */
    static boolean isPromotionSubstitution(String before, String after) {
        return JAVA.isSubstitution(before, after) || SCALUS.isSubstitution(before, after);
    }

    /** Failure texts must be identical or exactly the O9 promotion substitution. */
    static void assertFailureTextEquivalent(EvalResult.Failure before, EvalResult.Failure after, String label) {
        if (before.error().equals(after.error())) return;
        assertTrue(isPromotionSubstitution(before.error(), after.error()),
                label + ": failure text changed beyond the ADR-043 promotion substitution: <"
                        + before.error() + "> vs <" + after.error() + ">");
    }

    /**
     * The substitution must apply (a promoted site failed), and the array text must name exactly
     * the supplied index and array size. An index beyond the machine range is reported by the
     * Java VMs without the size; the index must still match.
     */
    static void assertPromotedFailure(EvalResult.Failure before, EvalResult.Failure after,
            long index, int size, String label) {
        assertTrue(isPromotionSubstitution(before.error(), after.error()),
                label + ": expected the ADR-043 substitution, got <" + before.error() + "> vs <" + after.error() + ">");
        assertIndexArrayText(after.error(), index, size, label);
    }

    /** The text must be an IndexArray failure (any VM wording) naming exactly this index and size. */
    static void assertIndexArrayText(String error, long index, int size, String label) {
        Matcher m = JAVA.indexArray.matcher(error);
        if (m.matches()) {
            assertEquals(Long.toString(index), m.group(1), label + ": IndexArray failure names the wrong index");
            assertEquals(Integer.toString(size), m.group(2), label + ": IndexArray failure names the wrong size");
            return;
        }
        m = JAVA.beyondMachineRange.matcher(error);
        if (m.matches()) {
            assertEquals(Long.toString(index), m.group(1), label + ": IndexArray failure names the wrong index");
            return;
        }
        m = SCALUS.indexArray.matcher(error);
        assertTrue(m.matches(), label + ": not an IndexArray failure: <" + error + ">");
        assertEquals(Long.toString(index), m.group(1), label + ": IndexArray failure names the wrong index");
        assertEquals(Integer.toString(size), m.group(2), label + ": IndexArray failure names the wrong size");
    }
}
