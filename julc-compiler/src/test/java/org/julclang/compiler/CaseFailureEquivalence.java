package org.julclang.compiler;

import org.julclang.vm.EvalResult;

import java.math.BigInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Failure-text equivalence for before/after lowering comparisons under ADR-041 O5.
 * <p>
 * Compiler-generated sealed dispatch fails on a malformed constructor tag via PV11 integer
 * Case selection instead of the legacy equality chain's terminal {@code error} term. Both
 * forms fail at the same semantic point with no branch effects; only the off-chain failure
 * text differs. This helper accepts exactly that substitution, in either direction and only
 * within one VM family's wording (Java and Truffle share one, Scalus has its own), and
 * nothing else.
 */
final class CaseFailureEquivalence {

    private record Wording(String legacyError, Pattern caseOutOfRange) {
        boolean isSubstitution(String a, String b) {
            return (a.equals(legacyError) && caseOutOfRange.matcher(b).matches())
                    || (b.equals(legacyError) && caseOutOfRange.matcher(a).matches());
        }
    }

    /** Java and Truffle: tag in group 1, branch count in group 2. */
    private static final Wording JAVA = new Wording("Error term encountered",
            Pattern.compile("Case: tag (-?\\d+) out of range for (\\d+) branches"));
    /** Scalus: tag in group 1, branch count in group 2. */
    private static final Wording SCALUS = new Wording("Error evaluated",
            Pattern.compile("Case index (-?\\d+) out of bounds for (\\d+) branches"));

    private CaseFailureEquivalence() {
    }

    /** True when the two texts are the documented O5 dispatch substitution, in either direction. */
    static boolean isDispatchSubstitution(String a, String b) {
        return JAVA.isSubstitution(a, b) || SCALUS.isSubstitution(a, b);
    }

    /** Failure texts must be identical or exactly the O5 dispatch substitution. */
    static void assertFailureTextEquivalent(EvalResult.Failure before, EvalResult.Failure after, String label) {
        if (before.error().equals(after.error())) return;
        assertTrue(isDispatchSubstitution(before.error(), after.error()),
                label + ": failure text changed beyond the ADR-041 dispatch substitution: <"
                        + before.error() + "> vs <" + after.error() + ">");
    }

    /**
     * Same as {@link #assertFailureTextEquivalent}, and when the substitution applies the Case
     * text must name exactly the supplied tag and constructor count.
     */
    static void assertFailureTextEquivalent(EvalResult.Failure before, EvalResult.Failure after,
            BigInteger tag, int constructors, String label) {
        assertFailureTextEquivalent(before, after, label);
        if (before.error().equals(after.error())) return;
        var caseText = before.error().equals(JAVA.legacyError) || before.error().equals(SCALUS.legacyError)
                ? after.error() : before.error();
        assertOutOfRangeText(caseText, tag, constructors, label);
    }

    /** The text must be a Case out-of-range failure (any VM wording) naming exactly this tag and count. */
    static void assertOutOfRangeText(String error, BigInteger tag, int constructors, String label) {
        Matcher m = JAVA.caseOutOfRange.matcher(error);
        if (!m.matches()) m = SCALUS.caseOutOfRange.matcher(error);
        assertTrue(m.matches(), label + ": not a Case out-of-range failure: <" + error + ">");
        assertEquals(tag.toString(), m.group(1), label + ": Case failure names the wrong tag");
        assertEquals(Integer.toString(constructors), m.group(2), label + ": Case failure names the wrong branch count");
    }

    /** The legacy chain's failure text for the given VM family. */
    static String legacyErrorText(String provider) {
        return provider.equals("Scalus") ? SCALUS.legacyError : JAVA.legacyError;
    }
}
