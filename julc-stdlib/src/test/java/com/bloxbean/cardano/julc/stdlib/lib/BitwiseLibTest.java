package com.bloxbean.cardano.julc.stdlib.lib;

import com.bloxbean.cardano.julc.testkit.JulcEval;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BitwiseLibTest {

    static JulcEval eval;

    @BeforeAll
    static void setUp() {
        eval = JulcEval.forClass(BitwiseLib.class);
    }

    @Nested
    class LogicalOps {

        @Test
        void andByteString() {
            // 0xFF AND 0x0F = 0x0F (with false = no padding/truncation)
            byte[] result = eval.call("andByteString", false, new byte[]{(byte) 0xFF}, new byte[]{(byte) 0x0F}).asByteString();
            assertArrayEquals(new byte[]{(byte) 0x0F}, result);
        }

        @Test
        void orByteString() {
            // 0xF0 OR 0x0F = 0xFF
            byte[] result = eval.call("orByteString", false, new byte[]{(byte) 0xF0}, new byte[]{(byte) 0x0F}).asByteString();
            assertArrayEquals(new byte[]{(byte) 0xFF}, result);
        }

        @Test
        void xorByteString() {
            // 0xFF XOR 0xFF = 0x00
            byte[] result = eval.call("xorByteString", false, new byte[]{(byte) 0xFF}, new byte[]{(byte) 0xFF}).asByteString();
            assertArrayEquals(new byte[]{0x00}, result);
        }

        @Test
        void xorDifferentValues() {
            // 0xF0 XOR 0x0F = 0xFF
            byte[] result = eval.call("xorByteString", false, new byte[]{(byte) 0xF0}, new byte[]{(byte) 0x0F}).asByteString();
            assertArrayEquals(new byte[]{(byte) 0xFF}, result);
        }
    }

    @Nested
    class Complement {

        @Test
        void complementByteString() {
            // complement of 0x00 = 0xFF
            byte[] result = eval.call("complementByteString", new byte[]{0x00}).asByteString();
            assertArrayEquals(new byte[]{(byte) 0xFF}, result);
        }
    }

    @Nested
    class ReadBit {

        @Test
        void readSetBit() {
            // 0x80 = 10000000, bit 7 is set
            assertTrue(eval.call("readBit", new byte[]{(byte) 0x80}, 7L).asBoolean());
        }

        @Test
        void readClearBit() {
            // 0x80 = 10000000, bit 0 is clear
            assertFalse(eval.call("readBit", new byte[]{(byte) 0x80}, 0L).asBoolean());
        }
    }

    // Note: writeBits is skipped because PlutusData.ListData params have an
    // entry-point code generation issue with compileMethod (wrap instead of unwrap).

    @Nested
    class Shift {

        @Test
        void shiftLeft() {
            // 0x01 << 1 = 0x02
            byte[] result = eval.call("shiftByteString", new byte[]{0x01}, 1L).asByteString();
            assertArrayEquals(new byte[]{0x02}, result);
        }

        @Test
        void shiftRight() {
            // 0x80 >> 1 = 0x40
            byte[] result = eval.call("shiftByteString", new byte[]{(byte) 0x80}, -1L).asByteString();
            assertArrayEquals(new byte[]{0x40}, result);
        }
    }

    @Nested
    class Rotate {

        @Test
        void rotateLeft() {
            // 0x80 rotate left 1 = 0x01 (bit wraps around)
            byte[] result = eval.call("rotateByteString", new byte[]{(byte) 0x80}, 1L).asByteString();
            assertArrayEquals(new byte[]{0x01}, result);
        }
    }

    @Nested
    class CountBits {

        @Test
        void allSet() {
            // 0xFF = 8 set bits
            assertEquals(8, eval.call("countSetBits", new byte[]{(byte) 0xFF}).asLong());
        }

        @Test
        void noneSet() {
            assertEquals(0, eval.call("countSetBits", new byte[]{0x00}).asLong());
        }
    }

    @Nested
    class FindBit {

        @Test
        void foundFirstSetBit() {
            // 0x04 = 00000100, first set bit at index 2
            assertEquals(2, eval.call("findFirstSetBit", new byte[]{0x04}).asLong());
        }

        @Test
        void noSetBitReturnsMinusOne() {
            assertEquals(-1, eval.call("findFirstSetBit", new byte[]{0x00}).asLong());
        }
    }
}
