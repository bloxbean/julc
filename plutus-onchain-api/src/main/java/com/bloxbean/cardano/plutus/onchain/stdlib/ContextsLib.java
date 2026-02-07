package com.bloxbean.cardano.plutus.onchain.stdlib;

import com.bloxbean.cardano.plutus.core.PlutusData;

/**
 * On-chain ScriptContext and TxInfo field extraction functions.
 * <p>
 * These are compile-time stubs for IDE support. The actual on-chain implementation
 * is provided by the PlutusCompiler via {@code StdlibRegistry}.
 */
public final class ContextsLib {

    private ContextsLib() {}

    /** Extract TxInfo (field 0) from ScriptContext. */
    public static PlutusData getTxInfo(PlutusData ctx) {
        throw new UnsupportedOperationException("On-chain only");
    }

    /** Extract redeemer (field 1) from ScriptContext. */
    public static PlutusData getRedeemer(PlutusData ctx) {
        throw new UnsupportedOperationException("On-chain only");
    }

    /** Extract optional datum from spending ScriptContext. */
    public static PlutusData getSpendingDatum(PlutusData ctx) {
        throw new UnsupportedOperationException("On-chain only");
    }

    /** Check if a PubKeyHash is in TxInfo's signatories list. */
    public static boolean signedBy(PlutusData txInfo, PlutusData pkh) {
        throw new UnsupportedOperationException("On-chain only");
    }

    /** Extract inputs list (field 0) from TxInfo. */
    public static PlutusData txInfoInputs(PlutusData txInfo) {
        throw new UnsupportedOperationException("On-chain only");
    }

    /** Extract outputs list (field 2) from TxInfo. */
    public static PlutusData txInfoOutputs(PlutusData txInfo) {
        throw new UnsupportedOperationException("On-chain only");
    }

    /** Extract signatories list (field 8) from TxInfo. */
    public static PlutusData txInfoSignatories(PlutusData txInfo) {
        throw new UnsupportedOperationException("On-chain only");
    }

    /** Extract valid range (field 7) from TxInfo. */
    public static PlutusData txInfoValidRange(PlutusData txInfo) {
        throw new UnsupportedOperationException("On-chain only");
    }
}
