package org.julclang.difftest;

import org.julclang.core.PlutusData;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The one-line text of an observation in saved cases (ADR-063 D6, D8). */
class ObservationTextTest {

    private static void roundTrip(Observation observation) {
        String text = ObservationText.format(observation);
        assertFalse(text.contains("\n") || text.contains("\r"), text);
        assertEquals(observation, ObservationText.parse(text), text);
    }

    @Test
    void everyKindRoundTrips() {
        var data = new PlutusData.ListData(List.of(new PlutusData.IntData(BigInteger.valueOf(-7)),
                new PlutusData.BytesData(new byte[]{1, 2})));
        roundTrip(new Observation.Value(data, List.of()));
        roundTrip(new Observation.Value(data, List.of("a", "b")));
        roundTrip(new Observation.Failure(null, List.of("before")));
        roundTrip(new Observation.Failure("java.lang.ArithmeticException: / by zero", List.of()));
        roundTrip(new Observation.Inconclusive("budget exhausted"));
        roundTrip(new Observation.Rejected("JULC0056", "a message"));
        roundTrip(new Observation.Rejected(null, "Unsupported statement: ContinueStmt"));
        roundTrip(new Observation.Crash("compiler: java.lang.NullPointerException"));
    }

    @Test
    void tracesKeepEveryCharacter() {
        var traces = List.of("a\n[TRACE] b", "x\r\ny", "", "say \"hi\" \\ there", "tab\tbell\u0007", "é ✓");
        roundTrip(new Observation.Value(new PlutusData.IntData(BigInteger.ONE), traces));
        assertEquals("Value 01 [\"a\\n[TRACE] b\"]",
                ObservationText.format(new Observation.Value(new PlutusData.IntData(BigInteger.ONE), List.of("a\n[TRACE] b"))));
    }

    @Test
    void malformedTextIsRejected() {
        assertThrows(HarnessException.class, () -> ObservationText.parse("Maybe 01 []"));
        assertThrows(HarnessException.class, () -> ObservationText.parse("Value 01"));
        assertThrows(HarnessException.class, () -> ObservationText.parse("Value 01 [\"a\""));
        assertThrows(HarnessException.class, () -> ObservationText.parse("Inconclusive budget"));
        assertThrows(HarnessException.class, () -> ObservationText.parse("Value 01 [] extra"));
        assertThrows(HarnessException.class, () -> ObservationText.parse("Value 01 [\"\\q\"]"));
    }
}
