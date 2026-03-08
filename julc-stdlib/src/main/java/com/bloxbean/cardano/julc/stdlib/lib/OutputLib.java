package com.bloxbean.cardano.julc.stdlib.lib;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.core.types.JulcMap;
import com.bloxbean.cardano.julc.stdlib.annotation.OnchainLibrary;
import com.bloxbean.cardano.julc.ledger.Address;
import com.bloxbean.cardano.julc.ledger.OutputDatum;
import com.bloxbean.cardano.julc.ledger.TxInInfo;
import com.bloxbean.cardano.julc.ledger.TxOut;
import com.bloxbean.cardano.julc.ledger.Value;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import java.math.BigInteger;
import java.util.Optional;

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
    public static JulcList<TxOut> outputsAt(JulcList<TxOut> outputs, Address address) {
        JulcList<TxOut> result = JulcList.empty();
        for (TxOut out : outputs) {
            if (Builtins.equalsData(out.address(), address)) {
                result = result.prepend(out);
            } else {
                result = result;
            }
        }
        return result.reverse();
    }

    /** Count the number of outputs sent to the given address. */
    public static long countOutputsAt(JulcList<TxOut> outputs, Address address) {
        long count = 0;
        for (TxOut out : outputs) {
            if (Builtins.equalsData(out.address(), address)) {
                count = count + 1;
            } else {
                count = count;
            }
        }
        return count;
    }

    /** Return the unique output at the given address. Aborts if not exactly one match. */
    public static TxOut uniqueOutputAt(JulcList<TxOut> outputs, Address address) {
        JulcList<TxOut> matched = outputsAt(outputs, address);
        if (matched.size() == 1) {
            return matched.head();
        } else {
            Builtins.error();
            return matched.head();
        }
    }

    // --- Internal Value Helpers (delegated to ValuesLib) ---

    /** Extract lovelace from a Value via ValuesLib. */
    private static BigInteger extractLovelace(Value value) {
        return ValuesLib.lovelaceOf(value);
    }

    // --- Token Filtering ---

    /** Return all outputs containing the specified token (amount > 0). */
    public static JulcList<TxOut> outputsWithToken(JulcList<TxOut> outputs, byte[] policyId, byte[] tokenName) {
        JulcList<TxOut> result = JulcList.empty();
        for (TxOut out : outputs) {
            if (ValuesLib.assetOf(out.value(), policyId, tokenName).compareTo(BigInteger.ZERO) > 0) {
                result = result.prepend(out);
            } else {
                result = result;
            }
        }
        return result.reverse();
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
            if (Builtins.equalsData(out.address(), address)) {
                total = total.add(extractLovelace(out.value()));
            } else {
                total = total;
            }
        }
        return total;
    }

    /** Check if the total lovelace paid to the address meets the minimum threshold. */
    public static boolean paidAtLeast(JulcList<TxOut> outputs, Address address, BigInteger minLovelace) {
        return lovelacePaidTo(outputs, address).compareTo(minLovelace) >= 0;
    }

    // --- Datum Extraction ---

    /** Extract the inline datum from a TxOut. Aborts if the datum is not inline. */
    public static PlutusData getInlineDatum(TxOut txOut) {
        return switch (txOut.datum()) {
            case OutputDatum.OutputDatumInline i -> i.datum();
            case OutputDatum.OutputDatumHash h -> {
                Builtins.error();
                yield (PlutusData)(Object) txOut.datum();
            }
            case OutputDatum.NoOutputDatum n -> {
                Builtins.error();
                yield (PlutusData)(Object) txOut.datum();
            }
        };
    }

    /** Resolve the datum from a TxOut: inline datum is returned directly,
     *  datum hash is looked up in the datums map, NoDatum aborts. */
    public static PlutusData resolveDatum(TxOut txOut, JulcMap<PlutusData, PlutusData> datumsMap) {
        return switch (txOut.datum()) {
            case OutputDatum.OutputDatumInline i -> i.datum();
            case OutputDatum.OutputDatumHash h -> {
                Optional<PlutusData> result = MapLib.lookup(datumsMap, Builtins.bData(h.hash()));
                if (result.isPresent()) {
                    yield result.get();
                } else {
                    Builtins.error();
                    yield (PlutusData)(Object) txOut.datum();
                }
            }
            case OutputDatum.NoOutputDatum n -> {
                Builtins.error();
                yield (PlutusData)(Object) txOut.datum();
            }
        };
    }

    // --- Multi-validator helpers: find outputs/inputs at script address with token ---

    /**
     * Find the first output at a script address (identified by scriptHash) containing
     * the specified token with inline datum. Aborts if not found.
     */
    public static TxOut findOutputWithToken(JulcList<TxOut> outputs, byte[] scriptHash,
                                            byte[] policyId, byte[] tokenName) {
        TxOut result = outputs.head(); // placeholder — will be overwritten or abort
        boolean found = false;
        for (TxOut out : outputs) {
            if (!found) {
                byte[] sh = AddressLib.credentialHash(out.address());
                if (Builtins.equalsByteString(sh, scriptHash)
                        && ValuesLib.assetOf(out.value(), policyId, tokenName).compareTo(BigInteger.ZERO) > 0) {
                    result = out;
                    found = true;
                }
            }
        }
        if (!found) {
            Builtins.error();
        }
        return result;
    }

    /**
     * Find the first input at a script address containing the specified token with inline datum.
     * Aborts if not found.
     */
    public static TxInInfo findInputWithToken(JulcList<TxInInfo> inputs, byte[] scriptHash,
                                               byte[] policyId, byte[] tokenName) {
        TxInInfo result = inputs.head(); // placeholder — will be overwritten or abort
        boolean found = false;
        for (TxInInfo inp : inputs) {
            if (!found) {
                byte[] sh = AddressLib.credentialHash(inp.resolved().address());
                if (Builtins.equalsByteString(sh, scriptHash)
                        && ValuesLib.assetOf(inp.resolved().value(), policyId, tokenName).compareTo(BigInteger.ZERO) > 0) {
                    result = inp;
                    found = true;
                }
            }
        }
        if (!found) {
            Builtins.error();
        }
        return result;
    }
}
