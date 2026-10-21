package org.julclang.vm.java.builtins;

import org.julclang.core.PlutusData;
import org.julclang.core.cbor.PlutusDataCborEncoder;

/**
 * @deprecated Use {@link PlutusDataCborEncoder#encode(PlutusData)}, Julc's single Plutus Data encoder. Kept
 * because it shipped in 0.1.0-pre17 and pre18; it will be removed in a later pre-release.
 */
@Deprecated(forRemoval = true)
public final class DataSerializer {

    private DataSerializer() {}

    /** @deprecated Use {@link PlutusDataCborEncoder#encode(PlutusData)}. */
    @Deprecated(forRemoval = true)
    public static byte[] serialize(PlutusData data) {
        return PlutusDataCborEncoder.encode(data);
    }
}
