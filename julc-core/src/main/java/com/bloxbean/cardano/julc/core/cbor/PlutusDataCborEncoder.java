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
        return array;
    }

    // --- Map (canonical key sorting) ---

    private static DataItem mapToDataItem(PlutusData.MapData m) {
        var entries = m.entries();
        if (entries.size() <= 1) {
            Map map = new Map();
            for (var entry : entries) {
                map.put(toDataItem(entry.key()), toDataItem(entry.value()));
            }
            return map;
        }

        // Sort entries by their key's CBOR byte representation (RFC 7049 §3.9).
        // We sort ourselves rather than relying on cbor-java's canonical mode because
        // cbor-java's Map uses DataItem.hashCode/equals which can reorder or lose entries.
        record EncodedEntry(byte[] keyBytes, DataItem key, DataItem value) {}
        List<EncodedEntry> sortable = new ArrayList<>(entries.size());
        for (var entry : entries) {
            DataItem keyDi = toDataItem(entry.key());
            DataItem valueDi = toDataItem(entry.value());
            byte[] keyBytes = serializeDataItem(keyDi);
            sortable.add(new EncodedEntry(keyBytes, keyDi, valueDi));
        }
        sortable.sort((a, b) -> {
            if (a.keyBytes.length != b.keyBytes.length) {
                return Integer.compare(a.keyBytes.length, b.keyBytes.length);
            }
            return Arrays.compareUnsigned(a.keyBytes, b.keyBytes);
        });

        Map map = new Map();
        for (var e : sortable) {
            map.put(e.key, e.value);
        }
        return map;
    }

    // --- List ---

    private static DataItem listToDataItem(PlutusData.ListData l) {
        Array array = new Array();
        for (var item : l.items()) {
            array.add(toDataItem(item));
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

    // --- Inner classes for chunked byte string support ---

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

        PlutusDataCborCborEncoder(java.io.OutputStream outputStream) {
            super(outputStream);
            this.chunkedByteStringEncoder = new ChunkedByteStringEncoder(this, outputStream);
        }

        @Override
        public void encode(DataItem dataItem) throws CborException {
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
    }
}
