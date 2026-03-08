package com.bloxbean.cardano.julc.stdlib.lib;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.Interval;
import com.bloxbean.cardano.julc.ledger.IntervalBound;
import com.bloxbean.cardano.julc.ledger.IntervalBoundType;
import com.bloxbean.cardano.julc.testkit.JulcEval;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

class IntervalLibTest {

    static JulcEval eval;

    @BeforeAll
    static void setUp() {
        eval = JulcEval.forClass(IntervalLib.class);
    }

    // --- Helpers ---

    static Interval closedInterval(long low, long high) {
        return new Interval(
                new IntervalBound(new IntervalBoundType.Finite(BigInteger.valueOf(low)), true),
                new IntervalBound(new IntervalBoundType.Finite(BigInteger.valueOf(high)), true));
    }

    static Interval alwaysInterval() {
        return new Interval(
                new IntervalBound(new IntervalBoundType.NegInf(), true),
                new IntervalBound(new IntervalBoundType.PosInf(), true));
    }

    static Interval neverInterval() {
        return new Interval(
                new IntervalBound(new IntervalBoundType.PosInf(), false),
                new IntervalBound(new IntervalBoundType.NegInf(), false));
    }

    // =========================================================================
    // contains
    // =========================================================================

    @Nested
    class Contains {

        @Test
        void pointInRange() {
            assertTrue(eval.call("contains", closedInterval(10, 20), BigInteger.valueOf(15)).asBoolean());
        }

        @Test
        void pointBelowRange() {
            assertFalse(eval.call("contains", closedInterval(10, 20), BigInteger.valueOf(5)).asBoolean());
        }

        @Test
        void pointAboveRange() {
            assertFalse(eval.call("contains", closedInterval(10, 20), BigInteger.valueOf(25)).asBoolean());
        }

        @Test
        void pointAtInclusiveLowerBound() {
            assertTrue(eval.call("contains", closedInterval(10, 20), BigInteger.valueOf(10)).asBoolean());
        }

        @Test
        void pointAtInclusiveUpperBound() {
            assertTrue(eval.call("contains", closedInterval(10, 20), BigInteger.valueOf(20)).asBoolean());
        }

        @Test
        void pointAtExclusiveLowerBound() {
            Interval interval = new Interval(
                    new IntervalBound(new IntervalBoundType.Finite(BigInteger.valueOf(10)), false),
                    new IntervalBound(new IntervalBoundType.Finite(BigInteger.valueOf(20)), true));
            assertFalse(eval.call("contains", interval, BigInteger.valueOf(10)).asBoolean());
        }

        @Test
        void pointAtExclusiveUpperBound() {
            Interval interval = new Interval(
                    new IntervalBound(new IntervalBoundType.Finite(BigInteger.valueOf(10)), true),
                    new IntervalBound(new IntervalBoundType.Finite(BigInteger.valueOf(20)), false));
            assertFalse(eval.call("contains", interval, BigInteger.valueOf(20)).asBoolean());
        }

        @Test
        void alwaysContainsAnyPoint() {
            assertTrue(eval.call("contains", alwaysInterval(), BigInteger.valueOf(999)).asBoolean());
        }

        @Test
        void neverContainsNothing() {
            assertFalse(eval.call("contains", neverInterval(), BigInteger.valueOf(0)).asBoolean());
        }
    }

    // =========================================================================
    // Factories: always, after, before, between, never
    // =========================================================================

    @Nested
    class Factories {

        @Test
        void alwaysReturnsValidInterval() {
            PlutusData result = eval.call("always").asData();
            assertNotNull(result);
            assertInstanceOf(PlutusData.ConstrData.class, result);
        }

        @Test
        void afterReturnsValidInterval() {
            PlutusData result = eval.call("after", BigInteger.valueOf(100)).asData();
            assertNotNull(result);
            assertInstanceOf(PlutusData.ConstrData.class, result);
        }

        @Test
        void beforeReturnsValidInterval() {
            PlutusData result = eval.call("before", BigInteger.valueOf(100)).asData();
            assertNotNull(result);
            assertInstanceOf(PlutusData.ConstrData.class, result);
        }

        @Test
        void betweenReturnsValidInterval() {
            PlutusData result = eval.call("between", BigInteger.valueOf(10), BigInteger.valueOf(20)).asData();
            assertNotNull(result);
            assertInstanceOf(PlutusData.ConstrData.class, result);
        }

        @Test
        void neverReturnsValidInterval() {
            PlutusData result = eval.call("never").asData();
            assertNotNull(result);
            assertInstanceOf(PlutusData.ConstrData.class, result);
        }
    }

    // =========================================================================
    // isEmpty
    // =========================================================================

    @Nested
    class IsEmpty {

        @Test
        void neverIsEmpty() {
            assertTrue(eval.call("isEmpty", neverInterval()).asBoolean());
        }

        @Test
        void alwaysIsNotEmpty() {
            assertFalse(eval.call("isEmpty", alwaysInterval()).asBoolean());
        }

        @Test
        void finiteIntervalIsNotEmpty() {
            assertFalse(eval.call("isEmpty", closedInterval(10, 20)).asBoolean());
        }
    }

    // =========================================================================
    // finiteUpperBound / finiteLowerBound
    // =========================================================================

    @Nested
    class FiniteBounds {

        @Test
        void finiteUpperBoundReturnsValue() {
            assertEquals(BigInteger.valueOf(20),
                    eval.call("finiteUpperBound", closedInterval(10, 20)).asInteger());
        }

        @Test
        void infiniteUpperBoundReturnsSentinel() {
            assertEquals(BigInteger.valueOf(-1),
                    eval.call("finiteUpperBound", alwaysInterval()).asInteger());
        }

        @Test
        void finiteLowerBoundReturnsValue() {
            assertEquals(BigInteger.valueOf(10),
                    eval.call("finiteLowerBound", closedInterval(10, 20)).asInteger());
        }

        @Test
        void infiniteLowerBoundReturnsSentinel() {
            assertEquals(BigInteger.valueOf(-1),
                    eval.call("finiteLowerBound", alwaysInterval()).asInteger());
        }
    }
}
