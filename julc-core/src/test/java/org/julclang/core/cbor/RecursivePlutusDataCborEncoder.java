package org.julclang.core.cbor;

import org.julclang.core.PlutusData;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;

/**
 * The recursive byte encoder of {@link PlutusDataCborEncoder} as it was before it moved to an explicit stack
 * (ADR-077 M2), kept unchanged as the reference for {@code PlutusDataCborEncoderDifferentialTest}.
 */
final class RecursivePlutusDataCborEncoder {

    private static final int MAX_BYTESTRING_CHUNK = 64;

    private RecursivePlutusDataCborEncoder() {}

    static byte[] encode(PlutusData data) {
        var out = new ByteArrayOutputStream();
        writeData(out, data);
        return out.toByteArray();
    }

    // --- Byte encoding (plutus-core encodeData) ---

    private static void writeData(ByteArrayOutputStream out, PlutusData data) {
        switch (data) {
            case PlutusData.ConstrData cd -> writeConstrData(out, cd);
            case PlutusData.MapData md -> writeMapData(out, md);
            case PlutusData.ListData ld -> writeDataArray(out, ld.items());
            case PlutusData.IntData id -> writeDataInteger(out, id.value());
            case PlutusData.BytesData bd -> writeBoundedBytes(out, bd.value());
        }
    }

    private static void writeConstrData(ByteArrayOutputStream out, PlutusData.ConstrData cd) {
        BigInteger tag = cd.constructorTag();
        if (isCompactTag0To6(tag)) {
            writeTag(out, 121 + tag.longValueExact());
        } else if (isCompactTag7To127(tag)) {
            writeTag(out, 1280 + (tag.longValueExact() - 7));
        } else {
            writeTag(out, 102);
            writeMajorArg(out, 4, 2); // outer definite array of 2: [tag, fields]
            // plutus writes this tag with cborg's encodeInteger (Data.hs:152-160), not the Data one
            writeCborgInteger(out, tag);
        }
        writeDataArray(out, cd.fields());
    }

    /**
     * Write a Plutus Data array (constructor fields or list items): serialise's {@code defaultEncodeList},
     * an indefinite array ({@code 0x9f ... 0xff}) when non-empty and a definite empty array ({@code 0x80}).
     */
    private static void writeDataArray(ByteArrayOutputStream out, List<PlutusData> items) {
        if (items.isEmpty()) {
            writeMajorArg(out, 4, 0);
        } else {
            out.write(0x9f);
            for (var item : items) {
                writeData(out, item);
            }
            out.write(0xff);
        }
    }

    /**
     * A definite map in entry order. Canonical Plutus Data keeps entry order and duplicate keys (the
     * on-chain serialiseData folds the entry list as-is); sorting and deduplication are a
     * transaction-body concern, not this encoder's.
     */
    private static void writeMapData(ByteArrayOutputStream out, PlutusData.MapData md) {
        writeMajorArg(out, 5, md.entries().size());
        for (var entry : md.entries()) {
            writeData(out, entry.key());
            writeData(out, entry.value());
        }
    }

    /**
     * plutus-core {@code encodeInteger} (Data.hs:170-183): outside the 64-bit range, a tag 2/3 bignum
     * whose bytes go through {@code encodeBs}, so over 64 bytes they are chunked.
     */
    private static void writeDataInteger(ByteArrayOutputStream out, BigInteger value) {
        BigInteger arg = cborArgument(value);
        if (arg.bitLength() <= 64) {
            writeCborgInteger(out, value);
        } else {
            writeTag(out, value.signum() >= 0 ? 2 : 3);
            writeBoundedBytes(out, unsignedBytes(arg));
        }
    }

    /**
     * cborg {@code encodeInteger}: a CBOR integer, or a tag 2/3 bignum whose bytes are one definite
     * byte string.
     */
    private static void writeCborgInteger(ByteArrayOutputStream out, BigInteger value) {
        int major = value.signum() >= 0 ? 0 : 1;
        BigInteger arg = cborArgument(value);
        if (arg.bitLength() <= 64) {
            writeUnsignedWord64(out, major, arg.longValue());
        } else {
            writeTag(out, major == 0 ? 2 : 3);
            byte[] bytes = unsignedBytes(arg);
            writeMajorArg(out, 2, bytes.length);
            out.write(bytes, 0, bytes.length);
        }
    }

    /**
     * plutus-core {@code encodeBs}: a byte string over 64 bytes is an indefinite string of 64-byte
     * chunks.
     */
    private static void writeBoundedBytes(ByteArrayOutputStream out, byte[] bytes) {
        if (bytes.length <= MAX_BYTESTRING_CHUNK) {
            writeMajorArg(out, 2, bytes.length);
            out.write(bytes, 0, bytes.length);
        } else {
            out.write(0x5f);
            for (int offset = 0; offset < bytes.length; offset += MAX_BYTESTRING_CHUNK) {
                int chunkLen = Math.min(MAX_BYTESTRING_CHUNK, bytes.length - offset);
                writeMajorArg(out, 2, chunkLen);
                out.write(bytes, offset, chunkLen);
            }
            out.write(0xff);
        }
    }

    private static void writeTag(ByteArrayOutputStream out, long tag) {
        writeMajorArg(out, 6, tag);
    }

    /** A CBOR head whose argument is an unsigned Word64 held in a signed long. */
    private static void writeUnsignedWord64(ByteArrayOutputStream out, int major, long value) {
        if (value >= 0) {
            writeMajorArg(out, major, value);
            return;
        }
        // Negative signed longs represent unsigned values 2^63..2^64-1.
        out.write((major << 5) | 27);
        for (int i = 56; i >= 0; i -= 8) {
            out.write((int) ((value >>> i) & 0xff));
        }
    }

    /** A CBOR head with the shortest argument encoding, for a non-negative {@code arg}. */
    private static void writeMajorArg(ByteArrayOutputStream out, int major, long arg) {
        int majorBits = major << 5;
        if (arg < 24) {
            out.write(majorBits | (int) arg);
        } else if (arg < 0x100) {
            out.write(majorBits | 24);
            out.write((int) arg);
        } else if (arg < 0x10000) {
            out.write(majorBits | 25);
            out.write((int) (arg >> 8));
            out.write((int) (arg & 0xff));
        } else if (arg < 0x100000000L) {
            out.write(majorBits | 26);
            out.write((int) (arg >> 24));
            out.write((int) ((arg >> 16) & 0xff));
            out.write((int) ((arg >> 8) & 0xff));
            out.write((int) (arg & 0xff));
        } else {
            out.write(majorBits | 27);
            for (int i = 56; i >= 0; i -= 8) {
                out.write((int) ((arg >> i) & 0xff));
            }
        }
    }

    private static boolean isCompactTag0To6(BigInteger tag) {
        return tag.signum() >= 0 && tag.compareTo(BigInteger.valueOf(6)) <= 0;
    }

    private static boolean isCompactTag7To127(BigInteger tag) {
        return tag.compareTo(BigInteger.valueOf(7)) >= 0 && tag.compareTo(BigInteger.valueOf(127)) <= 0;
    }

    /** The CBOR argument of an integer: the value itself, or {@code -1 - value} when negative. */
    private static BigInteger cborArgument(BigInteger value) {
        return value.signum() >= 0 ? value : value.negate().subtract(BigInteger.ONE);
    }

    /** Big-endian bytes of a non-negative value, without a leading sign byte. */
    private static byte[] unsignedBytes(BigInteger value) {
        byte[] bytes = value.toByteArray();
        return bytes.length > 1 && bytes[0] == 0 ? Arrays.copyOfRange(bytes, 1, bytes.length) : bytes;
    }
}
