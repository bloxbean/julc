package com.bloxbean.cardano.julc.core.cbor;

import co.nstant.in.cbor.CborEncoder;
import co.nstant.in.cbor.CborException;
import co.nstant.in.cbor.encoder.ByteStringEncoder;
import co.nstant.in.cbor.model.*;
import co.nstant.in.cbor.model.Map;
import com.bloxbean.cardano.julc.core.PlutusData;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.*;

/**
 * Encodes {@link PlutusData} to CBOR bytes following the Cardano specification.
 * <p>
 * Uses cbor-java's high-level API with canonical encoding (RFC 7049 §3.9)
 * and chunked byte strings for data &gt; 64 bytes (CDDL: bounded_bytes).
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
        DataItem dataItem = toDataItem(data);
        return serializeDataItem(dataItem);
    }

    /**
     * Convert PlutusData to a cbor-java DataItem tree.
     * Useful for interop with libraries that work with cbor-java DataItems.
     */
    public static DataItem toDataItem(PlutusData data) {
        return switch (data) {
            case PlutusData.ConstrData c -> constrToDataItem(c);
            case PlutusData.MapData m -> mapToDataItem(m);
            case PlutusData.ListData l -> listToDataItem(l);
            case PlutusData.IntData i -> intToDataItem(i.value());
            case PlutusData.BytesData b -> bytesToDataItem(b.value());
        };
    }

    // --- Constr ---

    private static DataItem constrToDataItem(PlutusData.ConstrData c) {
        int tag = c.tag();
        Array fieldsArray = fieldsToArray(c.fields());

        if (tag >= 0 && tag <= 6) {
            fieldsArray.setTag(121 + tag);
            return fieldsArray;
        } else if (tag >= 7 && tag <= 127) {
            fieldsArray.setTag(1280 + (tag - 7));
            return fieldsArray;
        } else {
            // General form: tag 102, [constructor_tag, fields_array]
            Array outer = new Array();
            outer.add(new UnsignedInteger(tag));
            outer.add(fieldsArray);
            outer.setTag(102);
            return outer;
        }
    }

    private static Array fieldsToArray(List<PlutusData> fields) {
        Array array = new Array();
        for (var field : fields) {
            array.add(toDataItem(field));
        }
        // Canonical Plutus Data encodes non-empty constructor fields as an
        // indefinite-length array (0x9f ... 0xff); empty stays definite (0x80).
        if (!fields.isEmpty()) {
            array.setChunked(true);
            // cbor-java models the closing break as an explicit array item.
            array.add(Special.BREAK);
        }
        return array;
    }

    // --- Map (order- and duplicate-preserving) ---

    private static DataItem mapToDataItem(PlutusData.MapData m) {
        // Canonical Plutus Data preserves map entry order and duplicate keys (the on-chain
        // serialiseData folds the entry list as-is; the ledger memoizes Plutus-data bytes and
        // does not require canonical form). Sorting/deduplication is a transaction-body concern
        // handled by cardano-client-lib, NOT the Plutus-data encoder. cbor-java's Map reorders
        // and drops duplicate keys, so use an order-preserving map DataItem instead.
        var keys = new ArrayList<DataItem>(m.entries().size());
        var values = new ArrayList<DataItem>(m.entries().size());
        for (var entry : m.entries()) {
            keys.add(toDataItem(entry.key()));
            values.add(toDataItem(entry.value()));
        }
        return new OrderedMap(keys, values);
    }

    // --- List ---

    private static DataItem listToDataItem(PlutusData.ListData l) {
        Array array = new Array();
        for (var item : l.items()) {
            array.add(toDataItem(item));
        }
        // Canonical Plutus Data encodes a non-empty list as an indefinite-length
        // array (0x9f ... 0xff); an empty list stays definite (0x80).
        if (!l.items().isEmpty()) {
            array.setChunked(true);
            // Keep the public DataItem tree serializable by a standard CborEncoder.
            array.add(Special.BREAK);
        }
        return array;
    }

    // --- Integer ---

    private static DataItem intToDataItem(BigInteger value) {
        if (value.signum() >= 0 && value.bitLength() <= 64) {
            return new UnsignedInteger(value);
        } else if (value.signum() < 0 && value.negate().subtract(BigInteger.ONE).bitLength() <= 64) {
            return new NegativeInteger(value);
        } else if (value.signum() >= 0) {
            // Positive BigNum: tag 2 + byte string
            byte[] bytes = bigIntToMinimalBytes(value);
            DataItem bs = chunkByteString(bytes);
            bs.setTag(2);
            return bs;
        } else {
            // Negative BigNum: tag 3 + byte string encoding -(1+n)
            BigInteger n = value.negate().subtract(BigInteger.ONE);
            byte[] bytes = bigIntToMinimalBytes(n);
            DataItem bs = chunkByteString(bytes);
            bs.setTag(3);
            return bs;
        }
    }

    private static byte[] bigIntToMinimalBytes(BigInteger value) {
        if (value.signum() == 0) {
            return new byte[0];
        }
        byte[] raw = value.toByteArray();
        if (raw.length > 1 && raw[0] == 0) {
            var stripped = new byte[raw.length - 1];
            System.arraycopy(raw, 1, stripped, 0, stripped.length);
            return stripped;
        }
        return raw;
    }

    // --- ByteString ---

    private static DataItem bytesToDataItem(byte[] value) {
        return chunkByteString(value);
    }

    /**
     * Create a ByteString DataItem, chunking into 64-byte segments if needed.
     */
    private static DataItem chunkByteString(byte[] value) {
        if (value.length <= MAX_BYTESTRING_CHUNK) {
            return new ByteString(value);
        }
        List<byte[]> chunks = new ArrayList<>();
        int offset = 0;
        while (offset < value.length) {
            int len = Math.min(value.length - offset, MAX_BYTESTRING_CHUNK);
            byte[] chunk = new byte[len];
            System.arraycopy(value, offset, chunk, 0, len);
            chunks.add(chunk);
            offset += len;
        }
        return new ChunkedByteString(chunks);
    }

    // --- Serialization ---

    static byte[] serializeDataItem(DataItem dataItem) {
        try {
            var baos = new ByteArrayOutputStream();
            new PlutusDataCborCborEncoder(baos).encode(dataItem);
            return baos.toByteArray();
        } catch (CborException e) {
            throw new CborDecodingException("CBOR encoding failed", e);
        }
    }

    // --- Inner classes ---

    /**
     * A CBOR map DataItem that preserves entry order and duplicate keys, unlike cbor-java's
     * {@link Map} (which reorders and deduplicates via {@code DataItem.hashCode/equals}).
     * Encoded as a definite-length map (major type 5) by {@link PlutusDataCborCborEncoder}.
     */
    static final class OrderedMap extends DataItem {
        private final List<DataItem> keys;
        private final List<DataItem> values;

        OrderedMap(List<DataItem> keys, List<DataItem> values) {
            super(MajorType.MAP);
            this.keys = keys;
            this.values = values;
        }

        List<DataItem> keys() {
            return keys;
        }

        List<DataItem> values() {
            return values;
        }
    }

    /**
     * A ByteString that has been split into chunks for indefinite-length encoding.
     */
    static final class ChunkedByteString extends ByteString {
        private final List<byte[]> chunks;

        ChunkedByteString(List<byte[]> chunks) {
            super(new byte[0]);
            this.chunks = chunks;
            setChunked(true);
        }

        List<byte[]> getChunks() {
            return chunks;
        }

        @Override
        public byte[] getBytes() {
            int total = 0;
            for (byte[] chunk : chunks) total += chunk.length;
            byte[] result = new byte[total];
            int offset = 0;
            for (byte[] chunk : chunks) {
                System.arraycopy(chunk, 0, result, offset, chunk.length);
                offset += chunk.length;
            }
            return result;
        }
    }

    /**
     * Custom ByteStringEncoder that encodes ChunkedByteString as indefinite-length
     * CBOR byte strings (0x5F ... chunks ... 0xFF).
     */
    private static final class ChunkedByteStringEncoder extends ByteStringEncoder {

        ChunkedByteStringEncoder(CborEncoder encoder, java.io.OutputStream outputStream) {
            super(encoder, outputStream);
        }

        @Override
        public void encode(ByteString byteString) throws CborException {
            if (byteString instanceof ChunkedByteString chunked) {
                encodeTypeChunked(MajorType.BYTE_STRING);
                for (byte[] chunk : chunked.getChunks()) {
                    super.encode(new ByteString(chunk));
                }
                encoder.encode(SimpleValue.BREAK);
            } else {
                super.encode(byteString);
            }
        }
    }

    /**
     * Custom CborEncoder that dispatches BYTE_STRING encoding to our
     * ChunkedByteStringEncoder while delegating everything else to the parent.
     */
    private static final class PlutusDataCborCborEncoder extends CborEncoder {
        private final ChunkedByteStringEncoder chunkedByteStringEncoder;
        private final java.io.OutputStream out;

        PlutusDataCborCborEncoder(java.io.OutputStream outputStream) {
            super(outputStream);
            this.out = outputStream;
            this.chunkedByteStringEncoder = new ChunkedByteStringEncoder(this, outputStream);
        }

        @Override
        public void encode(DataItem dataItem) throws CborException {
            if (dataItem instanceof OrderedMap om) {
                // Definite-length map (major type 5) preserving entry order and duplicate keys,
                // recursing through this encoder so nested items are handled.
                try {
                    if (om.hasTag()) {
                        encode(om.getTag());
                    }
                    writeTypeAndLength(5, om.keys.size());
                    for (int i = 0; i < om.keys.size(); i++) {
                        encode(om.keys.get(i));
                        encode(om.values.get(i));
                    }
                } catch (java.io.IOException e) {
                    throw new CborException("Failed to encode map", e);
                }
                return;
            }
            if (dataItem != null && dataItem.getMajorType() == MajorType.BYTE_STRING) {
                // Handle tag first, then dispatch to our custom byte string encoder
                if (dataItem.hasTag()) {
                    encode(dataItem.getTag());
                }
                chunkedByteStringEncoder.encode((ByteString) dataItem);
            } else {
                super.encode(dataItem);
            }
        }

        /** Write a CBOR major-type byte and length argument (as {@code encodeTypeAndLength} would). */
        private void writeTypeAndLength(int majorType, long length) throws java.io.IOException {
            int mt = majorType << 5;
            if (length < 24) {
                out.write(mt | (int) length);
            } else if (length < 0x100L) {
                out.write(mt | 24);
                out.write((int) length);
            } else if (length < 0x10000L) {
                out.write(mt | 25);
                out.write((int) (length >> 8));
                out.write((int) (length & 0xff));
            } else if (length < 0x100000000L) {
                out.write(mt | 26);
                out.write((int) (length >> 24));
                out.write((int) ((length >> 16) & 0xff));
                out.write((int) ((length >> 8) & 0xff));
                out.write((int) (length & 0xff));
            } else {
                out.write(mt | 27);
                for (int shift = 56; shift >= 0; shift -= 8) {
                    out.write((int) ((length >> shift) & 0xff));
                }
            }
        }
    }
}
