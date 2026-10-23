package org.julclang.difftest;

import org.julclang.core.PlutusData;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The comparison rules of ADR-063 D3, H2 and H5, and the saved-case expectations of D6. */
class VerdictTest {

    private static Observation value(long v, String... traces) {
        return new Observation.Value(new PlutusData.IntData(BigInteger.valueOf(v)), List.of(traces));
    }

    private static Observation failure(String... traces) {
        return new Observation.Failure("boom", List.of(traces));
    }

    private static Verdict.Kind kind(Observation jvm, Observation uplc) {
        return Verdict.compare(jvm, uplc).kind();
    }

    @Test
    void equalValuesAndTracesAgree() {
        assertEquals(Verdict.Kind.AGREE, kind(value(1, "a", "b"), value(1, "a", "b")));
        assertEquals(Verdict.Kind.AGREE, kind(failure("a"), failure("a")));
    }

    @Test
    void anyDifferenceIsAMismatch() {
        assertEquals(Verdict.Kind.MISMATCH, kind(value(1), value(2)));
        assertEquals(Verdict.Kind.MISMATCH, kind(value(1, "a"), value(1)));
        assertEquals(Verdict.Kind.MISMATCH, kind(value(1, "a", "b"), value(1, "b", "a")));
        assertEquals(Verdict.Kind.MISMATCH, kind(failure("a"), failure()));
        assertEquals(Verdict.Kind.MISMATCH, kind(value(1), failure()));
        assertEquals(Verdict.Kind.MISMATCH, kind(failure(), value(1)));
    }

    @Test
    void rejectionCrashAndInconclusiveAreNeverAgreement() {
        assertEquals(Verdict.Kind.REJECTED, kind(value(1), new Observation.Rejected("JULC0001", "no")));
        assertEquals(Verdict.Kind.CRASH, kind(value(1), new Observation.Crash("npe")));
        assertEquals(Verdict.Kind.CRASH, kind(new Observation.Inconclusive("timeout"), new Observation.Crash("npe")));
        assertEquals(Verdict.Kind.INCONCLUSIVE, kind(new Observation.Inconclusive("timeout"), value(1)));
        assertEquals(Verdict.Kind.INCONCLUSIVE, kind(value(1), new Observation.Inconclusive("budget exhausted")));
    }

    private static String check(String expectation, Observation jvm, Observation uplc) {
        return RegressionStore.Expectation.parse(expectation)
                .check(new DiffRunner.Result(jvm, uplc, Verdict.compare(jvm, uplc)));
    }

    @Test
    void expectationsCheckKindCodeAndSides() {
        assertNull(check("AGREE", value(1), value(1)));
        assertNotNull(check("AGREE", value(1), value(2)));
        assertNull(check("REJECT JULC0056", value(1), new Observation.Rejected("JULC0056", "m")));
        assertNotNull(check("REJECT JULC0056", value(1), new Observation.Rejected("JULC0001", "m")));
        assertNull(check("REJECT none", value(1), new Observation.Rejected(null, "m")));
        assertNotNull(check("REJECT none", value(1), new Observation.Rejected("JULC0001", "m")));
        assertNull(check("DIVERGE T7 Value/Failure", value(1), failure()));
        // A mismatch with another cause does not satisfy the expectation.
        assertNotNull(check("DIVERGE T7 Value/Failure", value(1), value(2)));
        assertNotNull(check("DIVERGE T7 Value/Failure", value(1), value(1)));
        assertThrows(HarnessException.class, () -> RegressionStore.Expectation.parse("DIVERGE T7"));
        assertThrows(HarnessException.class, () -> RegressionStore.Expectation.parse("REJECT"));
        assertThrows(HarnessException.class, () -> RegressionStore.Expectation.parse("MAYBE"));
    }
}
