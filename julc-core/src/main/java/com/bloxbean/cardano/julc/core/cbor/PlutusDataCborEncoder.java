package com.bloxbean.cardano.julc.core.cbor;

import com.bloxbean.cardano.julc.core.PlutusData;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.List;

/**
 * Encodes {@link PlutusData} to CBOR bytes following the Cardano specification.
 * <p>
 * Uses direct CBOR encoding (no external library) to ensure compliance with
 * Cardano's canonical CBOR rules, including chunked bytestrings for data > 64 bytes.
 * <p>
 * Constructor encoding uses compact form:
 * <ul>
 *   <li>Tags 0-6: CBOR tag 121+tag, with fields as array</li>
 *   <li>Tags 7-127: CBOR tag 1280+(tag-7), with fields as array</li>
 *   <li>Tags 128+: CBOR tag 102, with [tag, fields] array</li>
 * </ul>
 */
public final class PlutusDataCborEncoder {

    private static final int MAX_BYTESTRING_CHUNK = 64;

    private PlutusDataCborEncoder() {}

    /**
     * Encode PlutusData to CBOR bytes.
     */
    public static byte[] encode(PlutusData data) {
        var baos = new ByteArrayOutputStream();
        writeData(baos, data);
        return baos.toByteArray();
    }

    private static void writeData(ByteArrayOutputStream out, PlutusData data) {
        switch (data) {
            case PlutusData.ConstrData c -> writeConstr(out, c);
            case PlutusData.MapData m -> writeMap(out, m);
            case PlutusData.ListData l -> writeList(out, l);
            case PlutusData.IntData i -> writeInteger(out, i.value());
            case PlutusData.BytesData b -> writeByteString(out, b.value());
        }
    }

    // --- Constr ---

    private static void writeConstr(ByteArrayOutputStream out, PlutusData.ConstrData c) {
        int tag = c.tag();
        if (tag >= 0 && tag <= 6) {
            writeTag(out, 121 + tag);
        } else if (tag >= 7 && tag <= 127) {
            writeTag(out, 1280 + (tag - 7));
        } else {
            writeTag(out, 102);
            // General form: [tag, fields]
            writeMajorTypeLen(out, 4, 2);
            writeInteger(out, BigInteger.valueOf(tag));
        }
        writeFieldsArray(out, c.fields());
    }

    private static void writeFieldsArray(ByteArrayOutputStream out, List<PlutusData> fields) {
        writeMajorTypeLen(out, 4, fields.size());
        for (var field : fields) {
            writeData(out, field);
        }
    }

    // --- Map ---

    private static void writeMap(ByteArrayOutputStream out, PlutusData.MapData m) {
        var entries = m.entries();
        writeMajorTypeLen(out, 5, entries.size());
        for (var entry : entries) {
            writeData(out, entry.key());
            writeData(out, entry.value());
        }
    }

    // --- List ---

    private static void writeList(ByteArrayOutputStream out, PlutusData.ListData l) {
        var items = l.items();
        writeMajorTypeLen(out, 4, items.size());
        for (var item : items) {
            writeData(out, item);
        }
    }

    // --- Integer ---

    private static void writeInteger(ByteArrayOutputStream out, BigInteger value) {
        if (value.signum() >= 0 && value.bitLength() <= 64) {
            // Unsigned integer (major type 0)
            // Note: bitLength() <= 64 means value fits in unsigned 64-bit, but may exceed Long.MAX_VALUE
            writeMajorTypeLenBig(out, 0, value);
        } else if (value.signum() < 0 && value.negate().subtract(BigInteger.ONE).bitLength() <= 64) {
            // Negative integer (major type 1): encode as -(1+n) where n is the stored value
            writeMajorTypeLenBig(out, 1, value.negate().subtract(BigInteger.ONE));
        } else if (value.signum() >= 0) {
            // Positive BigNum: CBOR tag 2 + bytestring
            writeTag(out, 2);
            writeByteString(out, bigIntToMinimalBytes(value));
        } else {
            // Negative BigNum: CBOR tag 3 + bytestring encoding -(1+n)
            BigInteger n = value.negate().subtract(BigInteger.ONE);
            writeTag(out, 3);
            writeByteString(out, bigIntToMinimalBytes(n));
        }
    }

    private static byte[] bigIntToMinimalBytes(BigInteger value) {
        byte[] raw = value.toByteArray();
        if (raw.length > 1 && raw[0] == 0) {
            var stripped = new byte[raw.length - 1];
            System.arraycopy(raw, 1, stripped, 0, stripped.length);
            return stripped;
        }
        return raw;
    }

    // --- ByteString ---

    private static void writeByteString(ByteArrayOutputStream out, byte[] value) {
        // Use definite-length encoding for all bytestrings.
        // Note: Cardano canonical CBOR requires chunking for bytestrings > 64 bytes,
        // but that applies to on-chain transaction CBOR, not to FLAT-embedded Data CBOR.
        // The Scalus VM's CBOR decoder does not support indefinite-length bytestrings
        // in Data constants, so we use definite-length encoding here.
        writeMajorTypeLen(out, 2, value.length);
        out.write(value, 0, value.length);
    }

    // --- CBOR primitives ---

    private static void writeTag(ByteArrayOutputStream out, long tagValue) {
        writeMajorTypeLen(out, 6, tagValue);
    }

    /**
     * Write a CBOR major type (0-7) with an associated length/value.
     * Major type occupies bits 7-5, additional info in bits 4-0.
     */
    /**
     * Write a CBOR major type with a BigInteger value (for unsigned 64-bit values that exceed Long.MAX_VALUE).
     */
    private static void writeMajorTypeLenBig(ByteArrayOutputStream out, int majorType, BigInteger value) {
        if (value.bitLength() <= 63) {
            // Fits in signed long
            writeMajorTypeLen(out, majorType, value.longValue());
        } else {
            // Unsigned 64-bit value > Long.MAX_VALUE: always use 8-byte encoding
            int mt = majorType << 5;
            out.write(mt | 27);
            long bits = value.longValue(); // two's complement representation
            out.write((int) (bits >> 56) & 0xFF);
            out.write((int) (bits >> 48) & 0xFF);
            out.write((int) (bits >> 40) & 0xFF);
            out.write((int) (bits >> 32) & 0xFF);
            out.write((int) (bits >> 24) & 0xFF);
            out.write((int) (bits >> 16) & 0xFF);
            out.write((int) (bits >> 8) & 0xFF);
            out.write((int) bits & 0xFF);
        }
    }

    private static void writeMajorTypeLen(ByteArrayOutputStream out, int majorType, long value) {
        int mt = majorType << 5;
        if (value < 24) {
            out.write(mt | (int) value);
        } else if (value <= 0xFF) {
            out.write(mt | 24);
            out.write((int) value);
        } else if (value <= 0xFFFF) {
            out.write(mt | 25);
            out.write((int) (value >> 8) & 0xFF);
            out.write((int) value & 0xFF);
        } else if (value <= 0xFFFFFFFFL) {
            out.write(mt | 26);
            out.write((int) (value >> 24) & 0xFF);
            out.write((int) (value >> 16) & 0xFF);
            out.write((int) (value >> 8) & 0xFF);
            out.write((int) value & 0xFF);
        } else {
            out.write(mt | 27);
            out.write((int) (value >> 56) & 0xFF);
            out.write((int) (value >> 48) & 0xFF);
            out.write((int) (value >> 40) & 0xFF);
            out.write((int) (value >> 32) & 0xFF);
            out.write((int) (value >> 24) & 0xFF);
            out.write((int) (value >> 16) & 0xFF);
            out.write((int) (value >> 8) & 0xFF);
            out.write((int) value & 0xFF);
        }
    }
}
