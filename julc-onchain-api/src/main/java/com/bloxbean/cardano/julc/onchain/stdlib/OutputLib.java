package com.bloxbean.cardano.julc.onchain.stdlib;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.types.JulcArrayList;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.ledger.*;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * On-chain output utility operations.
 * <p>
 * These methods are executable both on-chain (compiled to UPLC via the @OnchainLibrary
 * source in julc-stdlib) and off-chain (as plain Java for debugging and testing).
 * <p>
 * Note: Address equality compares credentials only (not staking credential),
 * matching the on-chain == semantics (EqualsData on the full Constr).
 */
public final class OutputLib {

    private OutputLib() {}

    // --- TxOut Field Accessors ---

    /** Extract the address from a TxOut. */
    public static Address txOutAddress(TxOut txOut) {
        return txOut.address();
    }

    /** Extract the value from a TxOut. */
    public static Value txOutValue(TxOut txOut) {
        return txOut.value();
    }

    /** Extract the datum from a TxOut. */
    public static OutputDatum txOutDatum(TxOut txOut) {
        return txOut.datum();
    }

    // --- Output Filtering ---

    /** Return all outputs sent to the given address. */
    public static JulcList<TxOut> outputsAt(JulcList<TxOut> outputs, Address address) {
        var result = new ArrayList<TxOut>();
        for (TxOut out : outputs) {
            if (addressEquals(out.address(), address)) {
                result.add(out);
            }
        }
        return new JulcArrayList<>(result);
    }

    /** Count the number of outputs sent to the given address. */
    public static long countOutputsAt(JulcList<TxOut> outputs, Address address) {
        long count = 0;
        for (TxOut out : outputs) {
            if (addressEquals(out.address(), address)) {
                count++;
            }
        }
        return count;
    }

    /** Return the unique output at the given address. Throws if not exactly one match. */
    public static TxOut uniqueOutputAt(JulcList<TxOut> outputs, Address address) {
        var matched = outputsAt(outputs, address);
        if (matched.size() == 1) {
            return matched.head();
        }
        throw new RuntimeException("Expected exactly 1 output at address, found " + matched.size());
    }

    // --- Token Filtering ---

    /** Return all outputs containing the specified token (amount > 0). */
    public static JulcList<TxOut> outputsWithToken(JulcList<TxOut> outputs, byte[] policyId, byte[] tokenName) {
        var result = new ArrayList<TxOut>();
        for (TxOut out : outputs) {
            if (ValuesLib.assetOf(out.value(), policyId, tokenName).compareTo(BigInteger.ZERO) > 0) {
                result.add(out);
            }
        }
        return new JulcArrayList<>(result);
    }

    /** Check if a value contains any amount of the specified token. */
    public static boolean valueHasToken(Value value, byte[] policyId, byte[] tokenName) {
        return ValuesLib.assetOf(value, policyId, tokenName).compareTo(BigInteger.ZERO) > 0;
    }

    // --- Value Summation ---

    /** Sum the lovelace in all outputs sent to the given address. */
    public static BigInteger lovelacePaidTo(JulcList<TxOut> outputs, Address address) {
        BigInteger total = BigInteger.ZERO;
        for (TxOut out : outputs) {
            if (addressEquals(out.address(), address)) {
                total = total.add(ValuesLib.lovelaceOf(out.value()));
            }
        }
        return total;
    }

    /** Check if the total lovelace paid to the address meets the minimum threshold. */
    public static boolean paidAtLeast(JulcList<TxOut> outputs, Address address, BigInteger minLovelace) {
        return lovelacePaidTo(outputs, address).compareTo(minLovelace) >= 0;
    }

    // --- Datum Extraction ---

    /** Extract the inline datum from a TxOut. Throws if the datum is not inline. */
    public static PlutusData getInlineDatum(TxOut txOut) {
        if (txOut.datum() instanceof OutputDatum.OutputDatumInline i) {
            return i.datum();
        }
        throw new RuntimeException("Expected inline datum");
    }

    /**
     * Resolve the datum from a TxOut: inline datum is returned directly,
     * datum hash is looked up in the datums map, NoDatum throws.
     */
    public static PlutusData resolveDatum(TxOut txOut, PlutusData.MapData datumsMap) {
        if (txOut.datum() instanceof OutputDatum.OutputDatumInline i) {
            return i.datum();
        }
        if (txOut.datum() instanceof OutputDatum.OutputDatumHash h) {
            for (var pair : datumsMap.entries()) {
                if (pair.key() instanceof PlutusData.BytesData kb
                        && Arrays.equals(kb.value(), h.hash().hash())) {
                    return pair.value();
                }
            }
            throw new RuntimeException("Datum hash not found in map");
        }
        throw new RuntimeException("No datum on output");
    }

    // --- Internal helpers ---

    private static boolean addressEquals(Address a, Address b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return credentialEquals(a.credential(), b.credential());
    }

    private static boolean credentialEquals(Credential a, Credential b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a.getClass() != b.getClass()) return false;
        return switch (a) {
            case Credential.PubKeyCredential pk ->
                    Arrays.equals(pk.hash().hash(), ((Credential.PubKeyCredential) b).hash().hash());
            case Credential.ScriptCredential sc ->
                    Arrays.equals(sc.hash().hash(), ((Credential.ScriptCredential) b).hash().hash());
        };
    }
}
