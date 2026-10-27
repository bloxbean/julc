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

    /** Checks a result against a DIVERGE expectation that pins both observations. */
    private static String check(String expectation, Observation jvmPin, Observation uplcPin,
                                Observation jvm, Observation uplc) {
        return RegressionStore.Expectation.parse(expectation).pinned(jvmPin, uplcPin)
                .check(new DiffRunner.Result(jvm, uplc, Verdict.compare(jvm, uplc)));
    }

    private static Observation pinnedFailure(String... traces) {
        return new Observation.Failure(null, List.of(traces));
    }

    @Test
    void expectationsCheckKindAndCode() {
        assertNull(check("AGREE", value(1), value(1)));
        assertNotNull(check("AGREE", value(1), value(2)));
        assertNull(check("REJECT JULC0056", value(1), new Observation.Rejected("JULC0056", "m")));
        assertNotNull(check("REJECT JULC0056", value(1), new Observation.Rejected("JULC0001", "m")));
        assertNull(check("REJECT none", value(1), new Observation.Rejected(null, "m")));
        assertNotNull(check("REJECT none", value(1), new Observation.Rejected("JULC0001", "m")));
        assertThrows(HarnessException.class, () -> RegressionStore.Expectation.parse("DIVERGE T7"));
        assertThrows(HarnessException.class, () -> RegressionStore.Expectation.parse("REJECT"));
        assertThrows(HarnessException.class, () -> RegressionStore.Expectation.parse("MAYBE"));
    }

    /** A divergence passes only as its pinned pair of observations, not as any pair of the same kinds (D6). */
    @Test
    void aDivergenceIsPinnedToItsObservations() {
        // T12: the same value, and a trace only on UPLC.
        String t12 = "DIVERGE T12 Value/Value";
        Observation jvm12 = value(1), uplc12 = value(1, "checked");
        assertNull(check(t12, jvm12, uplc12, value(1), value(1, "checked")));
        assertNotNull(check(t12, jvm12, uplc12, value(1), value(999)), "a wrong value with the same traces");
        assertNotNull(check(t12, jvm12, uplc12, value(1), value(999, "checked")), "a wrong value");
        assertNotNull(check(t12, jvm12, uplc12, value(1), value(1, "checked", "checked")), "an extra trace");
        assertNotNull(check(t12, jvm12, uplc12, value(1, "x"), value(1, "checked")), "a JVM trace");
        // Reordered traces on a case that already diverges.
        String traced = "DIVERGE T12 Value/Value";
        assertNull(check(traced, value(1), value(1, "a", "b"), value(1), value(1, "a", "b")));
        assertNotNull(check(traced, value(1), value(1, "a", "b"), value(1), value(1, "b", "a")));
        // T11: the JVM fails, UPLC returns -1 and nothing else.
        String t11 = "DIVERGE T11 Failure/Value";
        assertNull(check(t11, pinnedFailure(), value(-1), failure(), value(-1)));
        assertNotNull(check(t11, pinnedFailure(), value(-1), failure(), value(5)));
        assertNotNull(check(t11, pinnedFailure(), value(-1), failure("t"), value(-1)), "a trace before the failure");
        // T21: UPLC exhausts its budget, for no other reason.
        String t21 = "DIVERGE T21 Value/Inconclusive";
        var exhausted = new Observation.Inconclusive("budget exhausted");
        assertNull(check(t21, value(3), exhausted, value(3), exhausted));
        assertNotNull(check(t21, value(3), exhausted, value(3), new Observation.Inconclusive("stack overflow")));
        assertNotNull(check(t21, value(3), exhausted, value(4), exhausted), "another JVM value");
        // Another kind is still another kind.
        assertNotNull(check("DIVERGE T7 Value/Failure", value(1), pinnedFailure(), value(1), value(2)));
        assertNotNull(check("DIVERGE T7 Value/Failure", value(1), pinnedFailure(), value(1), exhausted));
    }

    @Test
    void aDivergenceNeedsValidPins() {
        var t7 = RegressionStore.Expectation.parse("DIVERGE T7 Value/Failure");
        assertNotNull(t7.check(new DiffRunner.Result(value(1), failure(), Verdict.compare(value(1), failure()))),
                "an unpinned divergence never passes");
        assertThrows(HarnessException.class, () -> t7.pinned(value(1), null));
        assertThrows(HarnessException.class, () -> t7.pinned(value(1), value(2)), "the pin's kind must match");
        assertThrows(HarnessException.class, () -> t7.pinned(value(1), failure()), "failure details are not pinned");
        assertThrows(HarnessException.class, () -> RegressionStore.Expectation.parse("DIVERGE T7 Value/Crash")
                .pinned(value(1), new Observation.Crash("npe")));
        assertThrows(HarnessException.class, () -> RegressionStore.Expectation.AGREE.pinned(value(1), value(1)));
    }
}
