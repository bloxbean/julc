package com.bloxbean.cardano.julc.stdlib.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.onchain.annotation.OnchainLibrary;
import com.bloxbean.cardano.julc.onchain.ledger.Address;
import com.bloxbean.cardano.julc.onchain.ledger.OutputDatum;
import com.bloxbean.cardano.julc.onchain.ledger.TxOut;
import com.bloxbean.cardano.julc.onchain.ledger.Value;
import com.bloxbean.cardano.julc.onchain.stdlib.Builtins;
import java.math.BigInteger;
import java.util.List;

/**
 * Transaction output utility operations compiled from Java source to UPLC.
 * <p>
 * Uses ledger types (TxOut, Address, Value, OutputDatum) for readability.
 * No Builtins except {@code Builtins.error()} for abort and low-level value extraction.
 * <p>
 * Depends on: ListsLib (empty, prepend, reverse), MapLib (lookup).
 * <p>
 * Note: Casts like {@code (List<TxOut>)(Object)} are javac compatibility shims.
 * The JuLC compiler treats casts as no-ops since everything is Data at UPLC level.
 */
@OnchainLibrary
@SuppressWarnings("unchecked")
public class OutputLib {

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
    public static List<TxOut> outputsAt(List<TxOut> outputs, Address address) {
        List<TxOut> result = (List<TxOut>)(Object) ListsLib.empty();
        for (TxOut out : outputs) {
            if (out.address() == address) {
                result = (List<TxOut>)(Object) ListsLib.prepend((PlutusData.ListData)(Object) result, (PlutusData)(Object) out);
            } else {
                result = result;
            }
        }
        return (List<TxOut>)(Object) ListsLib.reverse((PlutusData.ListData)(Object) result);
    }

    /** Count the number of outputs sent to the given address. */
    public static long countOutputsAt(List<TxOut> outputs, Address address) {
        long count = 0;
        for (TxOut out : outputs) {
            if (out.address() == address) {
                count = count + 1;
            } else {
                count = count;
            }
        }
        return count;
    }

    /** Return the unique output at the given address. Aborts if not exactly one match. */
    public static TxOut uniqueOutputAt(List<TxOut> outputs, Address address) {
        List<TxOut> matched = outputsAt(outputs, address);
        PlutusData.ListData matchedData = (PlutusData.ListData)(Object) matched;
        if (ListsLib.length(matchedData) == 1) {
            return (TxOut)(Object) ListsLib.head(matchedData);
        } else {
            Builtins.error();
            return (TxOut)(Object) ListsLib.head(matchedData);
        }
    }

    // --- Internal Value Helpers ---

    /** Extract lovelace from a Value using Builtins (avoids cross-library coercion). */
    private static BigInteger extractLovelace(Value value) {
        var pairs = Builtins.unMapData((PlutusData.MapData)(Object) value);
        var firstPair = Builtins.headList(pairs);
        var tokenMapData = Builtins.sndPair(firstPair);
        var tokenPairs = Builtins.unMapData(tokenMapData);
        var firstTokenPair = Builtins.headList(tokenPairs);
        return BigInteger.valueOf(Builtins.unIData(Builtins.sndPair(firstTokenPair)));
    }

    /**
     * Extract the amount of a specific asset from a Value.
     * policyId and tokenName are Data (BData-wrapped bytestrings).
     * Returns 0 if not found.
     */
    private static BigInteger extractAssetAmount(Value value, PlutusData policyId, PlutusData tokenName) {
        var outerPairs = Builtins.unMapData((PlutusData.MapData)(Object) value);
        BigInteger result = BigInteger.ZERO;
        PlutusData current = outerPairs;
        while (!Builtins.nullList(current)) {
            var outerPair = Builtins.headList(current);
            if (Builtins.equalsData(Builtins.fstPair(outerPair), policyId)) {
                result = findTokenInInnerMap(Builtins.sndPair(outerPair), tokenName);
                current = Builtins.mkNilPairData();
            } else {
                current = Builtins.tailList(current);
            }
        }
        return result;
    }

    /** Search inner token map for a token name, return amount or 0. */
    private static BigInteger findTokenInInnerMap(PlutusData innerMap, PlutusData tokenName) {
        BigInteger result = BigInteger.ZERO;
        PlutusData current = Builtins.unMapData(innerMap);
        while (!Builtins.nullList(current)) {
            var pair = Builtins.headList(current);
            if (Builtins.equalsData(Builtins.fstPair(pair), tokenName)) {
                result = BigInteger.valueOf(Builtins.unIData(Builtins.sndPair(pair)));
                current = Builtins.mkNilPairData();
            } else {
                current = Builtins.tailList(current);
            }
        }
        return result;
    }

    // --- Token Filtering ---

    /** Return all outputs containing the specified token (amount > 0).
     *  policyId and tokenName must be Data (BData-wrapped bytestrings). */
    public static List<TxOut> outputsWithToken(List<TxOut> outputs, PlutusData policyId, PlutusData tokenName) {
        List<TxOut> result = (List<TxOut>)(Object) ListsLib.empty();
        for (TxOut out : outputs) {
            if (extractAssetAmount(out.value(), policyId, tokenName).compareTo(BigInteger.ZERO) > 0) {
                result = (List<TxOut>)(Object) ListsLib.prepend((PlutusData.ListData)(Object) result, (PlutusData)(Object) out);
            } else {
                result = result;
            }
        }
        return (List<TxOut>)(Object) ListsLib.reverse((PlutusData.ListData)(Object) result);
    }

    /** Check if a value contains any amount of the specified token.
     *  policyId and tokenName must be Data (BData-wrapped bytestrings). */
    public static boolean valueHasToken(Value value, PlutusData policyId, PlutusData tokenName) {
        return extractAssetAmount(value, policyId, tokenName).compareTo(BigInteger.ZERO) > 0;
    }

    // --- Value Summation ---

    /** Sum the lovelace in all outputs sent to the given address. */
    public static BigInteger lovelacePaidTo(List<TxOut> outputs, Address address) {
        BigInteger total = BigInteger.ZERO;
        for (TxOut out : outputs) {
            if (out.address() == address) {
                total = total.add(extractLovelace(out.value()));
            } else {
                total = total;
            }
        }
        return total;
    }

    /** Check if the total lovelace paid to the address meets the minimum threshold. */
    public static boolean paidAtLeast(List<TxOut> outputs, Address address, BigInteger minLovelace) {
        return lovelacePaidTo(outputs, address).compareTo(minLovelace) >= 0;
    }

    // --- Datum Extraction ---

    /** Extract the inline datum from a TxOut. Aborts if the datum is not inline. */
    public static PlutusData getInlineDatum(TxOut txOut) {
        return switch (txOut.datum()) {
            case OutputDatum.OutputDatumInline i -> i.datum();
            default -> {
                Builtins.error();
                yield (PlutusData)(Object) txOut.datum();
            }
        };
    }

    /** Resolve the datum from a TxOut: inline datum is returned directly,
     *  datum hash is looked up in the datums map, NoDatum aborts. */
    public static PlutusData resolveDatum(TxOut txOut, PlutusData.MapData datumsMap) {
        return switch (txOut.datum()) {
            case OutputDatum.OutputDatumInline i -> i.datum();
            case OutputDatum.OutputDatumHash h -> {
                PlutusData result = MapLib.lookup(datumsMap, Builtins.bData((PlutusData.BytesData)(Object) h.hash()));
                if (Builtins.constrTag(result) == 0) {
                    yield Builtins.headList(Builtins.constrFields(result));
                } else {
                    Builtins.error();
                    yield result;
                }
            }
            default -> {
                Builtins.error();
                yield (PlutusData)(Object) txOut.datum();
            }
        };
    }
}
