package com.bloxbean.cardano.plutus.core.cbor;

import co.nstant.in.cbor.CborEncoder;
import co.nstant.in.cbor.CborException;
import co.nstant.in.cbor.model.*;
import com.bloxbean.cardano.plutus.core.PlutusData;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;

/**
 * Encodes {@link PlutusData} to CBOR bytes following the Cardano specification.
 * <p>
 * Constructor encoding uses compact form:
 * <ul>
 *   <li>Tags 0-6: CBOR tag 121+tag, with fields as array</li>
 *   <li>Tags 7-127: CBOR tag 1280+(tag-7), with fields as array</li>
 *   <li>Tags 128+: CBOR tag 102, with [tag, fields] array</li>
 * </ul>
 */
public final class PlutusDataCborEncoder {

    private PlutusDataCborEncoder() {}

    /**
     * Encode PlutusData to CBOR bytes.
     */
    public static byte[] encode(PlutusData data) {
        try {
            var dataItem = toDataItem(data);
            var baos = new ByteArrayOutputStream();
            new CborEncoder(baos).encode(dataItem);
            return baos.toByteArray();
        } catch (CborException e) {
            throw new RuntimeException("CBOR encoding failed", e);
        }
    }

    /**
     * Convert PlutusData to a CBOR DataItem.
     */
    public static DataItem toDataItem(PlutusData data) {
        return switch (data) {
            case PlutusData.Constr c -> encodeConstr(c);
            case PlutusData.Map m -> encodeMap(m);
            case PlutusData.ListData l -> encodeList(l);
            case PlutusData.IntData i -> encodeInteger(i);
            case PlutusData.BytesData b -> new ByteString(b.value());
        };
    }

    private static DataItem encodeConstr(PlutusData.Constr c) {
        int tag = c.tag();
        if (tag >= 0 && tag <= 6) {
            // Compact encoding: CBOR tag 121+tag
            var array = new Array();
            for (var field : c.fields()) {
                array.add(toDataItem(field));
            }
            array.setTag(121 + tag);
            return array;
        } else if (tag >= 7 && tag <= 127) {
            // Extended compact encoding: CBOR tag 1280+(tag-7)
            var array = new Array();
            for (var field : c.fields()) {
                array.add(toDataItem(field));
            }
            array.setTag(1280 + (tag - 7));
            return array;
        } else {
            // General encoding: CBOR tag 102, [tag, fields]
            var inner = new Array();
            inner.add(encodeIntegerValue(BigInteger.valueOf(tag)));
            var fieldsArray = new Array();
            for (var field : c.fields()) {
                fieldsArray.add(toDataItem(field));
            }
            inner.add(fieldsArray);
            inner.setTag(102);
            return inner;
        }
    }

    private static DataItem encodeMap(PlutusData.Map m) {
        var map = new co.nstant.in.cbor.model.Map();
        for (var entry : m.entries()) {
            map.put(toDataItem(entry.key()), toDataItem(entry.value()));
        }
        return map;
    }

    private static DataItem encodeList(PlutusData.ListData l) {
        var array = new Array();
        for (var item : l.items()) {
            array.add(toDataItem(item));
        }
        return array;
    }

    private static DataItem encodeInteger(PlutusData.IntData i) {
        return encodeIntegerValue(i.value());
    }

    private static DataItem encodeIntegerValue(BigInteger value) {
        if (value.signum() >= 0 && value.bitLength() <= 64) {
            return new UnsignedInteger(value);
        } else if (value.signum() < 0 && value.negate().subtract(BigInteger.ONE).bitLength() <= 64) {
            return new NegativeInteger(value);
        } else if (value.signum() >= 0) {
            // Positive BigNum: CBOR tag 2
            byte[] raw = value.toByteArray();
            if (raw.length > 1 && raw[0] == 0) {
                var stripped = new byte[raw.length - 1];
                System.arraycopy(raw, 1, stripped, 0, stripped.length);
                raw = stripped;
            }
            var bs = new ByteString(raw);
            bs.setTag(2);
            return bs;
        } else {
            // Negative BigNum: CBOR tag 3
            // CBOR NegativeBigNum encodes n where value = -(1 + n)
            BigInteger n = value.negate().subtract(BigInteger.ONE);
            byte[] raw = n.toByteArray();
            if (raw.length > 1 && raw[0] == 0) {
                var stripped = new byte[raw.length - 1];
                System.arraycopy(raw, 1, stripped, 0, stripped.length);
                raw = stripped;
            }
            var bs = new ByteString(raw);
            bs.setTag(3);
            return bs;
        }
    }
}
