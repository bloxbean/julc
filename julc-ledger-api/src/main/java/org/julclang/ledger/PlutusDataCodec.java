package org.julclang.ledger;

import org.julclang.core.PlutusData;

/**
 * Generic bidirectional codec between a Java type and PlutusData.
 *
 * @param <T> the Java type to encode/decode
 */
public interface PlutusDataCodec<T> {
    PlutusData toPlutusData(T value);
    T fromPlutusData(PlutusData data);
}
