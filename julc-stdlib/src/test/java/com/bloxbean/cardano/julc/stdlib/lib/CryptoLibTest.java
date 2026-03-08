package com.bloxbean.cardano.julc.stdlib.lib;

import com.bloxbean.cardano.julc.testkit.JulcEval;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CryptoLibTest {

    static JulcEval eval;

    @BeforeAll
    static void setUp() {
        eval = JulcEval.forClass(CryptoLib.class);
    }

    @Nested
    class Sha2_256 {

        @Test
        void produces32Bytes() {
            byte[] result = eval.call("sha2_256", new byte[]{}).asByteString();
            assertEquals(32, result.length);
        }

        @Test
        void deterministicForSameInput() {
            byte[] r1 = eval.call("sha2_256", new byte[]{1, 2, 3}).asByteString();
            byte[] r2 = eval.call("sha2_256", new byte[]{1, 2, 3}).asByteString();
            assertArrayEquals(r1, r2);
        }

        @Test
        void differentInputsDifferentOutput() {
            byte[] r1 = eval.call("sha2_256", new byte[]{1}).asByteString();
            byte[] r2 = eval.call("sha2_256", new byte[]{2}).asByteString();
            assertFalse(java.util.Arrays.equals(r1, r2));
        }
    }

    @Nested
    class Blake2b {

        @Test
        void blake2b256Produces32Bytes() {
            byte[] result = eval.call("blake2b_256", new byte[]{}).asByteString();
            assertEquals(32, result.length);
        }

        @Test
        void blake2b224Produces28Bytes() {
            byte[] result = eval.call("blake2b_224", new byte[]{}).asByteString();
            assertEquals(28, result.length);
        }
    }

    @Nested
    class Sha3_256 {

        @Test
        void produces32Bytes() {
            byte[] result = eval.call("sha3_256", new byte[]{}).asByteString();
            assertEquals(32, result.length);
        }
    }

    @Nested
    class Keccak_256 {

        @Test
        void produces32Bytes() {
            byte[] result = eval.call("keccak_256", new byte[]{}).asByteString();
            assertEquals(32, result.length);
        }
    }

    @Nested
    class Ripemd_160 {

        @Test
        void produces20Bytes() {
            byte[] result = eval.call("ripemd_160", new byte[]{}).asByteString();
            assertEquals(20, result.length);
        }
    }
}
