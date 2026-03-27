package com.bloxbean.cardano.julc.stdlib.lib;

import com.bloxbean.cardano.julc.testkit.JulcEval;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link BlsLib} BLS12-381 operations.
 * <p>
 * BLS operations require proper UPLC element types (bls12_381_G1_element, etc.),
 * not raw bytestrings. So we use inline Java source that chains operations:
 * hashToGroup/uncompress to create elements, then test the target operation.
 */
class BlsLibTest {

    // -- Shared source fragments --

    private static final String IMPORTS = """
            import com.bloxbean.cardano.julc.stdlib.Builtins;
            import java.math.BigInteger;
            """;

    // ====== G1 Operations ======

    @Nested
    class G1Operations {

        @Test
        void g1HashToGroupProducesElement() {
            // hashToGroup(msg, dst) → G1 element → compress → 48-byte bytestring
            var eval = JulcEval.forSource(IMPORTS + """
                    class T {
                        static byte[] test(byte[] msg, byte[] dst) {
                            return Builtins.bls12_381_G1_compress(
                                       Builtins.bls12_381_G1_hashToGroup(msg, dst));
                        }
                    }
                    """);
            byte[] result = eval.call("test", new byte[]{1, 2, 3}, new byte[]{}).asByteString();
            assertEquals(48, result.length);
        }

        @Test
        void g1AddCommutative() {
            var eval = JulcEval.forSource(IMPORTS + """
                    class T {
                        static boolean test(byte[] m1, byte[] m2, byte[] dst) {
                            var a = Builtins.bls12_381_G1_hashToGroup(m1, dst);
                            var b = Builtins.bls12_381_G1_hashToGroup(m2, dst);
                            var ab = Builtins.bls12_381_G1_add(a, b);
                            var ba = Builtins.bls12_381_G1_add(b, a);
                            return Builtins.bls12_381_G1_equal(ab, ba);
                        }
                    }
                    """);
            assertTrue(eval.call("test", new byte[]{1}, new byte[]{2}, new byte[]{}).asBoolean());
        }

        @Test
        void g1NegInverse() {
            // a + neg(a) == identity; compress(a + neg(a)) should equal compress(uncompress(identity))
            var eval = JulcEval.forSource(IMPORTS + """
                    class T {
                        static boolean test(byte[] msg, byte[] dst) {
                            var a = Builtins.bls12_381_G1_hashToGroup(msg, dst);
                            var negA = Builtins.bls12_381_G1_neg(a);
                            var sum = Builtins.bls12_381_G1_add(a, negA);
                            var negSum = Builtins.bls12_381_G1_neg(sum);
                            var doubleNeg = Builtins.bls12_381_G1_add(sum, negSum);
                            return Builtins.bls12_381_G1_equal(sum, doubleNeg);
                        }
                    }
                    """);
            assertTrue(eval.call("test", new byte[]{1}, new byte[]{}).asBoolean());
        }

        @Test
        void g1EqualSameElement() {
            var eval = JulcEval.forSource(IMPORTS + """
                    class T {
                        static boolean test(byte[] msg, byte[] dst) {
                            var a = Builtins.bls12_381_G1_hashToGroup(msg, dst);
                            return Builtins.bls12_381_G1_equal(a, a);
                        }
                    }
                    """);
            assertTrue(eval.call("test", new byte[]{42}, new byte[]{}).asBoolean());
        }

        @Test
        void g1EqualDifferentElements() {
            var eval = JulcEval.forSource(IMPORTS + """
                    class T {
                        static boolean test(byte[] m1, byte[] m2, byte[] dst) {
                            var a = Builtins.bls12_381_G1_hashToGroup(m1, dst);
                            var b = Builtins.bls12_381_G1_hashToGroup(m2, dst);
                            return Builtins.bls12_381_G1_equal(a, b);
                        }
                    }
                    """);
            assertFalse(eval.call("test", new byte[]{1}, new byte[]{2}, new byte[]{}).asBoolean());
        }

        @Test
        void g1CompressUncompressRoundtrip() {
            var eval = JulcEval.forSource(IMPORTS + """
                    class T {
                        static boolean test(byte[] msg, byte[] dst) {
                            var g1 = Builtins.bls12_381_G1_hashToGroup(msg, dst);
                            var compressed = Builtins.bls12_381_G1_compress(g1);
                            var uncompressed = Builtins.bls12_381_G1_uncompress(compressed);
                            return Builtins.bls12_381_G1_equal(g1, uncompressed);
                        }
                    }
                    """);
            assertTrue(eval.call("test", new byte[]{1, 2, 3}, new byte[]{}).asBoolean());
        }

        @Test
        void g1CompressProduces48Bytes() {
            var eval = JulcEval.forSource(IMPORTS + """
                    class T {
                        static BigInteger test(byte[] msg, byte[] dst) {
                            var g1 = Builtins.bls12_381_G1_hashToGroup(msg, dst);
                            var compressed = Builtins.bls12_381_G1_compress(g1);
                            return BigInteger.valueOf(Builtins.lengthOfByteString(compressed));
                        }
                    }
                    """);
            assertEquals(48, eval.call("test", new byte[]{5}, new byte[]{}).asLong());
        }

        @Test
        void g1ScalarMulByOne() {
            // 1 * a == a
            var eval = JulcEval.forSource(IMPORTS + """
                    class T {
                        static boolean test(byte[] msg, byte[] dst) {
                            var a = Builtins.bls12_381_G1_hashToGroup(msg, dst);
                            var scaled = Builtins.bls12_381_G1_scalarMul(BigInteger.valueOf(1), a);
                            return Builtins.bls12_381_G1_equal(a, scaled);
                        }
                    }
                    """);
            assertTrue(eval.call("test", new byte[]{7}, new byte[]{}).asBoolean());
        }

        @Test
        void g1ScalarMulByTwo() {
            // 2 * a == a + a
            var eval = JulcEval.forSource(IMPORTS + """
                    class T {
                        static boolean test(byte[] msg, byte[] dst) {
                            var a = Builtins.bls12_381_G1_hashToGroup(msg, dst);
                            var doubled = Builtins.bls12_381_G1_scalarMul(BigInteger.valueOf(2), a);
                            var added = Builtins.bls12_381_G1_add(a, a);
                            return Builtins.bls12_381_G1_equal(doubled, added);
                        }
                    }
                    """);
            assertTrue(eval.call("test", new byte[]{7}, new byte[]{}).asBoolean());
        }

        @Test
        void g1DoubleNegIsIdentity() {
            // neg(neg(a)) == a
            var eval = JulcEval.forSource(IMPORTS + """
                    class T {
                        static boolean test(byte[] msg, byte[] dst) {
                            var a = Builtins.bls12_381_G1_hashToGroup(msg, dst);
                            var nn = Builtins.bls12_381_G1_neg(Builtins.bls12_381_G1_neg(a));
                            return Builtins.bls12_381_G1_equal(a, nn);
                        }
                    }
                    """);
            assertTrue(eval.call("test", new byte[]{3}, new byte[]{}).asBoolean());
        }
    }

    // ====== G2 Operations ======

    @Nested
    class G2Operations {

        @Test
        void g2HashToGroupProducesElement() {
            var eval = JulcEval.forSource(IMPORTS + """
                    class T {
                        static byte[] test(byte[] msg, byte[] dst) {
                            return Builtins.bls12_381_G2_compress(
                                       Builtins.bls12_381_G2_hashToGroup(msg, dst));
                        }
                    }
                    """);
            byte[] result = eval.call("test", new byte[]{1, 2, 3}, new byte[]{}).asByteString();
            assertEquals(96, result.length);
        }

        @Test
        void g2AddCommutative() {
            var eval = JulcEval.forSource(IMPORTS + """
                    class T {
                        static boolean test(byte[] m1, byte[] m2, byte[] dst) {
                            var a = Builtins.bls12_381_G2_hashToGroup(m1, dst);
                            var b = Builtins.bls12_381_G2_hashToGroup(m2, dst);
                            var ab = Builtins.bls12_381_G2_add(a, b);
                            var ba = Builtins.bls12_381_G2_add(b, a);
                            return Builtins.bls12_381_G2_equal(ab, ba);
                        }
                    }
                    """);
            assertTrue(eval.call("test", new byte[]{1}, new byte[]{2}, new byte[]{}).asBoolean());
        }

        @Test
        void g2EqualSameElement() {
            var eval = JulcEval.forSource(IMPORTS + """
                    class T {
                        static boolean test(byte[] msg, byte[] dst) {
                            var a = Builtins.bls12_381_G2_hashToGroup(msg, dst);
                            return Builtins.bls12_381_G2_equal(a, a);
                        }
                    }
                    """);
            assertTrue(eval.call("test", new byte[]{42}, new byte[]{}).asBoolean());
        }

        @Test
        void g2EqualDifferentElements() {
            var eval = JulcEval.forSource(IMPORTS + """
                    class T {
                        static boolean test(byte[] m1, byte[] m2, byte[] dst) {
                            var a = Builtins.bls12_381_G2_hashToGroup(m1, dst);
                            var b = Builtins.bls12_381_G2_hashToGroup(m2, dst);
                            return Builtins.bls12_381_G2_equal(a, b);
                        }
                    }
                    """);
            assertFalse(eval.call("test", new byte[]{1}, new byte[]{2}, new byte[]{}).asBoolean());
        }

        @Test
        void g2CompressUncompressRoundtrip() {
            var eval = JulcEval.forSource(IMPORTS + """
                    class T {
                        static boolean test(byte[] msg, byte[] dst) {
                            var g2 = Builtins.bls12_381_G2_hashToGroup(msg, dst);
                            var compressed = Builtins.bls12_381_G2_compress(g2);
                            var uncompressed = Builtins.bls12_381_G2_uncompress(compressed);
                            return Builtins.bls12_381_G2_equal(g2, uncompressed);
                        }
                    }
                    """);
            assertTrue(eval.call("test", new byte[]{1, 2, 3}, new byte[]{}).asBoolean());
        }

        @Test
        void g2ScalarMulByOne() {
            var eval = JulcEval.forSource(IMPORTS + """
                    class T {
                        static boolean test(byte[] msg, byte[] dst) {
                            var a = Builtins.bls12_381_G2_hashToGroup(msg, dst);
                            var scaled = Builtins.bls12_381_G2_scalarMul(BigInteger.valueOf(1), a);
                            return Builtins.bls12_381_G2_equal(a, scaled);
                        }
                    }
                    """);
            assertTrue(eval.call("test", new byte[]{7}, new byte[]{}).asBoolean());
        }

        @Test
        void g2ScalarMulByTwo() {
            var eval = JulcEval.forSource(IMPORTS + """
                    class T {
                        static boolean test(byte[] msg, byte[] dst) {
                            var a = Builtins.bls12_381_G2_hashToGroup(msg, dst);
                            var doubled = Builtins.bls12_381_G2_scalarMul(BigInteger.valueOf(2), a);
                            var added = Builtins.bls12_381_G2_add(a, a);
                            return Builtins.bls12_381_G2_equal(doubled, added);
                        }
                    }
                    """);
            assertTrue(eval.call("test", new byte[]{7}, new byte[]{}).asBoolean());
        }

        @Test
        void g2DoubleNegIsIdentity() {
            var eval = JulcEval.forSource(IMPORTS + """
                    class T {
                        static boolean test(byte[] msg, byte[] dst) {
                            var a = Builtins.bls12_381_G2_hashToGroup(msg, dst);
                            var nn = Builtins.bls12_381_G2_neg(Builtins.bls12_381_G2_neg(a));
                            return Builtins.bls12_381_G2_equal(a, nn);
                        }
                    }
                    """);
            assertTrue(eval.call("test", new byte[]{3}, new byte[]{}).asBoolean());
        }
    }

    // ====== Pairing Operations ======

    @Nested
    class PairingOperations {

        @Test
        void millerLoopProducesResult() {
            // millerLoop(g1, g2) should produce a result that can be used in finalVerify
            var eval = JulcEval.forSource(IMPORTS + """
                    class T {
                        static boolean test(byte[] m1, byte[] m2, byte[] dst) {
                            var g1 = Builtins.bls12_381_G1_hashToGroup(m1, dst);
                            var g2 = Builtins.bls12_381_G2_hashToGroup(m2, dst);
                            var ml = Builtins.bls12_381_millerLoop(g1, g2);
                            return Builtins.bls12_381_finalVerify(ml, ml);
                        }
                    }
                    """);
            assertTrue(eval.call("test", new byte[]{1}, new byte[]{2}, new byte[]{}).asBoolean());
        }

        @Test
        void mulMlResultAssociative() {
            // mulMlResult(a, mulMlResult(b, c)) == mulMlResult(mulMlResult(a, b), c)
            var eval = JulcEval.forSource(IMPORTS + """
                    class T {
                        static boolean test(byte[] m1, byte[] m2, byte[] m3, byte[] dst) {
                            var g1a = Builtins.bls12_381_G1_hashToGroup(m1, dst);
                            var g2a = Builtins.bls12_381_G2_hashToGroup(m1, dst);
                            var g1b = Builtins.bls12_381_G1_hashToGroup(m2, dst);
                            var g2b = Builtins.bls12_381_G2_hashToGroup(m2, dst);
                            var g1c = Builtins.bls12_381_G1_hashToGroup(m3, dst);
                            var g2c = Builtins.bls12_381_G2_hashToGroup(m3, dst);
                            var mlA = Builtins.bls12_381_millerLoop(g1a, g2a);
                            var mlB = Builtins.bls12_381_millerLoop(g1b, g2b);
                            var mlC = Builtins.bls12_381_millerLoop(g1c, g2c);
                            var left = Builtins.bls12_381_mulMlResult(mlA,
                                           Builtins.bls12_381_mulMlResult(mlB, mlC));
                            var right = Builtins.bls12_381_mulMlResult(
                                            Builtins.bls12_381_mulMlResult(mlA, mlB), mlC);
                            return Builtins.bls12_381_finalVerify(left, right);
                        }
                    }
                    """);
            assertTrue(eval.call("test",
                    new byte[]{1}, new byte[]{2}, new byte[]{3}, new byte[]{}).asBoolean());
        }

        @Test
        void finalVerifyBilinearity() {
            // e(a*G1, G2) == e(G1, a*G2) — bilinearity of pairing
            var eval = JulcEval.forSource(IMPORTS + """
                    class T {
                        static boolean test(byte[] msg, byte[] dst) {
                            var g1 = Builtins.bls12_381_G1_hashToGroup(msg, dst);
                            var g2 = Builtins.bls12_381_G2_hashToGroup(msg, dst);
                            var scalar = BigInteger.valueOf(7);
                            var left = Builtins.bls12_381_millerLoop(
                                           Builtins.bls12_381_G1_scalarMul(scalar, g1), g2);
                            var right = Builtins.bls12_381_millerLoop(
                                            g1, Builtins.bls12_381_G2_scalarMul(scalar, g2));
                            return Builtins.bls12_381_finalVerify(left, right);
                        }
                    }
                    """);
            assertTrue(eval.call("test", new byte[]{10}, new byte[]{}).asBoolean());
        }

        @Test
        void finalVerifyDifferentPairingsFails() {
            // e(a, b) != e(c, d) for different points
            var eval = JulcEval.forSource(IMPORTS + """
                    class T {
                        static boolean test(byte[] m1, byte[] m2, byte[] dst) {
                            var g1a = Builtins.bls12_381_G1_hashToGroup(m1, dst);
                            var g2a = Builtins.bls12_381_G2_hashToGroup(m1, dst);
                            var g1b = Builtins.bls12_381_G1_hashToGroup(m2, dst);
                            var g2b = Builtins.bls12_381_G2_hashToGroup(m2, dst);
                            var mlA = Builtins.bls12_381_millerLoop(g1a, g2a);
                            var mlB = Builtins.bls12_381_millerLoop(g1b, g2b);
                            return Builtins.bls12_381_finalVerify(mlA, mlB);
                        }
                    }
                    """);
            assertFalse(eval.call("test", new byte[]{1}, new byte[]{2}, new byte[]{}).asBoolean());
        }
    }

    // ====== BlsLib wrapper tests (via @OnchainLibrary compilation) ======

    @Nested
    class BlsLibWrappers {

        @Test
        void blsLibG1AddCompiles() {
            // Verify BlsLib.g1Add compiles to correct UPLC and produces valid results
            var eval = JulcEval.forSource("""
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.stdlib.lib.BlsLib;
                    import java.math.BigInteger;
                    class T {
                        static boolean test(byte[] msg, byte[] dst) {
                            var a = Builtins.bls12_381_G1_hashToGroup(msg, dst);
                            var b = Builtins.bls12_381_G1_hashToGroup(msg, dst);
                            var result = BlsLib.g1Add(a, b);
                            var expected = Builtins.bls12_381_G1_scalarMul(BigInteger.valueOf(2), a);
                            return BlsLib.g1Equal(result, expected);
                        }
                    }
                    """);
            assertTrue(eval.call("test", new byte[]{5}, new byte[]{}).asBoolean());
        }

        @Test
        void blsLibG1NegCompiles() {
            var eval = JulcEval.forSource("""
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.stdlib.lib.BlsLib;
                    class T {
                        static boolean test(byte[] msg, byte[] dst) {
                            var a = Builtins.bls12_381_G1_hashToGroup(msg, dst);
                            var neg = BlsLib.g1Neg(a);
                            var negNeg = BlsLib.g1Neg(neg);
                            return BlsLib.g1Equal(a, negNeg);
                        }
                    }
                    """);
            assertTrue(eval.call("test", new byte[]{9}, new byte[]{}).asBoolean());
        }

        @Test
        void blsLibG1ScalarMulCompiles() {
            var eval = JulcEval.forSource("""
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.stdlib.lib.BlsLib;
                    import java.math.BigInteger;
                    class T {
                        static boolean test(byte[] msg, byte[] dst) {
                            var a = Builtins.bls12_381_G1_hashToGroup(msg, dst);
                            var tripled = BlsLib.g1ScalarMul(BigInteger.valueOf(3), a);
                            var added = BlsLib.g1Add(BlsLib.g1Add(a, a), a);
                            return BlsLib.g1Equal(tripled, added);
                        }
                    }
                    """);
            assertTrue(eval.call("test", new byte[]{4}, new byte[]{}).asBoolean());
        }

        @Test
        void blsLibG1CompressUncompressCompiles() {
            var eval = JulcEval.forSource("""
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.stdlib.lib.BlsLib;
                    class T {
                        static boolean test(byte[] msg, byte[] dst) {
                            var g1 = Builtins.bls12_381_G1_hashToGroup(msg, dst);
                            var roundtripped = BlsLib.g1Uncompress(BlsLib.g1Compress(g1));
                            return BlsLib.g1Equal(g1, roundtripped);
                        }
                    }
                    """);
            assertTrue(eval.call("test", new byte[]{1}, new byte[]{}).asBoolean());
        }

        @Test
        void blsLibG2AddCompiles() {
            var eval = JulcEval.forSource("""
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.stdlib.lib.BlsLib;
                    import java.math.BigInteger;
                    class T {
                        static boolean test(byte[] msg, byte[] dst) {
                            var a = Builtins.bls12_381_G2_hashToGroup(msg, dst);
                            var result = BlsLib.g2Add(a, a);
                            var expected = BlsLib.g2ScalarMul(BigInteger.valueOf(2), a);
                            return BlsLib.g2Equal(result, expected);
                        }
                    }
                    """);
            assertTrue(eval.call("test", new byte[]{5}, new byte[]{}).asBoolean());
        }

        @Test
        void blsLibMillerLoopAndFinalVerifyCompile() {
            var eval = JulcEval.forSource("""
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.stdlib.lib.BlsLib;
                    import java.math.BigInteger;
                    class T {
                        static boolean test(byte[] msg, byte[] dst) {
                            var g1 = Builtins.bls12_381_G1_hashToGroup(msg, dst);
                            var g2 = Builtins.bls12_381_G2_hashToGroup(msg, dst);
                            var ml = BlsLib.millerLoop(g1, g2);
                            return BlsLib.finalVerify(ml, ml);
                        }
                    }
                    """);
            assertTrue(eval.call("test", new byte[]{1}, new byte[]{}).asBoolean());
        }

        @Test
        void blsLibMulMlResultCompiles() {
            var eval = JulcEval.forSource("""
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.stdlib.lib.BlsLib;
                    class T {
                        static boolean test(byte[] m1, byte[] m2, byte[] dst) {
                            var g1a = Builtins.bls12_381_G1_hashToGroup(m1, dst);
                            var g2a = Builtins.bls12_381_G2_hashToGroup(m1, dst);
                            var g1b = Builtins.bls12_381_G1_hashToGroup(m2, dst);
                            var g2b = Builtins.bls12_381_G2_hashToGroup(m2, dst);
                            var mlA = BlsLib.millerLoop(g1a, g2a);
                            var mlB = BlsLib.millerLoop(g1b, g2b);
                            var combined = BlsLib.mulMlResult(mlA, mlB);
                            return BlsLib.finalVerify(combined, combined);
                        }
                    }
                    """);
            assertTrue(eval.call("test", new byte[]{1}, new byte[]{2}, new byte[]{}).asBoolean());
        }

        @Test
        void blsLibG1HashToGroupCompiles() {
            var eval = JulcEval.forSource("""
                    import com.bloxbean.cardano.julc.stdlib.lib.BlsLib;
                    class T {
                        static boolean test(byte[] msg, byte[] dst) {
                            var a = BlsLib.g1HashToGroup(msg, dst);
                            return BlsLib.g1Equal(a, a);
                        }
                    }
                    """);
            assertTrue(eval.call("test", new byte[]{1}, new byte[]{}).asBoolean());
        }

        @Test
        void blsLibG2HashToGroupCompiles() {
            var eval = JulcEval.forSource("""
                    import com.bloxbean.cardano.julc.stdlib.lib.BlsLib;
                    class T {
                        static boolean test(byte[] msg, byte[] dst) {
                            var a = BlsLib.g2HashToGroup(msg, dst);
                            return BlsLib.g2Equal(a, a);
                        }
                    }
                    """);
            assertTrue(eval.call("test", new byte[]{1}, new byte[]{}).asBoolean());
        }
    }
}
