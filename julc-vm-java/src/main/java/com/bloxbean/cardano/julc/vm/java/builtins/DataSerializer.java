package com.bloxbean.cardano.julc.vm.java.builtins;

import com.bloxbean.cardano.julc.core.PlutusData;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.List;

/**
 * Minimal CBOR serializer for PlutusData (used by SerialiseData builtin).
 * <p>
 * Follows the Cardano CBOR encoding specification for Plutus Data.
 */
public final class DataSerializer {

    private DataSerializer() {}

    public static byte[] serialize(PlutusData data) {
        var out = new ByteArrayOutputStream();
        writeData(out, data);
        return out.toByteArray();
    }

    private static void writeData(ByteArrayOutputStream out, PlutusData data) {
        switch (data) {
            case PlutusData.ConstrData cd -> writeConstrData(out, cd);
            case PlutusData.MapData md -> writeMapData(out, md);
            case PlutusData.ListData ld -> writeListData(out, ld);
            case PlutusData.IntData id -> writeIntData(out, id);
            case PlutusData.BytesData bd -> writeBytesData(out, bd);
        }
    }

    private static void writeConstrData(ByteArrayOutputStream out, PlutusData.ConstrData cd) {
        int tag = cd.tag();
        if (tag >= 0 && tag <= 6) {
            // Compact encoding: tag 121-127
            writeTag(out, 121 + tag);
        } else if (tag >= 7 && tag <= 127) {
            // Compact encoding: tag 1280-1400
            writeTag(out, 1280 + (tag - 7));
        } else {
            // General encoding: tag 102, then [tag, fields]
            writeTag(out, 102);
            writeMajorArg(out, 4, 2); // array of 2
            writeUnsigned(out, tag); // the tag
            writeDefList(out, cd.fields());
            return;
        }
        writeDefList(out, cd.fields());
    }

    private static void writeDefList(ByteArrayOutputStream out, List<PlutusData> items) {
        if (items.isEmpty()) {
            writeMajorArg(out, 4, 0); // empty array
        } else {
            writeMajorArg(out, 4, items.size());
            for (var item : items) {
                writeData(out, item);
            }
        }
    }

    private static void writeMapData(ByteArrayOutputStream out, PlutusData.MapData md) {
        writeMajorArg(out, 5, md.entries().size());
        for (var entry : md.entries()) {
            writeData(out, entry.key());
            writeData(out, entry.value());
        }
    }

    private static void writeListData(ByteArrayOutputStream out, PlutusData.ListData ld) {
        // Use indefinite-length list encoding for non-empty lists
        if (ld.items().isEmpty()) {
            writeMajorArg(out, 4, 0);
        } else {
            out.write(0x9f); // indefinite-length array
            for (var item : ld.items()) {
                writeData(out, item);
            }
            out.write(0xff); // break
        }
    }

    private static void writeIntData(ByteArrayOutputStream out, PlutusData.IntData id) {
        BigInteger value = id.value();
        if (value.signum() >= 0) {
            writeBigUnsigned(out, 0, value);
        } else {
            // CBOR negative: -1 - n
            writeBigUnsigned(out, 1, value.negate().subtract(BigInteger.ONE));
        }
    }

    private static void writeBytesData(ByteArrayOutputStream out, PlutusData.BytesData bd) {
        byte[] bytes = bd.value();
        if (bytes.length <= 64) {
            writeMajorArg(out, 2, bytes.length);
            out.write(bytes, 0, bytes.length);
        } else {
            // Chunked encoding for >64 bytes
            out.write(0x5f); // indefinite-length bytestring
            int offset = 0;
            while (offset < bytes.length) {
                int chunkLen = Math.min(64, bytes.length - offset);
                writeMajorArg(out, 2, chunkLen);
                out.write(bytes, offset, chunkLen);
                offset += chunkLen;
            }
            out.write(0xff); // break
        }
    }

    private static void writeTag(ByteArrayOutputStream out, long tag) {
        writeMajorArg(out, 6, tag);
    }

    private static void writeUnsigned(ByteArrayOutputStream out, long value) {
        writeMajorArg(out, 0, value);
    }

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

    private static void writeBigUnsigned(ByteArrayOutputStream out, int major, BigInteger value) {
        if (value.bitLength() <= 63) {
            writeMajorArg(out, major, value.longValue());
        } else {
            // Big integer: tag 2 (positive) or 3 (negative) + bytestring
            writeTag(out, major == 0 ? 2 : 3);
            byte[] bytes = value.toByteArray();
            // Remove leading zero byte if present
            if (bytes.length > 1 && bytes[0] == 0) {
                byte[] trimmed = new byte[bytes.length - 1];
                System.arraycopy(bytes, 1, trimmed, 0, trimmed.length);
                bytes = trimmed;
            }
            writeMajorArg(out, 2, bytes.length);
            out.write(bytes, 0, bytes.length);
        }
    }
}
