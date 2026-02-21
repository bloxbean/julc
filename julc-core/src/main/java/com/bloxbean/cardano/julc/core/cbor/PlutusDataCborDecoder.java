package com.bloxbean.cardano.julc.core.cbor;

import co.nstant.in.cbor.CborDecoder;
import co.nstant.in.cbor.CborException;
import co.nstant.in.cbor.model.*;
import co.nstant.in.cbor.model.Map;
import com.bloxbean.cardano.julc.core.PlutusData;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Decodes {@link PlutusData} from CBOR bytes following the Cardano specification.
 */
public final class PlutusDataCborDecoder {

    private PlutusDataCborDecoder() {}

    /**
     * Decode PlutusData from CBOR bytes.
     */
    public static PlutusData decode(byte[] cborBytes) {
        try {
            var items = new CborDecoder(new ByteArrayInputStream(cborBytes)).decode();
            if (items.isEmpty()) {
                throw new CborDecodingException("Empty CBOR data");
            }
            return fromDataItem(items.getFirst());
        } catch (CborException e) {
            throw new CborDecodingException("CBOR decoding failed", e);
        }
    }

    /**
     * Convert a CBOR DataItem to PlutusData.
     */
    public static PlutusData fromDataItem(DataItem item) {
        // Check for CBOR tags first
        if (item.hasTag()) {
            long tag = item.getTag().getValue();

            // Constr compact: tags 121-127 → constructor 0-6
            if (tag >= 121 && tag <= 127) {
                return decodeConstrFields((int) (tag - 121), item);
            }

            // Constr extended: tags 1280-1400 → constructor 7-127
            if (tag >= 1280 && tag <= 1400) {
                return decodeConstrFields((int) (tag - 1280 + 7), item);
            }

            // Constr general: tag 102 → [constructor_tag, fields]
            if (tag == 102) {
                return decodeConstrGeneral(item);
            }

            // BigNum: tag 2 (positive), tag 3 (negative)
            if (tag == 2) {
                byte[] bytes = extractBytes(item);
                return new PlutusData.IntData(new BigInteger(1, bytes));
            }
            if (tag == 3) {
                byte[] bytes = extractBytes(item);
                // Value = -(1 + n)
                BigInteger n = new BigInteger(1, bytes);
                return new PlutusData.IntData(n.add(BigInteger.ONE).negate());
            }
        }

        return switch (item.getMajorType()) {
            case UNSIGNED_INTEGER -> {
                var ui = (UnsignedInteger) item;
                yield new PlutusData.IntData(ui.getValue());
            }
            case NEGATIVE_INTEGER -> {
                var ni = (NegativeInteger) item;
                yield new PlutusData.IntData(ni.getValue());
            }
            case BYTE_STRING -> new PlutusData.BytesData(extractBytes(item));
            case ARRAY -> {
                var array = (Array) item;
                var items = new ArrayList<PlutusData>();
                for (var elem : filterBreaks(array.getDataItems())) {
                    items.add(fromDataItem(elem));
                }
                yield new PlutusData.ListData(items);
            }
            case MAP -> {
                var map = (Map) item;
                var entries = new ArrayList<PlutusData.Pair>();
                Collection<DataItem> keys = map.getKeys();
                for (var key : keys) {
                    if (key.getMajorType() == MajorType.SPECIAL) continue; // skip break codes
                    var value = map.get(key);
                    entries.add(new PlutusData.Pair(fromDataItem(key), fromDataItem(value)));
                }
                yield new PlutusData.MapData(entries);
            }
            default -> throw new CborDecodingException(
                    "Unsupported CBOR major type for PlutusData: " + item.getMajorType());
        };
    }

    private static PlutusData decodeConstrFields(int constrTag, DataItem item) {
        if (item instanceof Array array) {
            var fields = new ArrayList<PlutusData>();
            for (var elem : filterBreaks(array.getDataItems())) {
                fields.add(fromDataItem(elem));
            }
            return new PlutusData.ConstrData(constrTag, fields);
        }
        throw new CborDecodingException("Expected array for Constr fields, got: " + item.getMajorType());
    }

    private static PlutusData decodeConstrGeneral(DataItem item) {
        if (item instanceof Array outer) {
            var items = filterBreaks(outer.getDataItems());
            if (items.size() != 2) {
                throw new CborDecodingException(
                        "Constr general encoding expects [tag, fields], got " + items.size() + " elements");
            }
            BigInteger tagValue;
            if (items.get(0) instanceof UnsignedInteger ui) {
                tagValue = ui.getValue();
            } else if (items.get(0) instanceof NegativeInteger ni) {
                tagValue = ni.getValue();
            } else {
                throw new CborDecodingException(
                        "Expected integer for Constr tag, got: " + items.get(0).getMajorType());
            }
            if (items.get(1) instanceof Array fieldsArray) {
                var fields = new ArrayList<PlutusData>();
                for (var elem : filterBreaks(fieldsArray.getDataItems())) {
                    fields.add(fromDataItem(elem));
                }
                try {
                    return new PlutusData.ConstrData(tagValue.intValueExact(), fields);
                } catch (ArithmeticException e) {
                    throw new CborDecodingException("Constr tag exceeds int range: " + tagValue, e);
                }
            }
            throw new CborDecodingException("Expected array for Constr fields in general encoding");
        }
        throw new CborDecodingException("Expected array for Constr general encoding");
    }

    /**
     * Filter out CBOR break codes (SPECIAL major type) from a list of data items.
     * The cbor-java library includes the break code (0xFF) as an element in indefinite-length
     * arrays, so we must filter it out before processing.
     */
    private static List<DataItem> filterBreaks(List<DataItem> items) {
        return items.stream()
                .filter(i -> i.getMajorType() != MajorType.SPECIAL)
                .toList();
    }

    /**
     * Extract bytes from a ByteString DataItem.
     * The cbor-java library automatically concatenates chunked byte strings.
     */
    private static byte[] extractBytes(DataItem item) {
        if (item instanceof ByteString bs) {
            return bs.getBytes();
        }
        throw new CborDecodingException("Expected ByteString, got: " + item.getMajorType());
    }
}
