package com.bloxbean.cardano.julc.stdlib.lib;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.stdlib.annotation.OnchainLibrary;
import com.bloxbean.cardano.julc.stdlib.Builtins;

/**
 * Native MaryEra Value operations using PV11 builtins (CIP-153).
 * <p>
 * <b>Experimental PV11 API</b> — These operations use the native UPLC Value type
 * introduced in protocol version 11. They will be merged into {@link ValuesLib}
 * once the PV11 hard fork is complete. Until then, use these for PV11 testing
 * and be prepared to migrate imports in a future release.
 * <p>
 * Unlike {@link ValuesLib} which operates on Map-encoded PlutusData, these methods
 * operate on the native Value UPLC constant type. Use {@link #fromData(PlutusData)}
 * to convert from Map encoding and {@link #toData(PlutusData)} to convert back.
 * <p>
 * <b>Off-chain testing:</b> These methods throw {@link UnsupportedOperationException} when
 * called directly on the JVM. To test code that uses this library:
 * <ul>
 *   <li>Use {@code JulcEval.forSource(...)} to compile and evaluate through the UPLC VM</li>
 *   <li>Or mock these calls using a test framework such as Mockito</li>
 * </ul>
 *
 * @see ValuesLib
 */
@OnchainLibrary
public class NativeValueLib {

    /** Convert Map-encoded PlutusData to native Value. */
    public static PlutusData fromData(PlutusData mapData) {
        return Builtins.unValueData(mapData);
    }

    /** Convert native Value back to Map-encoded PlutusData. */
    public static PlutusData toData(PlutusData value) {
        return Builtins.valueData(value);
    }

    /** Insert or update a token quantity in a Value. */
    public static PlutusData insertCoin(byte[] policyId, byte[] tokenName, long amount, PlutusData value) {
        return Builtins.insertCoin(policyId, tokenName, amount, value);
    }

    /** Look up a token quantity. Returns 0 if absent. */
    public static long lookupCoin(byte[] policyId, byte[] tokenName, PlutusData value) {
        return Builtins.lookupCoin(policyId, tokenName, value);
    }

    /** Merge two Values by adding quantities. */
    public static PlutusData union(PlutusData a, PlutusData b) {
        return Builtins.unionValue(a, b);
    }

    /** Check if Value a contains at least Value b (a >= b element-wise). */
    public static boolean contains(PlutusData a, PlutusData b) {
        return Builtins.valueContains(a, b);
    }

    /** Scale all quantities by a scalar. */
    public static PlutusData scale(long scalar, PlutusData value) {
        return Builtins.scaleValue(scalar, value);
    }
}
