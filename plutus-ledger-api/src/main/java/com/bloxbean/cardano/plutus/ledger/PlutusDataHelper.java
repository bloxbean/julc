package com.bloxbean.cardano.plutus.ledger;

import com.bloxbean.cardano.plutus.core.PlutusData;

import java.math.BigInteger;
import java.util.*;
import java.util.function.Function;

/**
 * Static helpers for encoding/decoding common Java types to/from PlutusData.
 * Follows the Haskell/Scalus encoding conventions.
 */
public final class PlutusDataHelper {

    private PlutusDataHelper() {}

    // --- Boolean encoding ---
    // False -> Constr(0, []), True -> Constr(1, [])

    private static final PlutusData FALSE_DATA = new PlutusData.Constr(0, List.of());
    private static final PlutusData TRUE_DATA = new PlutusData.Constr(1, List.of());

    public static PlutusData encodeBool(boolean value) {
        return value ? TRUE_DATA : FALSE_DATA;
    }

    public static boolean decodeBool(PlutusData data) {
        if (data instanceof PlutusData.Constr c) {
            return switch (c.tag()) {
                case 0 -> false;
                case 1 -> true;
                default -> throw new IllegalArgumentException("Invalid Bool encoding: Constr tag " + c.tag());
            };
        }
        throw new IllegalArgumentException("Expected Constr for Bool, got: " + data.getClass().getSimpleName());
    }

    // --- Optional encoding ---
    // Some(x) -> Constr(0, [toData(x)]), None -> Constr(1, [])

    public static <T> PlutusData encodeOptional(Optional<T> opt, Function<T, PlutusData> encoder) {
        if (opt.isPresent()) {
            return new PlutusData.Constr(0, List.of(encoder.apply(opt.get())));
        }
        return new PlutusData.Constr(1, List.of());
    }

    public static <T> Optional<T> decodeOptional(PlutusData data, Function<PlutusData, T> decoder) {
        if (data instanceof PlutusData.Constr c) {
            return switch (c.tag()) {
                case 0 -> {
                    if (c.fields().size() != 1) {
                        throw new IllegalArgumentException("Some variant must have exactly 1 field, got: " + c.fields().size());
                    }
                    yield Optional.of(decoder.apply(c.fields().getFirst()));
                }
                case 1 -> Optional.empty();
                default -> throw new IllegalArgumentException("Invalid Optional encoding: Constr tag " + c.tag());
            };
        }
        throw new IllegalArgumentException("Expected Constr for Optional, got: " + data.getClass().getSimpleName());
    }

    // --- List encoding ---

    public static <T> PlutusData encodeList(List<T> list, Function<T, PlutusData> encoder) {
        List<PlutusData> items = new ArrayList<>(list.size());
        for (T elem : list) {
            items.add(encoder.apply(elem));
        }
        return new PlutusData.ListData(items);
    }

    public static <T> List<T> decodeList(PlutusData data, Function<PlutusData, T> decoder) {
        if (data instanceof PlutusData.ListData ld) {
            List<T> result = new ArrayList<>(ld.items().size());
            for (PlutusData item : ld.items()) {
                result.add(decoder.apply(item));
            }
            return List.copyOf(result);
        }
        throw new IllegalArgumentException("Expected ListData, got: " + data.getClass().getSimpleName());
    }

    // --- Map encoding ---

    public static <K, V> PlutusData encodeMap(
            java.util.Map<K, V> map,
            Function<K, PlutusData> keyEncoder,
            Function<V, PlutusData> valueEncoder) {
        List<PlutusData.Pair> entries = new ArrayList<>(map.size());
        for (var entry : map.entrySet()) {
            entries.add(new PlutusData.Pair(
                    keyEncoder.apply(entry.getKey()),
                    valueEncoder.apply(entry.getValue())));
        }
        return new PlutusData.Map(entries);
    }

    public static <K, V> java.util.Map<K, V> decodeMap(
            PlutusData data,
            Function<PlutusData, K> keyDecoder,
            Function<PlutusData, V> valueDecoder) {
        if (data instanceof PlutusData.Map m) {
            LinkedHashMap<K, V> result = new LinkedHashMap<>(m.entries().size());
            for (PlutusData.Pair pair : m.entries()) {
                result.put(keyDecoder.apply(pair.key()), valueDecoder.apply(pair.value()));
            }
            return Collections.unmodifiableMap(result);
        }
        throw new IllegalArgumentException("Expected Map, got: " + data.getClass().getSimpleName());
    }

    // --- Integer encoding ---

    public static PlutusData encodeInteger(BigInteger value) {
        return new PlutusData.IntData(value);
    }

    public static BigInteger decodeInteger(PlutusData data) {
        if (data instanceof PlutusData.IntData i) {
            return i.value();
        }
        throw new IllegalArgumentException("Expected IntData, got: " + data.getClass().getSimpleName());
    }

    // --- ByteString encoding ---

    public static PlutusData encodeBytes(byte[] value) {
        return new PlutusData.BytesData(value);
    }

    public static byte[] decodeBytes(PlutusData data) {
        if (data instanceof PlutusData.BytesData b) {
            return b.value();
        }
        throw new IllegalArgumentException("Expected BytesData, got: " + data.getClass().getSimpleName());
    }

    // --- Constr field helpers ---

    /**
     * Extract fields from a Constr, validating the expected tag.
     */
    public static List<PlutusData> expectConstr(PlutusData data, int expectedTag) {
        if (data instanceof PlutusData.Constr c) {
            if (c.tag() != expectedTag) {
                throw new IllegalArgumentException(
                        "Expected Constr tag " + expectedTag + ", got: " + c.tag());
            }
            return c.fields();
        }
        throw new IllegalArgumentException("Expected Constr, got: " + data.getClass().getSimpleName());
    }

    /**
     * Extract fields from a Constr, returning the tag and fields.
     */
    public static PlutusData.Constr expectConstr(PlutusData data) {
        if (data instanceof PlutusData.Constr c) {
            return c;
        }
        throw new IllegalArgumentException("Expected Constr, got: " + data.getClass().getSimpleName());
    }
}
