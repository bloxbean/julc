package com.bloxbean.cardano.julc.testkit;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.PlutusDataConvertible;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

/**
 * Converts Java method arguments to {@link PlutusData} for UPLC evaluation.
 */
public final class ArgConverter {

    private ArgConverter() {}

    /**
     * Convert an array of Java arguments to PlutusData.
     *
     * @param args the Java arguments (may be null for zero-arg methods)
     * @return the converted PlutusData array
     */
    public static PlutusData[] convert(Object[] args) {
        if (args == null || args.length == 0) {
            return new PlutusData[0];
        }
        var result = new PlutusData[args.length];
        for (int i = 0; i < args.length; i++) {
            result[i] = convertOne(args[i]);
        }
        return result;
    }

    /**
     * Convert a single Java argument to PlutusData.
     *
     * @param arg the Java argument
     * @return the PlutusData representation
     * @throws IllegalArgumentException if the argument type is not supported
     */
    static PlutusData convertOne(Object arg) {
        if (arg == null) {
            throw new IllegalArgumentException("Null arguments are not supported");
        }
        return switch (arg) {
            case PlutusData pd -> pd;
            case PlutusDataConvertible pdc -> pdc.toPlutusData();
            case BigInteger bi -> PlutusData.integer(bi);
            case Long l -> PlutusData.integer(l);
            case Integer i -> PlutusData.integer(i);
            case byte[] bs -> PlutusData.bytes(bs);
            case Boolean b -> PlutusData.constr(b ? 1 : 0);
            case String s -> PlutusData.bytes(s.getBytes(StandardCharsets.UTF_8));
            default -> throw new IllegalArgumentException(
                    "Unsupported argument type: " + arg.getClass().getName()
                    + ". Supported: BigInteger, long, int, byte[], boolean, String, PlutusData, PlutusDataConvertible");
        };
    }
}
