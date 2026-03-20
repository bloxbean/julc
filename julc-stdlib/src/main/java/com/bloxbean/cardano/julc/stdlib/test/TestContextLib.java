package com.bloxbean.cardano.julc.stdlib.test;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.core.types.JulcMap;
import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.annotation.OnchainLibrary;
import com.bloxbean.cardano.julc.stdlib.lib.ListsLib;
import com.bloxbean.cardano.julc.stdlib.lib.MapLib;

import java.math.BigInteger;
import java.util.Optional;

/**
 * On-chain test helper library for building ScriptContext and related types.
 * All methods compile to UPLC and can be used in {@code @Test} methods.
 */
@OnchainLibrary
public class TestContextLib {

    // ---- TxInfo builders ----

    /** Create a TxInfo with all fields set to empty/zero defaults. */
    @SuppressWarnings("unchecked")
    public static TxInfo emptyTxInfo() {
        return new TxInfo(
                (JulcList)(Object) ListsLib.empty(),           // inputs
                (JulcList)(Object) ListsLib.empty(),           // referenceInputs
                (JulcList)(Object) ListsLib.empty(),           // outputs
                BigInteger.ZERO,                               // fee
                Value.zero(),                                  // mint
                (JulcList)(Object) ListsLib.empty(),           // certificates
                (JulcMap)(Object) MapLib.empty(),              // withdrawals
                Interval.always(),                             // validRange
                (JulcList)(Object) ListsLib.empty(),           // signatories
                (JulcMap)(Object) MapLib.empty(),              // redeemers
                (JulcMap)(Object) MapLib.empty(),              // datums
                TxId.of(Builtins.replicateByte(32, 0)),        // id
                (JulcMap)(Object) MapLib.empty(),              // votes
                (JulcList)(Object) ListsLib.empty(),           // proposalProcedures
                Optional.empty(),                              // currentTreasuryAmount
                Optional.empty()                               // treasuryDonation
        );
    }

    // ---- ScriptContext builders ----

    /** Build a spending ScriptContext with the given inputs, outputs, and signatories. */
    @SuppressWarnings("unchecked")
    public static ScriptContext spending(
            TxOutRef ownRef, TxOut ownInput, PlutusData datum,
            PlutusData redeemer, JulcList<TxOut> outputs,
            JulcList<PubKeyHash> signatories) {

        var ownTxIn = new TxInInfo(ownRef, ownInput);
        var inputs = ListsLib.prepend(ListsLib.empty(), (PlutusData)(Object) ownTxIn);

        var txInfo = new TxInfo(
                (JulcList)(Object) inputs,                     // inputs
                (JulcList)(Object) ListsLib.empty(),           // referenceInputs
                outputs,                                       // outputs
                BigInteger.ZERO,                               // fee
                Value.zero(),                                  // mint
                (JulcList)(Object) ListsLib.empty(),           // certificates
                (JulcMap)(Object) MapLib.empty(),              // withdrawals
                Interval.always(),                             // validRange
                signatories,                                   // signatories
                (JulcMap)(Object) MapLib.empty(),              // redeemers
                (JulcMap)(Object) MapLib.empty(),              // datums
                TxId.of(Builtins.replicateByte(32, 0)),        // id
                (JulcMap)(Object) MapLib.empty(),              // votes
                (JulcList)(Object) ListsLib.empty(),           // proposalProcedures
                Optional.empty(),                              // currentTreasuryAmount
                Optional.empty()                               // treasuryDonation
        );

        var scriptInfo = new ScriptInfo.SpendingScript(ownRef, Optional.of(datum));
        return new ScriptContext(txInfo, redeemer, scriptInfo);
    }

    /** Build a minting ScriptContext with the given mint value and outputs. */
    @SuppressWarnings("unchecked")
    public static ScriptContext minting(
            byte[] policyId, PlutusData redeemer,
            Value mint, JulcList<TxOut> outputs) {

        var txInfo = new TxInfo(
                (JulcList)(Object) ListsLib.empty(),           // inputs
                (JulcList)(Object) ListsLib.empty(),           // referenceInputs
                outputs,                                       // outputs
                BigInteger.ZERO,                               // fee
                mint,                                          // mint
                (JulcList)(Object) ListsLib.empty(),           // certificates
                (JulcMap)(Object) MapLib.empty(),              // withdrawals
                Interval.always(),                             // validRange
                (JulcList)(Object) ListsLib.empty(),           // signatories
                (JulcMap)(Object) MapLib.empty(),              // redeemers
                (JulcMap)(Object) MapLib.empty(),              // datums
                TxId.of(Builtins.replicateByte(32, 0)),        // id
                (JulcMap)(Object) MapLib.empty(),              // votes
                (JulcList)(Object) ListsLib.empty(),           // proposalProcedures
                Optional.empty(),                              // currentTreasuryAmount
                Optional.empty()                               // treasuryDonation
        );

        var scriptInfo = new ScriptInfo.MintingScript(PolicyId.of(policyId));
        return new ScriptContext(txInfo, redeemer, scriptInfo);
    }

    // ---- Convenience builders ----

    /** Create a TxOut with an inline datum and lovelace value. */
    public static TxOut txOut(Address addr, BigInteger lovelace, PlutusData datum) {
        return new TxOut(addr, Value.lovelace(lovelace),
                new OutputDatum.OutputDatumInline(datum), Optional.empty());
    }

    /** Create a TxInInfo from a reference and output. */
    public static TxInInfo txInInfo(TxOutRef ref, TxOut output) {
        return new TxInInfo(ref, output);
    }

    /** Create a pub-key address (no staking credential). */
    public static Address pubKeyAddress(byte[] pkh) {
        return new Address(
                new Credential.PubKeyCredential(PubKeyHash.of(pkh)),
                Optional.empty());
    }

    /** Create a script address (no staking credential). */
    public static Address scriptAddress(byte[] scriptHash) {
        return new Address(
                new Credential.ScriptCredential(ScriptHash.of(scriptHash)),
                Optional.empty());
    }
}
