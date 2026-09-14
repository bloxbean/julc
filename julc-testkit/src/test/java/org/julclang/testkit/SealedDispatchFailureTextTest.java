package org.julclang.testkit;

import org.julclang.core.PlutusData;
import org.julclang.vm.TermExtractor;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-041: {@link JulcEval} compiles methods through {@code compileMethod}, which decodes
 * sealed parameters without the strict boundary. A constructor tag outside {@code 0..n-1}
 * therefore reaches the dispatch and, under the default safe profile, fails at the PV11
 * integer Case selection. This pins the failure text testkit users observe.
 */
class SealedDispatchFailureTextTest {

    static final JulcEval eval = JulcEval.forSource("""
            import java.math.BigInteger;

            class Dispatch {
                sealed interface Action permits Pay, Cancel, Hold {}
                record Pay(BigInteger amount) implements Action {}
                record Cancel() implements Action {}
                record Hold() implements Action {}

                static BigInteger run(Action action) {
                    return switch (action) {
                        case Pay p -> p.amount();
                        case Cancel c -> BigInteger.ZERO;
                        case Hold h -> BigInteger.valueOf(-1);
                    };
                }
            }
            """);

    @Test
    void validTagsSelectTheirBranch() {
        assertEquals(BigInteger.valueOf(7), eval.call("run", PlutusData.constr(0, PlutusData.integer(7))).asInteger());
        assertEquals(BigInteger.ZERO, eval.call("run", PlutusData.constr(1)).asInteger());
        assertEquals(BigInteger.valueOf(-1), eval.call("run", PlutusData.constr(2)).asInteger());
    }

    @Test
    void outOfRangeTagFailsAtCaseSelectionNamingTagAndBranchCount() {
        for (var tag : new long[]{3, 99}) {
            var ex = assertThrows(TermExtractor.ExtractionException.class,
                    () -> eval.call("run", PlutusData.constr((int) tag)));
            assertTrue(ex.getMessage().contains("Case: tag " + tag + " out of range for 3 branches"), ex.getMessage());
        }
        var huge = new PlutusData.ConstrData(BigInteger.ONE.shiftLeft(40), List.of());
        var ex = assertThrows(TermExtractor.ExtractionException.class, () -> eval.call("run", huge));
        assertTrue(ex.getMessage().contains("Case: tag 1099511627776 out of range for 3 branches"), ex.getMessage());
    }
}
