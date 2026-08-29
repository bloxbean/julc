package com.bloxbean.cardano.julc.stdlib.lib;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.stdlib.annotation.OnchainLibrary;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.core.types.JulcValue;

import java.math.BigInteger;

/**
 * Native MaryEra Value operations using PV11 builtins (CIP-153).
 * <p>
 * <b>Experimental PV11 API</b> — These operations use the native UPLC Value type
 * introduced in protocol version 11. This is a separate API from
 * {@link ValuesLib}: the latter retains the ledger-facing Data representation,
 * while this class makes native operations and conversions explicit.
 * <p>
 * Unlike {@link ValuesLib} which operates on Map-encoded PlutusData, these methods
 * operate on the opaque {@link JulcValue} UPLC type. Use
 * {@link #fromData(PlutusData)} to convert from Map encoding and
 * {@link #toData(JulcValue)} to convert back.
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
    public static JulcValue fromData(PlutusData mapData) {
        return Builtins.unValueData(mapData);
    }

    /** Convert native Value back to Map-encoded PlutusData. */
    public static PlutusData toData(JulcValue value) {
        return Builtins.valueData(value);
    }

    /** Insert or update a token quantity in a Value. */
    public static JulcValue insertCoin(byte[] policyId, byte[] tokenName, BigInteger amount, JulcValue value) {
        return Builtins.insertCoin(policyId, tokenName, amount, value);
    }

    /** Look up a token quantity. Returns 0 if absent. */
    public static BigInteger lookupCoin(byte[] policyId, byte[] tokenName, JulcValue value) {
        return Builtins.lookupCoin(policyId, tokenName, value);
    }

    /** Merge two Values by adding quantities. */
    public static JulcValue union(JulcValue a, JulcValue b) {
        return Builtins.unionValue(a, b);
    }

    /** Check if Value a contains at least Value b (a >= b element-wise). */
    public static boolean contains(JulcValue a, JulcValue b) {
        return Builtins.valueContains(a, b);
    }

    /** Scale all quantities by a scalar. */
    public static JulcValue scale(BigInteger scalar, JulcValue value) {
        return Builtins.scaleValue(scalar, value);
    }
}
