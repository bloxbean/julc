package com.bloxbean.cardano.julc.stdlib.lib;

import com.bloxbean.cardano.julc.testkit.JulcEval;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link NativeValueLib} — PV11 MaryEraValue operations (CIP-153).
 * All tests use JulcEval to compile and evaluate through the UPLC VM,
 * since Value is a native UPLC constant type.
 */
class NativeValueLibTest {

    private static final String IMPORTS =
            "import com.bloxbean.cardano.julc.stdlib.Builtins;\n"
            + "import com.bloxbean.cardano.julc.core.PlutusData;\n"
            + "import java.math.BigInteger;\n";

    // Empty Value = unValueData(mapData(mkNilPairData()))
    private static final String EV = "Builtins.unValueData(Builtins.mapData(Builtins.mkNilPairData()))";

    private static String src(String body) {
        return IMPORTS + "class T {\n" + body + "\n}\n";
    }

    @Nested
    class InsertAndLookup {

        @Test
        void insertThenLookup() {
            var eval = JulcEval.forSource(src(
                    "static BigInteger test(byte[] policy, byte[] token) {"
                    + "  var empty = " + EV + ";"
                    + "  var value = Builtins.insertCoin(policy, token, 100, empty);"
                    + "  return BigInteger.valueOf(Builtins.lookupCoin(policy, token, value));"
                    + "}"));
            assertEquals(100, eval.call("test", new byte[]{1, 2, 3}, new byte[]{4, 5}).asLong());
        }

        @Test
        void lookupMissing() {
            var eval = JulcEval.forSource(src(
                    "static BigInteger test(byte[] policy, byte[] token) {"
                    + "  var empty = " + EV + ";"
                    + "  return BigInteger.valueOf(Builtins.lookupCoin(policy, token, empty));"
                    + "}"));
            assertEquals(0, eval.call("test", new byte[]{1}, new byte[]{2}).asLong());
        }

        @Test
        void lookupAbsentTokenInPresentPolicy() {
            // Policy exists with token {4,5} but lookup uses different token {6,7}
            var eval = JulcEval.forSource(src(
                    "static BigInteger test(byte[] policy, byte[] token, byte[] otherToken) {"
                    + "  var empty = " + EV + ";"
                    + "  var value = Builtins.insertCoin(policy, token, 100, empty);"
                    + "  return BigInteger.valueOf(Builtins.lookupCoin(policy, otherToken, value));"
                    + "}"));
            assertEquals(0, eval.call("test", new byte[]{1, 2, 3}, new byte[]{4, 5}, new byte[]{6, 7}).asLong());
        }

        @Test
        void insertOverwrite() {
            var eval = JulcEval.forSource(src(
                    "static BigInteger test(byte[] policy, byte[] token) {"
                    + "  var empty = " + EV + ";"
                    + "  var v1 = Builtins.insertCoin(policy, token, 50, empty);"
                    + "  var v2 = Builtins.insertCoin(policy, token, 200, v1);"
                    + "  return BigInteger.valueOf(Builtins.lookupCoin(policy, token, v2));"
                    + "}"));
            assertEquals(200, eval.call("test", new byte[]{1}, new byte[]{2}).asLong());
        }
    }

    @Nested
    class UnionAndContains {

        @Test
        void unionMerges() {
            var eval = JulcEval.forSource(src(
                    "static BigInteger test(byte[] policy, byte[] token) {"
                    + "  var empty = " + EV + ";"
                    + "  var a = Builtins.insertCoin(policy, token, 30, empty);"
                    + "  var b = Builtins.insertCoin(policy, token, 70, empty);"
                    + "  var merged = Builtins.unionValue(a, b);"
                    + "  return BigInteger.valueOf(Builtins.lookupCoin(policy, token, merged));"
                    + "}"));
            assertEquals(100, eval.call("test", new byte[]{1}, new byte[]{2}).asLong());
        }

        @Test
        void containsSelf() {
            var eval = JulcEval.forSource(src(
                    "static boolean test(byte[] policy, byte[] token) {"
                    + "  var empty = " + EV + ";"
                    + "  var value = Builtins.insertCoin(policy, token, 50, empty);"
                    + "  return Builtins.valueContains(value, value);"
                    + "}"));
            assertTrue(eval.call("test", new byte[]{1}, new byte[]{2}).asBoolean());
        }

        @Test
        void containsSmaller() {
            var eval = JulcEval.forSource(src(
                    "static boolean test(byte[] policy, byte[] token) {"
                    + "  var empty = " + EV + ";"
                    + "  var bigger = Builtins.insertCoin(policy, token, 100, empty);"
                    + "  var smaller = Builtins.insertCoin(policy, token, 50, empty);"
                    + "  return Builtins.valueContains(bigger, smaller);"
                    + "}"));
            assertTrue(eval.call("test", new byte[]{1}, new byte[]{2}).asBoolean());
        }

        @Test
        void doesNotContainLarger() {
            var eval = JulcEval.forSource(src(
                    "static boolean test(byte[] policy, byte[] token) {"
                    + "  var empty = " + EV + ";"
                    + "  var smaller = Builtins.insertCoin(policy, token, 50, empty);"
                    + "  var bigger = Builtins.insertCoin(policy, token, 100, empty);"
                    + "  return Builtins.valueContains(smaller, bigger);"
                    + "}"));
            assertFalse(eval.call("test", new byte[]{1}, new byte[]{2}).asBoolean());
        }
    }

    @Nested
    class ScaleValueTests {

        @Test
        void scaleByTwo() {
            var eval = JulcEval.forSource(src(
                    "static BigInteger test(byte[] policy, byte[] token) {"
                    + "  var empty = " + EV + ";"
                    + "  var value = Builtins.insertCoin(policy, token, 50, empty);"
                    + "  var scaled = Builtins.scaleValue(2, value);"
                    + "  return BigInteger.valueOf(Builtins.lookupCoin(policy, token, scaled));"
                    + "}"));
            assertEquals(100, eval.call("test", new byte[]{1}, new byte[]{2}).asLong());
        }

        @Test
        void scaleByZero() {
            var eval = JulcEval.forSource(src(
                    "static BigInteger test(byte[] policy, byte[] token) {"
                    + "  var empty = " + EV + ";"
                    + "  var value = Builtins.insertCoin(policy, token, 50, empty);"
                    + "  var scaled = Builtins.scaleValue(0, value);"
                    + "  return BigInteger.valueOf(Builtins.lookupCoin(policy, token, scaled));"
                    + "}"));
            assertEquals(0, eval.call("test", new byte[]{1}, new byte[]{2}).asLong());
        }

        @Test
        void scaleByNegativeOne() {
            var eval = JulcEval.forSource(src(
                    "static BigInteger test(byte[] policy, byte[] token) {"
                    + "  var empty = " + EV + ";"
                    + "  var value = Builtins.insertCoin(policy, token, 50, empty);"
                    + "  var scaled = Builtins.scaleValue(-1, value);"
                    + "  return BigInteger.valueOf(Builtins.lookupCoin(policy, token, scaled));"
                    + "}"));
            assertEquals(-50, eval.call("test", new byte[]{1}, new byte[]{2}).asLong());
        }
    }

    @Nested
    class DataRoundtrip {

        @Test
        void valueToDataAndBack() {
            var eval = JulcEval.forSource(src(
                    "static BigInteger test(byte[] policy, byte[] token) {"
                    + "  var empty = " + EV + ";"
                    + "  var value = Builtins.insertCoin(policy, token, 42, empty);"
                    + "  var data = Builtins.valueData(value);"
                    + "  var restored = Builtins.unValueData(data);"
                    + "  return BigInteger.valueOf(Builtins.lookupCoin(policy, token, restored));"
                    + "}"));
            assertEquals(42, eval.call("test", new byte[]{1}, new byte[]{2}).asLong());
        }
    }

    @Nested
    class NativeValueLibWrappers {

        private static final String LIB_IMPORTS =
                "import com.bloxbean.cardano.julc.stdlib.Builtins;\n"
                + "import com.bloxbean.cardano.julc.stdlib.lib.NativeValueLib;\n"
                + "import com.bloxbean.cardano.julc.core.PlutusData;\n"
                + "import java.math.BigInteger;\n";

        private static String libSrc(String body) {
            return LIB_IMPORTS + "class T {\n" + body + "\n}\n";
        }

        @Test
        void insertCoinCompiles() {
            var eval = JulcEval.forSource(libSrc(
                    "static BigInteger test(byte[] policy, byte[] token) {"
                    + "  var empty = " + EV + ";"
                    + "  var value = NativeValueLib.insertCoin(policy, token, 77, empty);"
                    + "  return BigInteger.valueOf(NativeValueLib.lookupCoin(policy, token, value));"
                    + "}"));
            assertEquals(77, eval.call("test", new byte[]{1}, new byte[]{2}).asLong());
        }

        @Test
        void unionCompiles() {
            var eval = JulcEval.forSource(libSrc(
                    "static BigInteger test(byte[] policy, byte[] token) {"
                    + "  var empty = " + EV + ";"
                    + "  var a = NativeValueLib.insertCoin(policy, token, 10, empty);"
                    + "  var b = NativeValueLib.insertCoin(policy, token, 20, empty);"
                    + "  var merged = NativeValueLib.union(a, b);"
                    + "  return BigInteger.valueOf(NativeValueLib.lookupCoin(policy, token, merged));"
                    + "}"));
            assertEquals(30, eval.call("test", new byte[]{1}, new byte[]{2}).asLong());
        }

        @Test
        void containsCompiles() {
            var eval = JulcEval.forSource(libSrc(
                    "static boolean test(byte[] policy, byte[] token) {"
                    + "  var empty = " + EV + ";"
                    + "  var v = NativeValueLib.insertCoin(policy, token, 50, empty);"
                    + "  return NativeValueLib.contains(v, v);"
                    + "}"));
            assertTrue(eval.call("test", new byte[]{1}, new byte[]{2}).asBoolean());
        }

        @Test
        void scaleCompiles() {
            var eval = JulcEval.forSource(libSrc(
                    "static BigInteger test(byte[] policy, byte[] token) {"
                    + "  var empty = " + EV + ";"
                    + "  var v = NativeValueLib.insertCoin(policy, token, 25, empty);"
                    + "  var scaled = NativeValueLib.scale(3, v);"
                    + "  return BigInteger.valueOf(NativeValueLib.lookupCoin(policy, token, scaled));"
                    + "}"));
            assertEquals(75, eval.call("test", new byte[]{1}, new byte[]{2}).asLong());
        }

        @Test
        void fromDataToDataRoundtrip() {
            var eval = JulcEval.forSource(libSrc(
                    "static BigInteger test(byte[] policy, byte[] token) {"
                    + "  var empty = " + EV + ";"
                    + "  var v = NativeValueLib.insertCoin(policy, token, 99, empty);"
                    + "  var data = NativeValueLib.toData(v);"
                    + "  var restored = NativeValueLib.fromData(data);"
                    + "  return BigInteger.valueOf(NativeValueLib.lookupCoin(policy, token, restored));"
                    + "}"));
            assertEquals(99, eval.call("test", new byte[]{1}, new byte[]{2}).asLong());
        }
    }
}
