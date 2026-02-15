package com.bloxbean.cardano.julc.stdlib.lib;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.core.types.JulcMap;
import com.bloxbean.cardano.julc.onchain.annotation.OnchainLibrary;
import com.bloxbean.cardano.julc.onchain.stdlib.Builtins;
import com.bloxbean.cardano.julc.ledger.*;

import java.math.BigInteger;

/**
 * Script context operations compiled from Java source to UPLC.
 * <p>
 * Uses typed records (ScriptContext, TxInfo, ScriptInfo, etc.) for readability.
 * Simple field accessors work both on-chain and off-chain.
 * Complex methods (findOwnInput, getContinuingOutputs, etc.) use casts that
 * are no-ops on-chain but do not execute off-chain.
 */
@OnchainLibrary
@SuppressWarnings("unchecked")
public class ContextsLib {

    // =========================================================================
    // ScriptContext field accessors (off-chain compatible)
    // =========================================================================

    /** Extracts the TxInfo from a ScriptContext. */
    public static TxInfo getTxInfo(ScriptContext ctx) {
        return ctx.txInfo();
    }

    /** Extracts the redeemer from a ScriptContext. */
    public static PlutusData getRedeemer(ScriptContext ctx) {
        return ctx.redeemer();
    }

    // =========================================================================
    // TxInfo field accessors (off-chain compatible)
    // =========================================================================

    /** Extracts the list of inputs from a TxInfo. */
    public static JulcList<TxInInfo> txInfoInputs(TxInfo txInfo) {
        return txInfo.inputs();
    }

    /** Extracts the list of outputs from a TxInfo. */
    public static JulcList<TxOut> txInfoOutputs(TxInfo txInfo) {
        return txInfo.outputs();
    }

    /** Extracts the signatories list from a TxInfo. */
    public static JulcList<PubKeyHash> txInfoSignatories(TxInfo txInfo) {
        return txInfo.signatories();
    }

    /** Extracts the valid range from a TxInfo. */
    public static Interval txInfoValidRange(TxInfo txInfo) {
        return txInfo.validRange();
    }

    /** Extracts the mint field from a TxInfo. */
    public static Value txInfoMint(TxInfo txInfo) {
        return txInfo.mint();
    }

    /** Extracts the fee from a TxInfo. */
    public static BigInteger txInfoFee(TxInfo txInfo) {
        return txInfo.fee();
    }

    /** Extracts the txId from a TxInfo. */
    public static TxId txInfoId(TxInfo txInfo) {
        return txInfo.id();
    }

    /** Extracts reference inputs from TxInfo. */
    public static JulcList<TxInInfo> txInfoRefInputs(TxInfo txInfo) {
        return txInfo.referenceInputs();
    }

    /** Extracts withdrawals map from TxInfo. */
    public static JulcMap<Credential, BigInteger> txInfoWithdrawals(TxInfo txInfo) {
        return txInfo.withdrawals();
    }

    /** Extracts redeemers map from TxInfo. */
    public static JulcMap<ScriptPurpose, PlutusData> txInfoRedeemers(TxInfo txInfo) {
        return txInfo.redeemers();
    }

    // =========================================================================
    // signedBy (off-chain compatible via Builtins.equalsByteString byte[] overload)
    // =========================================================================

    /** Checks whether a given PubKeyHash is in the signatories list. */
    public static boolean signedBy(TxInfo txInfo, byte[] pkh) {
        boolean found = false;
        for (PubKeyHash sig : txInfo.signatories()) {
            if (Builtins.equalsByteString(sig.hash(), pkh)) {
                found = true;
            } else {
                found = found;
            }
        }
        return found;
    }

    // =========================================================================
    // getSpendingDatum (on-chain only — casts for Optional unwrapping)
    // =========================================================================

    /** Extracts the optional datum from a spending ScriptContext.
     *  On-chain: switch on ScriptInfo, unwrap Optional Constr manually.
     *  Off-chain: use ctx.scriptInfo() instanceof SpendingScript ss -> ss.datum().orElse(...) directly. */
    public static PlutusData getSpendingDatum(ScriptContext ctx) {
        return switch (ctx.scriptInfo()) {
            case ScriptInfo.SpendingScript ss -> {
                // datum field is Optional<PlutusData>, encoded as Constr(0, [val]) or Constr(1, [])
                // Cast is no-op on-chain, extracts second field of SpendingScript
                PlutusData optDatum = (PlutusData)(Object) ss.datum();
                if (Builtins.constrTag(optDatum) == 0) {
                    yield Builtins.headList(Builtins.constrFields(optDatum));
                } else {
                    yield Builtins.constrData(0, Builtins.mkNilData());
                }
            }
            default -> Builtins.constrData(0, Builtins.mkNilData());
        };
    }

    // =========================================================================
    // Complex search/filter operations (on-chain only — use casts)
    // =========================================================================

    /** Finds the own input for a spending validator. Returns Optional TxInInfo
     *  encoded as Constr(0, [txInInfo]) for Some, Constr(1, []) for None. */
    public static PlutusData.ConstrData findOwnInput(ScriptContext ctx) {
        return switch (ctx.scriptInfo()) {
            case ScriptInfo.SpendingScript ss -> {
                PlutusData targetRef = (PlutusData)(Object) ss.txOutRef();
                PlutusData.ConstrData result = Builtins.constrData(1, Builtins.mkNilData());
                for (TxInInfo input : ctx.txInfo().inputs()) {
                    PlutusData inputRef = (PlutusData)(Object) input.outRef();
                    if (Builtins.equalsData(inputRef, targetRef)) {
                        var someFields = Builtins.mkCons((PlutusData)(Object) input, Builtins.mkNilData());
                        result = Builtins.constrData(0, someFields);
                    } else {
                        result = result;
                    }
                }
                yield result;
            }
            default -> Builtins.constrData(1, Builtins.mkNilData());
        };
    }

    /** Returns outputs that pay to the same address as the own spending input. */
    public static PlutusData.ListData getContinuingOutputs(ScriptContext ctx) {
        var ownInputOpt = findOwnInput(ctx);
        var optFields = Builtins.constrFields(ownInputOpt);
        var ownInput = Builtins.headList(optFields);
        var ownInFields = Builtins.constrFields(ownInput);
        var ownTxOut = Builtins.headList(Builtins.tailList(ownInFields));
        var ownTxOutFields = Builtins.constrFields(ownTxOut);
        var ownAddress = Builtins.headList(ownTxOutFields);

        PlutusData.ListData result = Builtins.mkNilData();
        for (TxOut out : ctx.txInfo().outputs()) {
            PlutusData outAddr = (PlutusData)(Object) out.address();
            if (Builtins.equalsData(outAddr, ownAddress)) {
                result = Builtins.mkCons((PlutusData)(Object) out, result);
            } else {
                result = result;
            }
        }
        return result;
    }

    /** Searches the txInfo datums map for a datum matching the given hash. Returns Optional. */
    public static PlutusData.ConstrData findDatum(TxInfo txInfo, PlutusData.BytesData hash) {
        PlutusData.MapData datumsMap = (PlutusData.MapData)(Object) txInfo.datums();
        var datumsPairs = Builtins.unMapData(datumsMap);
        PlutusData.ConstrData result = Builtins.constrData(1, Builtins.mkNilData());
        PlutusData current = datumsPairs;
        while (!Builtins.nullList(current)) {
            var pair = Builtins.headList(current);
            if (Builtins.equalsData(Builtins.fstPair(pair), hash)) {
                var someFields = Builtins.mkCons(Builtins.sndPair(pair), Builtins.mkNilData());
                result = Builtins.constrData(0, someFields);
                current = Builtins.mkNilPairData();
            } else {
                current = Builtins.tailList(current);
            }
        }
        return result;
    }

    /** Collects the values of all inputs as a list. */
    public static PlutusData.ListData valueSpent(TxInfo txInfo) {
        PlutusData.ListData result = Builtins.mkNilData();
        for (TxInInfo input : txInfo.inputs()) {
            PlutusData value = (PlutusData)(Object) input.resolved().value();
            result = Builtins.mkCons(value, result);
        }
        return result;
    }

    /** Filters outputs by address and returns their values as a list. */
    public static PlutusData.ListData valuePaid(TxInfo txInfo, PlutusData addr) {
        PlutusData.ListData result = Builtins.mkNilData();
        for (TxOut out : txInfo.outputs()) {
            PlutusData outAddr = (PlutusData)(Object) out.address();
            PlutusData outValue = (PlutusData)(Object) out.value();
            if (Builtins.equalsData(outAddr, addr)) {
                result = Builtins.mkCons(outValue, result);
            } else {
                result = result;
            }
        }
        return result;
    }

    /** Extracts the own script hash from the ScriptContext's ScriptInfo.
     *  Minting (tag 0) -> policyId. Spending (tag 1) -> script credential hash. */
    public static PlutusData.BytesData ownHash(ScriptContext ctx) {
        return switch (ctx.scriptInfo()) {
            case ScriptInfo.MintingScript ms ->
                    (PlutusData.BytesData)(Object) ms.policyId();
            default -> {
                // For spending and other script types, find the own input and extract
                // the script credential hash from its address
                var ownInputOpt = findOwnInput(ctx);
                var optFields = Builtins.constrFields(ownInputOpt);
                var ownInput = Builtins.headList(optFields);
                var ownInFields = Builtins.constrFields(ownInput);
                var ownTxOut = Builtins.headList(Builtins.tailList(ownInFields));
                var ownTxOutFields = Builtins.constrFields(ownTxOut);
                var ownAddress = Builtins.headList(ownTxOutFields);
                var addrFields = Builtins.constrFields(ownAddress);
                var credential = Builtins.headList(addrFields);
                var credFields = Builtins.constrFields(credential);
                yield (PlutusData.BytesData) Builtins.headList(credFields);
            }
        };
    }

    /** Filters outputs whose address has a ScriptCredential matching the given hash. */
    public static PlutusData.ListData scriptOutputsAt(TxInfo txInfo, PlutusData.BytesData scriptHash) {
        PlutusData.ListData result = Builtins.mkNilData();
        for (TxOut out : txInfo.outputs()) {
            PlutusData outAddr = (PlutusData)(Object) out.address();
            var addrFields = Builtins.constrFields(outAddr);
            var credential = Builtins.headList(addrFields);
            var credTag = Builtins.constrTag(credential);
            if (credTag == 1) {
                var credFields = Builtins.constrFields(credential);
                var credHash = Builtins.headList(credFields);
                if (Builtins.equalsData(credHash, scriptHash)) {
                    result = Builtins.mkCons((PlutusData)(Object) out, result);
                } else {
                    result = result;
                }
            } else {
                result = result;
            }
        }
        return result;
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    /** Get the nth element from a list by chaining tailList/headList. */
    public static PlutusData listIndex(PlutusData.ListData list, BigInteger n) {
        var current = list;
        var idx = n;
        while (idx.compareTo(BigInteger.ZERO) > 0) {
            current = Builtins.tailList(current);
            idx = idx.subtract(BigInteger.ONE);
        }
        return Builtins.headList(current);
    }
}
