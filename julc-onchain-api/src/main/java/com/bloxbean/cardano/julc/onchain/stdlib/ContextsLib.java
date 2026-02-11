package com.bloxbean.cardano.julc.onchain.stdlib;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.onchain.ledger.Interval;
import com.bloxbean.cardano.julc.onchain.ledger.ScriptContext;
import com.bloxbean.cardano.julc.onchain.ledger.ScriptInfo;
import com.bloxbean.cardano.julc.onchain.ledger.TxInfo;

import java.util.Arrays;
import java.util.List;

/**
 * On-chain ScriptContext and TxInfo field extraction functions.
 * <p>
 * These methods are executable both on-chain (compiled to UPLC via StdlibRegistry)
 * and off-chain (as plain Java for debugging and testing).
 */
public final class ContextsLib {

    private ContextsLib() {}

    /** Extract TxInfo (field 0) from ScriptContext. */
    public static TxInfo getTxInfo(ScriptContext ctx) {
        return ctx.txInfo();
    }

    /** Extract redeemer (field 1) from ScriptContext. */
    public static PlutusData getRedeemer(ScriptContext ctx) {
        return ctx.redeemer();
    }

    /** Extract optional datum from spending ScriptContext. */
    public static PlutusData getSpendingDatum(ScriptContext ctx) {
        var scriptInfo = ctx.scriptInfo();
        if (scriptInfo instanceof ScriptInfo.SpendingScript ss) {
            return ss.datum() != null ? ss.datum() : PlutusData.UNIT;
        }
        return PlutusData.UNIT;
    }

    /** Check if a PubKeyHash is in TxInfo's signatories list. */
    public static boolean signedBy(TxInfo txInfo, byte[] pkh) {
        List<byte[]> signatories = txInfo.signatories();
        if (signatories == null) return false;
        for (byte[] sig : signatories) {
            if (Arrays.equals(sig, pkh)) {
                return true;
            }
        }
        return false;
    }

    /** Extract inputs list (field 0) from TxInfo as PlutusData. */
    public static PlutusData txInfoInputs(TxInfo txInfo) {
        var inputs = txInfo.inputs();
        var items = new java.util.ArrayList<PlutusData>();
        if (inputs != null) {
            for (int i = 0; i < inputs.size(); i++) {
                items.add(PlutusData.UNIT); // untyped placeholder
            }
        }
        return new PlutusData.ListData(items);
    }

    /** Extract outputs list (field 2) from TxInfo as PlutusData. */
    public static PlutusData txInfoOutputs(TxInfo txInfo) {
        var outputs = txInfo.outputs();
        var items = new java.util.ArrayList<PlutusData>();
        if (outputs != null) {
            for (int i = 0; i < outputs.size(); i++) {
                items.add(PlutusData.UNIT); // untyped placeholder
            }
        }
        return new PlutusData.ListData(items);
    }

    /** Extract signatories list (field 8) from TxInfo as PlutusData. */
    public static PlutusData txInfoSignatories(TxInfo txInfo) {
        var sigs = txInfo.signatories();
        var items = new java.util.ArrayList<PlutusData>();
        if (sigs != null) {
            for (byte[] sig : sigs) {
                items.add(new PlutusData.BytesData(sig));
            }
        }
        return new PlutusData.ListData(items);
    }

    /** Extract valid range (field 7) from TxInfo. */
    public static Interval txInfoValidRange(TxInfo txInfo) {
        return txInfo.validRange();
    }

    /** On-chain: emits UPLC Trace. Off-chain: prints for debugging. */
    public static void trace(String msg) {
        System.out.println("[TRACE] " + msg);
    }

    /** Extract mint field from TxInfo. */
    public static PlutusData txInfoMint(TxInfo txInfo) {
        return PlutusData.UNIT; // placeholder
    }

    /** Extract fee from TxInfo. */
    public static java.math.BigInteger txInfoFee(TxInfo txInfo) {
        return txInfo.fee() != null ? txInfo.fee() : java.math.BigInteger.ZERO;
    }

    /** Extract txId from TxInfo. */
    public static PlutusData txInfoId(TxInfo txInfo) {
        return PlutusData.UNIT; // placeholder
    }

    /** Find the input being spent (for spending validators). */
    public static PlutusData findOwnInput(ScriptContext ctx) {
        return PlutusData.UNIT; // complex off-chain impl not needed
    }

    /** Get outputs continuing to the same script address. */
    public static PlutusData getContinuingOutputs(ScriptContext ctx) {
        return new PlutusData.ListData(new java.util.ArrayList<>());
    }

    /** Find a datum by its hash in the txInfo datums map. */
    public static PlutusData findDatum(TxInfo txInfo, PlutusData hash) {
        return PlutusData.UNIT; // placeholder
    }

    /** Get list of input values. */
    public static PlutusData valueSpent(TxInfo txInfo) {
        return new PlutusData.ListData(new java.util.ArrayList<>());
    }

    /** Get list of values paid to a given address. */
    public static PlutusData valuePaid(TxInfo txInfo, PlutusData addr) {
        return new PlutusData.ListData(new java.util.ArrayList<>());
    }

    /** Extract own script hash from ScriptContext. */
    public static PlutusData ownHash(ScriptContext ctx) {
        return PlutusData.UNIT; // placeholder
    }

    /** Filter outputs by script credential hash. */
    public static PlutusData scriptOutputsAt(TxInfo txInfo, PlutusData scriptHash) {
        return new PlutusData.ListData(new java.util.ArrayList<>());
    }
}
