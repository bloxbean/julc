package org.julclang.stdlib;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the JVM's builtins do where a cast or a sign would read another value. Plutus 1.63.0.0 is the model. */
class BuiltinsRangeTest {

    private static byte[] b(int... values) {
        var bytes = new byte[values.length];
        for (int i = 0; i < values.length; i++) bytes[i] = (byte) values[i];
        return bytes;
    }

    @Test
    void comparisonsReadUnsignedBytes() {
        assertFalse(Builtins.lessThanByteString(b(200), b(1)));
        assertTrue(Builtins.lessThanByteString(b(1), b(200)));
        assertFalse(Builtins.lessThanEqualsByteString(b(200), b(1)));
        assertTrue(Builtins.lessThanByteString(b(1), b(1, 0)));
        assertTrue(Builtins.lessThanEqualsByteString(b(1), b(1)));
    }

    @Test
    void bitwiseOperationsAlignFromTheFirstByte() {
        assertArrayEquals(b(1), Builtins.andByteString(false, b(1, 2), b(3)));
        assertArrayEquals(b(1, 2), Builtins.andByteString(true, b(1, 2), b(3)));
        assertArrayEquals(b(3), Builtins.orByteString(false, b(1, 2), b(3)));
        assertArrayEquals(b(3, 2), Builtins.orByteString(true, b(3), b(1, 2)));
        assertArrayEquals(b(2), Builtins.xorByteString(false, b(1, 2), b(3)));
        assertArrayEquals(b(2, 2), Builtins.xorByteString(true, b(3), b(1, 2)));
    }

    @Test
    void aSliceClampsWithNoCast() {
        var bytes = b(7, 8);
        assertArrayEquals(b(), Builtins.sliceByteString(4294967296L, 1, bytes));
        assertArrayEquals(b(7, 8), Builtins.sliceByteString(0, 4294967297L, bytes));
        assertArrayEquals(b(7), Builtins.sliceByteString(-4294967296L, 1, bytes));
        assertArrayEquals(b(), Builtins.sliceByteString(0, -4294967295L, bytes));
        assertArrayEquals(b(8), Builtins.sliceByteString(1, Long.MAX_VALUE, bytes));
        assertArrayEquals(b(7, 8), Builtins.sliceByteString(Long.MIN_VALUE, Long.MAX_VALUE, bytes));
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 2, 4294967296L, -4294967296L, Long.MIN_VALUE, Long.MAX_VALUE})
    void anIndexOutOfRangeThrows(long index) {
        assertThrows(IndexOutOfBoundsException.class, () -> Builtins.indexByteString(b(7, 8), index));
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 256, 4294967296L})
    void aValueThatIsNoByteThrows(long value) {
        assertThrows(IllegalArgumentException.class, () -> Builtins.consByteString(value, b(7)));
        assertThrows(IllegalArgumentException.class, () -> Builtins.replicateByte(1, value));
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 8193, 4294967296L, 4294967297L, Long.MIN_VALUE, Long.MAX_VALUE})
    void aReplicationPastItsRangeThrows(long length) {
        assertThrows(IllegalArgumentException.class, () -> Builtins.replicateByte(length, 0));
    }

    @Test
    void aReplicationAtItsEnds() {
        assertEquals(0, Builtins.replicateByte(0, 255).length);
        assertEquals(8192, Builtins.replicateByte(8192, 255).length);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ff", "80", "c080", "eda080", "f4908080", "e282"})
    void bytesThatAreNotTextThrow(String hex) {
        assertThrows(IllegalArgumentException.class, () -> Builtins.decodeUtf8(HexFormat.of().parseHex(hex)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "61", "c3a9", "e282ac", "f09f9880", "f48fbfbf"})
    void textDecodes(String hex) {
        assertDoesNotThrow(() -> Builtins.decodeUtf8(HexFormat.of().parseHex(hex)));
    }
}
