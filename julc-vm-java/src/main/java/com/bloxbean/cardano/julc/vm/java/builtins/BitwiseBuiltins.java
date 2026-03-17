package com.bloxbean.cardano.julc.vm.java.builtins;

import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.vm.java.CekValue;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;

import static com.bloxbean.cardano.julc.vm.java.builtins.BuiltinHelper.*;

/**
 * Bitwise operation builtins (CIP-121, CIP-122, CIP-123).
 */
public final class BitwiseBuiltins {

    private BitwiseBuiltins() {}

    /**
     * IntegerToByteString: arity=3 → (endianness, length, integer)
     * endianness: True = big-endian, False = little-endian
     * length: 0 = minimal, >0 = exact (zero-padded)
     */
    public static CekValue integerToByteString(List<CekValue> args) {
        var bigEndian = asBool(args.get(0), "IntegerToByteString");
        var len = asInteger(args.get(1), "IntegerToByteString");
        var value = asInteger(args.get(2), "IntegerToByteString");

        if (value.signum() < 0) {
            throw new BuiltinException("IntegerToByteString: negative integer");
        }

        int requestedLen = len.intValue();
        if (requestedLen < 0) {
            throw new BuiltinException("IntegerToByteString: negative length");
        }
        // Limit to 8192 bytes
        if (requestedLen > 8192) {
            throw new BuiltinException("IntegerToByteString: length exceeds 8192 bytes");
        }

        byte[] bytes;
        if (value.signum() == 0) {
            bytes = new byte[0];
        } else {
            bytes = value.toByteArray();
            // Remove leading zero byte if present
            if (bytes[0] == 0) {
                bytes = Arrays.copyOfRange(bytes, 1, bytes.length);
            }
        }

        // Check size constraint
        if (requestedLen == 0) {
            // Minimal representation
            if (bytes.length > 8192) {
                throw new BuiltinException("IntegerToByteString: result exceeds 8192 bytes");
            }
        } else {
            if (bytes.length > requestedLen) {
                throw new BuiltinException("IntegerToByteString: integer doesn't fit in " + requestedLen + " bytes");
            }
        }

        // bytes is big-endian from BigInteger
        if (requestedLen > 0) {
            // Pad to requested length
            byte[] padded = new byte[requestedLen];
            // Big-endian: pad at front
            System.arraycopy(bytes, 0, padded, requestedLen - bytes.length, bytes.length);
            bytes = padded;
        }

        if (!bigEndian) {
            // Reverse for little-endian
            reverse(bytes);
        }

        return mkByteString(bytes);
    }

    /**
     * ByteStringToInteger: arity=2 → (endianness, bytestring)
     * endianness: True = big-endian, False = little-endian
     */
    public static CekValue byteStringToInteger(List<CekValue> args) {
        var bigEndian = asBool(args.get(0), "ByteStringToInteger");
        var bs = asByteString(args.get(1), "ByteStringToInteger");

        if (bs.length == 0) {
            return mkInteger(BigInteger.ZERO);
        }

        byte[] bytes = bs.clone();
        if (!bigEndian) {
            reverse(bytes);
        }

        // Interpret as unsigned big-endian
        return mkInteger(new BigInteger(1, bytes));
    }

    /**
     * AndByteString: arity=3 → (padding, a, b)
     * padding: True = pad shorter with 0xff, False = pad shorter with 0x00
     */
    public static CekValue andByteString(List<CekValue> args) {
        var padding = asBool(args.get(0), "AndByteString");
        var a = asByteString(args.get(1), "AndByteString");
        var b = asByteString(args.get(2), "AndByteString");
        // AND identity is 0xff (x & 0xff = x)
        return mkByteString(bitwiseOp(a, b, padding, (byte) 0xff, (x, y) -> (byte)(x & y)));
    }

    public static CekValue orByteString(List<CekValue> args) {
        var padding = asBool(args.get(0), "OrByteString");
        var a = asByteString(args.get(1), "OrByteString");
        var b = asByteString(args.get(2), "OrByteString");
        // OR identity is 0x00 (x | 0x00 = x)
        return mkByteString(bitwiseOp(a, b, padding, (byte) 0x00, (x, y) -> (byte)(x | y)));
    }

    public static CekValue xorByteString(List<CekValue> args) {
        var padding = asBool(args.get(0), "XorByteString");
        var a = asByteString(args.get(1), "XorByteString");
        var b = asByteString(args.get(2), "XorByteString");
        // XOR identity is 0x00 (x ^ 0x00 = x)
        return mkByteString(bitwiseOp(a, b, padding, (byte) 0x00, (x, y) -> (byte)(x ^ y)));
    }

    public static CekValue complementByteString(List<CekValue> args) {
        var bs = asByteString(args.get(0), "ComplementByteString");
        var result = new byte[bs.length];
        for (int i = 0; i < bs.length; i++) {
            result[i] = (byte) ~bs[i];
        }
        return mkByteString(result);
    }

    public static CekValue readBit(List<CekValue> args) {
        var bs = asByteString(args.get(0), "ReadBit");
        var idx = asInteger(args.get(1), "ReadBit");
        long bitIdx = idx.longValue();
        if (bitIdx < 0 || bitIdx >= (long)bs.length * 8) {
            throw new BuiltinException("ReadBit: index " + bitIdx + " out of range for " + bs.length + " bytes");
        }
        int byteIdx = (int)(bitIdx / 8);
        int bitOffset = (int)(bitIdx % 8);
        // Bit 0 is the LSB of the last byte
        int actualByte = bs.length - 1 - byteIdx;
        boolean bit = ((bs[actualByte] >> bitOffset) & 1) == 1;
        return mkBool(bit);
    }

    public static CekValue writeBits(List<CekValue> args) {
        var bs = asByteString(args.get(0), "WriteBits");
        var indices = asListConst(args.get(1), "WriteBits");
        var bitValue = asBool(args.get(2), "WriteBits");

        byte[] result = bs.clone();
        for (var idxConst : indices.values()) {
            if (!(idxConst instanceof Constant.IntegerConst ic)) {
                throw new BuiltinException("WriteBits: index must be integer");
            }
            long bitIdx = ic.value().longValue();
            if (bitIdx < 0 || bitIdx >= (long)result.length * 8) {
                throw new BuiltinException("WriteBits: index " + bitIdx + " out of range");
            }
            int byteIdx = (int)(bitIdx / 8);
            int bitOffset = (int)(bitIdx % 8);
            int actualByte = result.length - 1 - byteIdx;
            if (bitValue) {
                result[actualByte] |= (byte)(1 << bitOffset);
            } else {
                result[actualByte] &= (byte)~(1 << bitOffset);
            }
        }
        return mkByteString(result);
    }

    public static CekValue replicateByte(List<CekValue> args) {
        var len = asInteger(args.get(0), "ReplicateByte");
        var byteVal = asInteger(args.get(1), "ReplicateByte");

        int length = len.intValue();
        if (length < 0) {
            throw new BuiltinException("ReplicateByte: negative length");
        }
        if (length > 8192) {
            throw new BuiltinException("ReplicateByte: length exceeds 8192");
        }

        long val = byteVal.longValue();
        if (val < 0 || val > 255) {
            throw new BuiltinException("ReplicateByte: byte value out of range: " + val);
        }

        byte[] result = new byte[length];
        Arrays.fill(result, (byte) val);
        return mkByteString(result);
    }

    public static CekValue shiftByteString(List<CekValue> args) {
        var bs = asByteString(args.get(0), "ShiftByteString");
        var shift = asInteger(args.get(1), "ShiftByteString");

        if (bs.length == 0) return mkByteString(new byte[0]);

        long totalBits = (long) bs.length * 8;
        byte[] result = new byte[bs.length];

        // If shift magnitude >= totalBits, result is all zeros
        if (shift.abs().compareTo(BigInteger.valueOf(totalBits)) >= 0) {
            return mkByteString(result);
        }

        int n = shift.intValue();
        if (n > 0) {
            shiftLeft(bs, result, n);
        } else if (n < 0) {
            shiftRight(bs, result, -n);
        } else {
            System.arraycopy(bs, 0, result, 0, bs.length);
        }

        return mkByteString(result);
    }

    public static CekValue rotateByteString(List<CekValue> args) {
        var bs = asByteString(args.get(0), "RotateByteString");
        var shift = asInteger(args.get(1), "RotateByteString");

        if (bs.length == 0) return mkByteString(new byte[0]);

        long totalBits = (long) bs.length * 8;
        // Use BigInteger modulo for correct handling of huge values
        BigInteger totalBitsBig = BigInteger.valueOf(totalBits);
        int n = shift.mod(totalBitsBig).intValue();
        if (n == 0) return mkByteString(bs.clone());

        // Rotate left by n bits
        byte[] result = new byte[bs.length];
        for (int i = 0; i < totalBits; i++) {
            int srcBit = (int) ((i + n) % totalBits);
            if (getBit(bs, srcBit)) {
                setBit(result, i);
            }
        }
        return mkByteString(result);
    }

    public static CekValue countSetBits(List<CekValue> args) {
        var bs = asByteString(args.get(0), "CountSetBits");
        int count = 0;
        for (byte b : bs) {
            count += Integer.bitCount(Byte.toUnsignedInt(b));
        }
        return mkInteger(count);
    }

    public static CekValue findFirstSetBit(List<CekValue> args) {
        var bs = asByteString(args.get(0), "FindFirstSetBit");
        // Search from LSB (last byte, bit 0)
        for (int byteIdx = bs.length - 1; byteIdx >= 0; byteIdx--) {
            if (bs[byteIdx] != 0) {
                int bitInByte = Integer.numberOfTrailingZeros(Byte.toUnsignedInt(bs[byteIdx]));
                int absoluteBit = (bs.length - 1 - byteIdx) * 8 + bitInByte;
                return mkInteger(absoluteBit);
            }
        }
        return mkInteger(-1); // No bits set
    }

    // === Helpers ===

    @FunctionalInterface
    private interface ByteOp {
        byte apply(byte a, byte b);
    }

    /**
     * Perform a bitwise operation on two byte strings.
     *
     * @param a         first operand
     * @param b         second operand
     * @param padding   if true, pad shorter to max length; if false, truncate to min length
     * @param padByte   the identity byte for this operation (used when padding)
     * @param op        the bitwise operation
     */
    private static byte[] bitwiseOp(byte[] a, byte[] b, boolean padding, byte padByte, ByteOp op) {
        int resultLen = padding ? Math.max(a.length, b.length) : Math.min(a.length, b.length);
        byte[] result = new byte[resultLen];

        // Operate from the left (MSB) end — Plutus uses big-endian byte layout
        for (int i = 0; i < resultLen; i++) {
            byte aVal = i < a.length ? a[i] : padByte;
            byte bVal = i < b.length ? b[i] : padByte;
            result[i] = op.apply(aVal, bVal);
        }
        return result;
    }

    private static void reverse(byte[] arr) {
        for (int i = 0, j = arr.length - 1; i < j; i++, j--) {
            byte tmp = arr[i];
            arr[i] = arr[j];
            arr[j] = tmp;
        }
    }

    private static boolean getBit(byte[] bs, int bitIdx) {
        // Bit 0 = MSB of first byte in big-endian layout
        int byteIdx = bitIdx / 8;
        int bitOffset = 7 - (bitIdx % 8);
        return ((bs[byteIdx] >> bitOffset) & 1) == 1;
    }

    private static void setBit(byte[] bs, int bitIdx) {
        int byteIdx = bitIdx / 8;
        int bitOffset = 7 - (bitIdx % 8);
        bs[byteIdx] |= (byte)(1 << bitOffset);
    }

    private static void shiftLeft(byte[] src, byte[] dst, int n) {
        int byteShift = n / 8;
        int bitShift = n % 8;

        for (int i = 0; i < dst.length; i++) {
            int srcIdx = i + byteShift;
            int hi = (srcIdx < src.length) ? Byte.toUnsignedInt(src[srcIdx]) : 0;
            int lo = (srcIdx + 1 < src.length) ? Byte.toUnsignedInt(src[srcIdx + 1]) : 0;
            dst[i] = (byte)((hi << bitShift | lo >>> (8 - bitShift)) & 0xff);
        }
        if (bitShift == 0) {
            // When bitShift is 0, the lo >>> 8 gives wrong result, redo simply
            Arrays.fill(dst, 0, dst.length, (byte) 0);
            for (int i = 0; i < dst.length; i++) {
                int srcIdx = i + byteShift;
                if (srcIdx < src.length) {
                    dst[i] = src[srcIdx];
                }
            }
        }
    }

    private static void shiftRight(byte[] src, byte[] dst, int n) {
        int byteShift = n / 8;
        int bitShift = n % 8;

        for (int i = dst.length - 1; i >= 0; i--) {
            int srcIdx = i - byteShift;
            int lo = (srcIdx >= 0) ? Byte.toUnsignedInt(src[srcIdx]) : 0;
            int hi = (srcIdx - 1 >= 0) ? Byte.toUnsignedInt(src[srcIdx - 1]) : 0;
            dst[i] = (byte)((lo >>> bitShift | hi << (8 - bitShift)) & 0xff);
        }
        if (bitShift == 0) {
            Arrays.fill(dst, 0, dst.length, (byte) 0);
            for (int i = dst.length - 1; i >= 0; i--) {
                int srcIdx = i - byteShift;
                if (srcIdx >= 0) {
                    dst[i] = src[srcIdx];
                }
            }
        }
    }
}
