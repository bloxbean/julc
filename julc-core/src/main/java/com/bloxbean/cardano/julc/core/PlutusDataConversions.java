package com.bloxbean.cardano.julc.core;

import com.bloxbean.cardano.julc.core.types.JulcList;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Canonical off-chain conversions used by JuLC collection APIs and stdlib
 * builtin shims.
 */
public final class PlutusDataConversions {

    private PlutusDataConversions() {
    }

    /**
     * Convert a supported JuLC value to Plutus Data.
     *
     * @throws IllegalArgumentException if the value is null, unsupported, or a
     *                                  custom converter returns null
     */
    public static PlutusData toPlutusData(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("Cannot convert null to PlutusData");
        }
        if (value instanceof PlutusData data) {
            return data;
        }
        // JulcList implements ToPlutusData; handle it first to avoid calling its
        // default method recursively.
        if (value instanceof JulcList<?> list) {
            return listToPlutusData(list);
        }
        if (value instanceof ToPlutusData convertible) {
            var converted = convertible.toPlutusData();
            if (converted == null) {
                throw new IllegalArgumentException(
                        "toPlutusData() returned null for " + value.getClass().getName());
            }
            return converted;
        }
        if (value instanceof BigInteger integer) {
            return new PlutusData.IntData(integer);
        }
        if (value instanceof Long integer) {
            return new PlutusData.IntData(BigInteger.valueOf(integer));
        }
        if (value instanceof Integer integer) {
            return new PlutusData.IntData(BigInteger.valueOf(integer));
        }
        if (value instanceof byte[] bytes) {
            return new PlutusData.BytesData(bytes);
        }
        if (value instanceof Boolean bool) {
            return new PlutusData.ConstrData(bool ? 1 : 0, List.of());
        }
        if (value instanceof String string) {
            return new PlutusData.BytesData(string.getBytes(StandardCharsets.UTF_8));
        }
        throw new IllegalArgumentException(
                "Unsupported PlutusData conversion type: " + value.getClass().getName());
    }

    /** Convert a JuLC list, recursively converting every element. */
    public static PlutusData.ListData listToPlutusData(JulcList<?> list) {
        if (list == null) {
            throw new IllegalArgumentException("Cannot convert null JulcList to PlutusData");
        }
        var items = new ArrayList<PlutusData>();
        for (var item : list) {
            items.add(toPlutusData(item));
        }
        return new PlutusData.ListData(items);
    }
}
