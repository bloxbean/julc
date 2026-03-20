package com.bloxbean.cardano.julc.stdlib.lib;

import com.bloxbean.cardano.julc.testkit.JulcEval;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Value.zero/lovelace/singleton and Interval.always/never/after/before/between
 * registered in StdlibRegistry. Tests compile inline Java source to UPLC and evaluate.
 */
class ValueIntervalFactoryTest {

    // =========================================================================
    // Value factories
    // =========================================================================

    @Nested
    class ValueZero {

        @Test
        void valueZeroIsEmpty() {
            // Use Builtins directly — method chaining on factory results not yet supported
            var eval = JulcEval.forSource("""
                    import com.bloxbean.cardano.julc.ledger.Value;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    public class T {
                        public static boolean test() {
                            return Builtins.nullList(Builtins.unMapData(Value.zero()));
                        }
                    }
                    """);
            assertTrue(eval.call("test").asBoolean());
        }

        @Test
        void valueZeroSizeIsZero() {
            // Value.zero() produces MapData-wrapped empty pair list → unwrap then check null
            var eval = JulcEval.forSource("""
                    import com.bloxbean.cardano.julc.ledger.Value;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    public class T {
                        public static boolean test() {
                            return Builtins.nullList(Builtins.unMapData(Value.zero()));
                        }
                    }
                    """);
            assertTrue(eval.call("test").asBoolean());
        }
    }

    @Nested
    class ValueLovelace {

        @Test
        void lovelaceIsNotEmpty() {
            // Use Builtins directly — method chaining on factory results not yet supported
            var eval = JulcEval.forSource("""
                    import com.bloxbean.cardano.julc.ledger.Value;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import java.math.BigInteger;
                    public class T {
                        public static boolean test() {
                            return !Builtins.nullList(Builtins.unMapData(Value.lovelace(BigInteger.valueOf(1000000))));
                        }
                    }
                    """);
            assertTrue(eval.call("test").asBoolean());
        }

        @Test
        void lovelaceHasOneEntry() {
            // Value.lovelace(x) produces MapData-wrapped pair list with exactly one outer entry
            var eval = JulcEval.forSource("""
                    import com.bloxbean.cardano.julc.ledger.Value;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.core.PlutusData;
                    import java.math.BigInteger;
                    public class T {
                        public static boolean test() {
                            var v = Value.lovelace(BigInteger.valueOf(5000000));
                            // Unwrap MapData, then check pair list has 1 entry
                            var pairs = Builtins.unMapData(v);
                            return !Builtins.nullList(pairs)
                                && Builtins.nullList(Builtins.tailList(pairs));
                        }
                    }
                    """);
            assertTrue(eval.call("test").asBoolean());
        }
    }

    @Nested
    class ValueSingleton {

        @Test
        void singletonIsNotEmpty() {
            // Use Builtins directly — method chaining on factory results not yet supported
            var eval = JulcEval.forSource("""
                    import com.bloxbean.cardano.julc.ledger.*;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import java.math.BigInteger;
                    public class T {
                        public static boolean test() {
                            byte[] policy = new byte[]{1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,28};
                            byte[] token = new byte[]{65,66,67};
                            return !Builtins.nullList(Builtins.unMapData(Value.singleton(PolicyId.of(policy), TokenName.of(token), BigInteger.valueOf(100))));
                        }
                    }
                    """);
            assertTrue(eval.call("test").asBoolean());
        }
    }

    // =========================================================================
    // Interval factories — use IntervalLib.contains() to check membership
    // =========================================================================

    @Nested
    class IntervalAlways {

        @Test
        void alwaysIsNotEmpty() {
            var eval = JulcEval.forSource("""
                    import com.bloxbean.cardano.julc.ledger.Interval;
                    import com.bloxbean.cardano.julc.stdlib.lib.IntervalLib;
                    public class T {
                        public static boolean test() {
                            return !IntervalLib.isEmpty(Interval.always());
                        }
                    }
                    """);
            assertTrue(eval.call("test").asBoolean());
        }

        @Test
        void alwaysContainsZero() {
            var eval = JulcEval.forSource("""
                    import com.bloxbean.cardano.julc.ledger.Interval;
                    import com.bloxbean.cardano.julc.stdlib.lib.IntervalLib;
                    import java.math.BigInteger;
                    public class T {
                        public static boolean test() {
                            return IntervalLib.contains(Interval.always(), BigInteger.ZERO);
                        }
                    }
                    """);
            assertTrue(eval.call("test").asBoolean());
        }
    }

    @Nested
    class IntervalNever {

        @Test
        void neverIsEmpty() {
            var eval = JulcEval.forSource("""
                    import com.bloxbean.cardano.julc.ledger.Interval;
                    import com.bloxbean.cardano.julc.stdlib.lib.IntervalLib;
                    public class T {
                        public static boolean test() {
                            return IntervalLib.isEmpty(Interval.never());
                        }
                    }
                    """);
            assertTrue(eval.call("test").asBoolean());
        }
    }

    @Nested
    class IntervalAfter {

        @Test
        void afterContainsLaterTime() {
            var eval = JulcEval.forSource("""
                    import com.bloxbean.cardano.julc.ledger.Interval;
                    import com.bloxbean.cardano.julc.stdlib.lib.IntervalLib;
                    import java.math.BigInteger;
                    public class T {
                        public static boolean test() {
                            return IntervalLib.contains(Interval.after(BigInteger.valueOf(100)), BigInteger.valueOf(200));
                        }
                    }
                    """);
            assertTrue(eval.call("test").asBoolean());
        }

        @Test
        void afterContainsBoundary() {
            var eval = JulcEval.forSource("""
                    import com.bloxbean.cardano.julc.ledger.Interval;
                    import com.bloxbean.cardano.julc.stdlib.lib.IntervalLib;
                    import java.math.BigInteger;
                    public class T {
                        public static boolean test() {
                            return IntervalLib.contains(Interval.after(BigInteger.valueOf(100)), BigInteger.valueOf(100));
                        }
                    }
                    """);
            assertTrue(eval.call("test").asBoolean());
        }

        @Test
        void afterExcludesEarlierTime() {
            var eval = JulcEval.forSource("""
                    import com.bloxbean.cardano.julc.ledger.Interval;
                    import com.bloxbean.cardano.julc.stdlib.lib.IntervalLib;
                    import java.math.BigInteger;
                    public class T {
                        public static boolean test() {
                            return !IntervalLib.contains(Interval.after(BigInteger.valueOf(100)), BigInteger.valueOf(50));
                        }
                    }
                    """);
            assertTrue(eval.call("test").asBoolean());
        }
    }

    @Nested
    class IntervalBefore {

        @Test
        void beforeContainsEarlierTime() {
            var eval = JulcEval.forSource("""
                    import com.bloxbean.cardano.julc.ledger.Interval;
                    import com.bloxbean.cardano.julc.stdlib.lib.IntervalLib;
                    import java.math.BigInteger;
                    public class T {
                        public static boolean test() {
                            return IntervalLib.contains(Interval.before(BigInteger.valueOf(100)), BigInteger.valueOf(50));
                        }
                    }
                    """);
            assertTrue(eval.call("test").asBoolean());
        }

        @Test
        void beforeExcludesLaterTime() {
            var eval = JulcEval.forSource("""
                    import com.bloxbean.cardano.julc.ledger.Interval;
                    import com.bloxbean.cardano.julc.stdlib.lib.IntervalLib;
                    import java.math.BigInteger;
                    public class T {
                        public static boolean test() {
                            return !IntervalLib.contains(Interval.before(BigInteger.valueOf(100)), BigInteger.valueOf(200));
                        }
                    }
                    """);
            assertTrue(eval.call("test").asBoolean());
        }
    }

    @Nested
    class IntervalBetweenFactory {

        @Test
        void betweenContainsMiddle() {
            var eval = JulcEval.forSource("""
                    import com.bloxbean.cardano.julc.ledger.Interval;
                    import com.bloxbean.cardano.julc.stdlib.lib.IntervalLib;
                    import java.math.BigInteger;
                    public class T {
                        public static boolean test() {
                            var interval = Interval.between(BigInteger.valueOf(10), BigInteger.valueOf(20));
                            return IntervalLib.contains(interval, BigInteger.valueOf(15));
                        }
                    }
                    """);
            assertTrue(eval.call("test").asBoolean());
        }

        @Test
        void betweenContainsBoundaries() {
            var eval = JulcEval.forSource("""
                    import com.bloxbean.cardano.julc.ledger.Interval;
                    import com.bloxbean.cardano.julc.stdlib.lib.IntervalLib;
                    import java.math.BigInteger;
                    public class T {
                        public static boolean test() {
                            var interval = Interval.between(BigInteger.valueOf(10), BigInteger.valueOf(20));
                            return IntervalLib.contains(interval, BigInteger.valueOf(10))
                                && IntervalLib.contains(interval, BigInteger.valueOf(20));
                        }
                    }
                    """);
            assertTrue(eval.call("test").asBoolean());
        }

        @Test
        void betweenExcludesOutside() {
            var eval = JulcEval.forSource("""
                    import com.bloxbean.cardano.julc.ledger.Interval;
                    import com.bloxbean.cardano.julc.stdlib.lib.IntervalLib;
                    import java.math.BigInteger;
                    public class T {
                        public static boolean test() {
                            var interval = Interval.between(BigInteger.valueOf(10), BigInteger.valueOf(20));
                            return !IntervalLib.contains(interval, BigInteger.valueOf(5))
                                && !IntervalLib.contains(interval, BigInteger.valueOf(25));
                        }
                    }
                    """);
            assertTrue(eval.call("test").asBoolean());
        }
    }

    // =========================================================================
    // JulcMap.empty() — registered in StdlibRegistry
    // =========================================================================

    @Nested
    class JulcMapEmpty {

        @Test
        void julcMapEmptyIsEmpty() {
            var eval = JulcEval.forSource("""
                    import com.bloxbean.cardano.julc.core.types.JulcMap;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.core.PlutusData;
                    public class T {
                        public static boolean test() {
                            return Builtins.nullList((PlutusData)(Object) JulcMap.empty());
                        }
                    }
                    """);
            assertTrue(eval.call("test").asBoolean());
        }
    }
}
