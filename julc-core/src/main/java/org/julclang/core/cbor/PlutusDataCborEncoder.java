package org.julclang.core.cbor;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.Map;
import co.nstant.in.cbor.model.NegativeInteger;
import co.nstant.in.cbor.model.Special;
import co.nstant.in.cbor.model.UnsignedInteger;
import org.julclang.core.PlutusData;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Encodes {@link PlutusData} to CBOR bytes exactly as plutus-core's {@code encodeData} does
 * (plutus 1.65.0.0, {@code plutus-core/plutus-core/src/PlutusCore/Data.hs:146-198}).
 * <p>
 * {@link #encode(PlutusData)} is Julc's single Plutus Data serialisation: the on-chain
 * {@code serialiseData} builtin, FLAT Data constants and the off-chain {@code Builtins.serialiseData}
 * all use it.
 * <ul>
 *   <li>Constructor tags 0-6: CBOR tag 121+tag; tags 7-127: CBOR tag 1280+(tag-7); both followed by the
 *       fields array.</li>
 *   <li>Any other constructor tag, negative ones included: CBOR tag 102, then a definite array
 *       {@code [tag, fields]}. The tag is written by cborg's {@code encodeInteger}: a CBOR integer, or a
 *       tag 2/3 bignum with one definite byte string. It is never chunked.</li>
 *   <li>Non-empty lists and constructor fields are indefinite arrays; empty ones are definite
 *       ({@code 0x80}).</li>
 *   <li>Maps are definite and keep entry order and duplicate keys.</li>
 *   <li>Integers outside the 64-bit CBOR range are tag 2/3 bignums whose bytes, like byte strings,
 *       go through plutus {@code encodeBs}: over 64 bytes they are an indefinite string of 64-byte
 *       chunks.</li>
 * </ul>
 */
public final class PlutusDataCborEncoder {

    private static final int MAX_BYTESTRING_CHUNK = 64;

    private PlutusDataCborEncoder() {}

    /**
     * Encode PlutusData to CBOR bytes, byte for byte as plutus-core's {@code serialiseData}.
     */
    public static byte[] encode(PlutusData data) {
        var out = new ByteArrayOutputStream();
        writeData(out, data);
        return out.toByteArray();
    }

    /**
     * Convert PlutusData to a cbor-java DataItem tree, for interop with libraries that work with
     * cbor-java DataItems.
     *
     * <p>This tree is not the authoritative Plutus serialisation; {@link #encode(PlutusData)} is.
     * Serialising the tree with a standard cbor-java encoder does not give the same bytes in general:
     * cbor-java's map model may sort entries and collapse duplicate keys, and it cannot write a byte
     * string or bignum over 64 bytes as 64-byte chunks.
     */
    public static DataItem toDataItem(PlutusData data) {
        return switch (data) {
            case PlutusData.ConstrData c -> constrToDataItem(c);
            case PlutusData.MapData m -> mapToDataItem(m);
            case PlutusData.ListData l -> dataArray(l.items());
            case PlutusData.IntData i -> intToDataItem(i.value(), true);
            case PlutusData.BytesData b -> chunkByteString(b.value());
        };
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

    // --- DataItem tree (interop) ---

    private static DataItem constrToDataItem(PlutusData.ConstrData c) {
        BigInteger tag = c.constructorTag();
        Array fieldsArray = dataArray(c.fields());
        if (isCompactTag0To6(tag)) {
            fieldsArray.setTag(121 + tag.longValueExact());
            return fieldsArray;
        } else if (isCompactTag7To127(tag)) {
            fieldsArray.setTag(1280 + (tag.longValueExact() - 7));
            return fieldsArray;
        } else {
            // General form: tag 102, [constructor_tag, fields_array]; the tag is a cborg integer, never chunked
            Array outer = new Array();
            outer.add(intToDataItem(tag, false));
            outer.add(fieldsArray);
            outer.setTag(102);
            return outer;
        }
    }

    private static Array dataArray(List<PlutusData> items) {
        Array array = new Array();
        for (var item : items) {
            array.add(toDataItem(item));
        }
        if (!items.isEmpty()) {
            array.setChunked(true);
            // cbor-java models the closing break as an explicit array item, which keeps the tree
            // serialisable by a standard CborEncoder.
            array.add(Special.BREAK);
        }
        return array;
    }

    private static DataItem mapToDataItem(PlutusData.MapData m) {
        // cbor-java's Map reorders and drops duplicate keys, so use an order-preserving map DataItem.
        var keys = new ArrayList<DataItem>(m.entries().size());
        var values = new ArrayList<DataItem>(m.entries().size());
        for (var entry : m.entries()) {
            keys.add(toDataItem(entry.key()));
            values.add(toDataItem(entry.value()));
        }
        return new OrderedMap(keys, values);
    }

    /** An integer item; a bignum's byte string is chunked as Data integers are, or definite as cborg's. */
    private static DataItem intToDataItem(BigInteger value, boolean chunkBignum) {
        BigInteger arg = cborArgument(value);
        if (arg.bitLength() <= 64) {
            return value.signum() >= 0 ? new UnsignedInteger(value) : new NegativeInteger(value);
        }
        byte[] bytes = unsignedBytes(arg);
        DataItem bs = chunkBignum ? chunkByteString(bytes) : new ByteString(bytes);
        bs.setTag(value.signum() >= 0 ? 2 : 3);
        return bs;
    }

    /** A ByteString item, marked chunked when plutus writes it as 64-byte chunks. */
    private static DataItem chunkByteString(byte[] value) {
        ByteString bs = new ByteString(value);
        bs.setChunked(value.length > MAX_BYTESTRING_CHUNK);
        return bs;
    }

    /**
     * A CBOR map with two representations: the raw entry sequence used by Julc to preserve
     * order and duplicate keys, and the inherited cbor-java {@link Map} view used for interop.
     * The latter necessarily follows cbor-java semantics and therefore deduplicates equal keys.
     * {@link PlutusDataCborDecoder} reads the raw entries.
     */
    static final class OrderedMap extends Map {
        private final List<DataItem> orderedKeys;
        private final List<DataItem> orderedValues;

        OrderedMap(List<DataItem> keys, List<DataItem> values) {
            super(keys.size());
            if (keys.size() != values.size()) {
                throw new IllegalArgumentException("Map keys and values must have the same size");
            }

            this.orderedKeys = List.copyOf(keys);
            this.orderedValues = List.copyOf(values);

            // Populate the ordinary cbor-java Map view so its standard CborEncoder can
            // serialize this object without casting failures. Do not expose orderedKeys
            // through getKeys(): its canonical encoder deduplicates serialized keys, which
            // could otherwise make the encoded entry count disagree with the map header.
            for (int i = 0; i < orderedKeys.size(); i++) {
                super.put(orderedKeys.get(i), orderedValues.get(i));
            }
        }

        List<DataItem> orderedKeys() {
            return orderedKeys;
        }

        List<DataItem> orderedValues() {
            return orderedValues;
        }
    }
}
