package com.bloxbean.cardano.julc.vm.java.builtins;

import com.bloxbean.cardano.julc.vm.java.CekValue;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;

import static com.bloxbean.cardano.julc.vm.java.builtins.BuiltinHelper.*;

/**
 * ByteString operation builtins.
 */
public final class ByteStringBuiltins {

    private ByteStringBuiltins() {}

    public static CekValue appendByteString(List<CekValue> args) {
        var a = asByteString(args.get(0), "AppendByteString");
        var b = asByteString(args.get(1), "AppendByteString");
        var result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return mkByteString(result);
    }

    public static CekValue consByteString(List<CekValue> args) {
        var n = asInteger(args.get(0), "ConsByteString");
        var bs = asByteString(args.get(1), "ConsByteString");
        int val = toWord8(n, "ConsByteString");
        var result = new byte[bs.length + 1];
        result[0] = (byte) val;
        System.arraycopy(bs, 0, result, 1, bs.length);
        return mkByteString(result);
    }

    public static CekValue sliceByteString(List<CekValue> args) {
        long start = toInt64(asInteger(args.get(0), "SliceByteString"), "SliceByteString");
        long len = toInt64(asInteger(args.get(1), "SliceByteString"), "SliceByteString");
        var bs = asByteString(args.get(2), "SliceByteString");

        // ByteString.take/drop clamp negative and oversized Int arguments. Do
        // all comparisons before narrowing to Java array indices.
        long actualStart = Math.max(0L, start);
        if (len <= 0 || actualStart >= bs.length) {
            return mkByteString(new byte[0]);
        }
        int from = (int) actualStart;
        int copyLength = (int) Math.min(len, (long) bs.length - actualStart);
        return mkByteString(Arrays.copyOfRange(bs, from, from + copyLength));
    }

    public static CekValue lengthOfByteString(List<CekValue> args) {
        var bs = asByteString(args.get(0), "LengthOfByteString");
        return mkInteger(bs.length);
    }

    public static CekValue indexByteString(List<CekValue> args) {
        var bs = asByteString(args.get(0), "IndexByteString");
        var idxBig = asInteger(args.get(1), "IndexByteString");
        // Check for overflow before converting to int
        if (idxBig.signum() < 0 || idxBig.bitLength() > 31 || idxBig.intValue() >= bs.length) {
            throw new BuiltinException("IndexByteString: index " + idxBig + " out of bounds for length " + bs.length);
        }
        int idx = idxBig.intValue();
        return mkInteger(Byte.toUnsignedInt(bs[idx]));
    }

    public static CekValue equalsByteString(List<CekValue> args) {
        var a = asByteString(args.get(0), "EqualsByteString");
        var b = asByteString(args.get(1), "EqualsByteString");
        return mkBool(Arrays.equals(a, b));
    }

    public static CekValue lessThanByteString(List<CekValue> args) {
        var a = asByteString(args.get(0), "LessThanByteString");
        var b = asByteString(args.get(1), "LessThanByteString");
        return mkBool(compareByteStrings(a, b) < 0);
    }

    public static CekValue lessThanEqualsByteString(List<CekValue> args) {
        var a = asByteString(args.get(0), "LessThanEqualsByteString");
        var b = asByteString(args.get(1), "LessThanEqualsByteString");
        return mkBool(compareByteStrings(a, b) <= 0);
    }

    /**
     * Lexicographic comparison of byte strings (unsigned bytes).
     */
    private static int compareByteStrings(byte[] a, byte[] b) {
        int minLen = Math.min(a.length, b.length);
        for (int i = 0; i < minLen; i++) {
            int cmp = Integer.compare(Byte.toUnsignedInt(a[i]), Byte.toUnsignedInt(b[i]));
            if (cmp != 0) return cmp;
        }
        return Integer.compare(a.length, b.length);
    }
}
