package com.bloxbean.cardano.julc.stdlib.lib;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.onchain.annotation.OnchainLibrary;
import com.bloxbean.cardano.julc.onchain.stdlib.Builtins;

import java.math.BigInteger;

/**
 * Script context operations compiled from Java source to UPLC.
 * <p>
 * V3 ScriptContext = Constr(0, [txInfo, redeemer, scriptInfo])
 * TxInfo = Constr(0, [inputs(0), refInputs(1), outputs(2), fee(3), mint(4),
 *   certs(5), withdrawals(6), validRange(7), signatories(8), redeemers(9),
 *   datums(10), txId(11), votes(12), proposalProcs(13), treasury(14), donation(15)])
 */
@OnchainLibrary
public class ContextsLib {

    // =========================================================================
    // ScriptContext field accessors
    // =========================================================================

    /** Extracts the TxInfo (field 0) from a ScriptContext. */
    public static PlutusData.ConstrData getTxInfo(PlutusData.ConstrData ctx) {
        return (PlutusData.ConstrData) Builtins.headList(Builtins.constrFields(ctx));
    }

    /** Extracts the redeemer (field 1) from a ScriptContext. */
    public static PlutusData getRedeemer(PlutusData.ConstrData ctx) {
        var fields = Builtins.constrFields(ctx);
        return Builtins.headList(Builtins.tailList(fields));
    }

    /** Extracts the optional datum from a spending ScriptContext. */
    public static PlutusData getSpendingDatum(PlutusData.ConstrData ctx) {
        var ctxFields = Builtins.constrFields(ctx);
        var scriptInfoData = listIndex(ctxFields, BigInteger.valueOf(2));
        var scriptInfoFields = Builtins.constrFields(scriptInfoData);
        return Builtins.headList(Builtins.tailList(scriptInfoFields));
    }

    // =========================================================================
    // TxInfo field accessors
    // =========================================================================

    /** Extracts the list of inputs (field 0) from a TxInfo. */
    public static PlutusData.ListData txInfoInputs(PlutusData.ConstrData txInfo) {
        var fields = Builtins.constrFields(txInfo);
        var inputsData = Builtins.headList(fields);
        return Builtins.unListData(inputsData);
    }

    /** Extracts the list of outputs (field 2) from a TxInfo. */
    public static PlutusData.ListData txInfoOutputs(PlutusData.ConstrData txInfo) {
        var fields = Builtins.constrFields(txInfo);
        var outputsData = listIndex(fields, BigInteger.valueOf(2));
        return Builtins.unListData(outputsData);
    }

    /** Extracts the signatories list (field 8) from a TxInfo. */
    public static PlutusData.ListData txInfoSignatories(PlutusData.ConstrData txInfo) {
        var fields = Builtins.constrFields(txInfo);
        var sigsData = listIndex(fields, BigInteger.valueOf(8));
        return Builtins.unListData(sigsData);
    }

    /** Extracts the valid range (field 7) from a TxInfo. */
    public static PlutusData.ConstrData txInfoValidRange(PlutusData.ConstrData txInfo) {
        var fields = Builtins.constrFields(txInfo);
        return (PlutusData.ConstrData) listIndex(fields, BigInteger.valueOf(7));
    }

    /** Extracts the mint field (field 4) from a TxInfo as MapData. */
    public static PlutusData.MapData txInfoMint(PlutusData.ConstrData txInfo) {
        var fields = Builtins.constrFields(txInfo);
        return (PlutusData.MapData) listIndex(fields, BigInteger.valueOf(4));
    }

    /** Extracts the fee (field 3) from a TxInfo as Integer. */
    public static BigInteger txInfoFee(PlutusData.ConstrData txInfo) {
        var fields = Builtins.constrFields(txInfo);
        var feeData = listIndex(fields, BigInteger.valueOf(3));
        return Builtins.unIData(feeData);
    }

    /** Extracts the txId (field 11) from a TxInfo. */
    public static PlutusData.ConstrData txInfoId(PlutusData.ConstrData txInfo) {
        var fields = Builtins.constrFields(txInfo);
        return (PlutusData.ConstrData) listIndex(fields, BigInteger.valueOf(11));
    }

    /** Extracts reference inputs (field 1) from TxInfo. */
    public static PlutusData.ListData txInfoRefInputs(PlutusData.ConstrData txInfo) {
        var fields = Builtins.constrFields(txInfo);
        return Builtins.unListData(listIndex(fields, BigInteger.ONE));
    }

    /** Extracts withdrawals map (field 6) from TxInfo. */
    public static PlutusData.MapData txInfoWithdrawals(PlutusData.ConstrData txInfo) {
        var fields = Builtins.constrFields(txInfo);
        return (PlutusData.MapData) listIndex(fields, BigInteger.valueOf(6));
    }

    /** Extracts redeemers map (field 9) from TxInfo. */
    public static PlutusData.MapData txInfoRedeemers(PlutusData.ConstrData txInfo) {
        var fields = Builtins.constrFields(txInfo);
        return (PlutusData.MapData) listIndex(fields, BigInteger.valueOf(9));
    }

    // Note: trace(message) stays as PIR in StdlibRegistry because it uses UPLC Text type.

    // =========================================================================
    // Search/filter operations
    // =========================================================================

    /** Checks whether a given PubKeyHash is in the signatories list. */
    public static boolean signedBy(PlutusData.ConstrData txInfo, PlutusData.BytesData pkh) {
        var sigs = txInfoSignatories(txInfo);
        var target = Builtins.unBData(pkh);
        var found = false;
        PlutusData current = sigs;
        while (!Builtins.nullList(current)) {
            var head = Builtins.headList(current);
            if (Builtins.equalsByteString(Builtins.unBData(head), target)) {
                found = true;
                current = Builtins.mkNilData();
            } else {
                current = Builtins.tailList(current);
            }
        }
        return found;
    }

    /** Finds the own input for a spending validator. Returns Optional TxInInfo. */
    public static PlutusData.ConstrData findOwnInput(PlutusData.ConstrData ctx) {
        var ctxFields = Builtins.constrFields(ctx);
        var scriptInfoData = listIndex(ctxFields, BigInteger.valueOf(2));
        var scriptInfoFields = Builtins.constrFields(scriptInfoData);
        var targetTxOutRef = Builtins.headList(scriptInfoFields);
        var txInfoData = Builtins.headList(ctxFields);
        var txInfoFields = Builtins.constrFields(txInfoData);
        var inputsData = Builtins.headList(txInfoFields);
        var inputsList = Builtins.unListData(inputsData);
        PlutusData.ConstrData result = Builtins.constrData(1, Builtins.mkNilData());
        PlutusData current = inputsList;
        while (!Builtins.nullList(current)) {
            var h = Builtins.headList(current);
            var hFields = Builtins.constrFields(h);
            var inputTxOutRef = Builtins.headList(hFields);
            if (Builtins.equalsData(inputTxOutRef, targetTxOutRef)) {
                var someFields = Builtins.mkCons(h, Builtins.mkNilData());
                result = Builtins.constrData(0, someFields);
                current = Builtins.mkNilData();
            } else {
                current = Builtins.tailList(current);
            }
        }
        return result;
    }

    /** Returns outputs that pay to the same address as the own spending input. */
    public static PlutusData.ListData getContinuingOutputs(PlutusData.ConstrData ctx) {
        var ownInputOpt = findOwnInput(ctx);
        var optFields = Builtins.constrFields(ownInputOpt);
        var ownInput = Builtins.headList(optFields);
        var ownInFields = Builtins.constrFields(ownInput);
        var ownTxOut = listIndex(ownInFields, BigInteger.ONE);
        var ownTxOutFields = Builtins.constrFields(ownTxOut);
        var ownAddress = Builtins.headList(ownTxOutFields);
        var ctxFields = Builtins.constrFields(ctx);
        var txInfoData = (PlutusData.ConstrData) Builtins.headList(ctxFields);
        var outputs = txInfoOutputs(txInfoData);
        var result = Builtins.mkNilData();
        PlutusData current = outputs;
        while (!Builtins.nullList(current)) {
            var out = Builtins.headList(current);
            var outFields = Builtins.constrFields(out);
            var outAddr = Builtins.headList(outFields);
            if (Builtins.equalsData(outAddr, ownAddress)) {
                result = Builtins.mkCons(out, result);
            } else {
                result = result;
            }
            current = Builtins.tailList(current);
        }
        return result;
    }

    /** Searches the txInfo datums map for a datum matching the given hash. Returns Optional. */
    public static PlutusData.ConstrData findDatum(PlutusData.ConstrData txInfo, PlutusData.BytesData hash) {
        var fields = Builtins.constrFields(txInfo);
        var datumsData = listIndex(fields, BigInteger.valueOf(10));
        var datumsPairs = Builtins.unMapData(datumsData);
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
    public static PlutusData.ListData valueSpent(PlutusData.ConstrData txInfo) {
        var inputs = txInfoInputs(txInfo);
        var result = Builtins.mkNilData();
        PlutusData current = inputs;
        while (!Builtins.nullList(current)) {
            var input = Builtins.headList(current);
            var inputFields = Builtins.constrFields(input);
            var txOut = listIndex(inputFields, BigInteger.ONE);
            var txOutFields = Builtins.constrFields(txOut);
            var value = listIndex(txOutFields, BigInteger.ONE);
            result = Builtins.mkCons(value, result);
            current = Builtins.tailList(current);
        }
        return result;
    }

    /** Filters outputs by address and returns their values as a list. */
    public static PlutusData.ListData valuePaid(PlutusData.ConstrData txInfo, PlutusData addr) {
        var outputs = txInfoOutputs(txInfo);
        var result = Builtins.mkNilData();
        PlutusData current = outputs;
        while (!Builtins.nullList(current)) {
            var out = Builtins.headList(current);
            var outFields = Builtins.constrFields(out);
            var outAddr = Builtins.headList(outFields);
            var outValue = listIndex(outFields, BigInteger.ONE);
            if (Builtins.equalsData(outAddr, addr)) {
                result = Builtins.mkCons(outValue, result);
            } else {
                result = result;
            }
            current = Builtins.tailList(current);
        }
        return result;
    }

    /** Extracts the own script hash from the ScriptContext's ScriptInfo.
     *  Minting (tag 0) -> policyId. Spending (tag 1) -> script credential hash. */
    public static PlutusData.BytesData ownHash(PlutusData.ConstrData ctx) {
        var ctxFields = Builtins.constrFields(ctx);
        var scriptInfoData = listIndex(ctxFields, BigInteger.valueOf(2));
        var siTag = Builtins.constrTag(scriptInfoData);
        var siFields = Builtins.constrFields(scriptInfoData);
        if (siTag == 0) {
            return (PlutusData.BytesData) Builtins.headList(siFields);
        } else {
            var ownInputOpt = findOwnInput(ctx);
            var optFields = Builtins.constrFields(ownInputOpt);
            var ownInput = Builtins.headList(optFields);
            var ownInFields = Builtins.constrFields(ownInput);
            var ownTxOut = listIndex(ownInFields, BigInteger.ONE);
            var ownTxOutFields = Builtins.constrFields(ownTxOut);
            var ownAddress = Builtins.headList(ownTxOutFields);
            var addrFields = Builtins.constrFields(ownAddress);
            var credential = Builtins.headList(addrFields);
            var credFields = Builtins.constrFields(credential);
            return (PlutusData.BytesData) Builtins.headList(credFields);
        }
    }

    /** Filters outputs whose address has a ScriptCredential matching the given hash. */
    public static PlutusData.ListData scriptOutputsAt(PlutusData.ConstrData txInfo, PlutusData.BytesData scriptHash) {
        var outputs = txInfoOutputs(txInfo);
        var result = Builtins.mkNilData();
        PlutusData current = outputs;
        while (!Builtins.nullList(current)) {
            var out = Builtins.headList(current);
            var outFields = Builtins.constrFields(out);
            var outAddr = Builtins.headList(outFields);
            var addrFields = Builtins.constrFields(outAddr);
            var credential = Builtins.headList(addrFields);
            var credTag = Builtins.constrTag(credential);
            if (credTag == 1) {
                var credFields = Builtins.constrFields(credential);
                var credHash = Builtins.headList(credFields);
                if (Builtins.equalsData(credHash, scriptHash)) {
                    result = Builtins.mkCons(out, result);
                } else {
                    result = result;
                }
            } else {
                result = result;
            }
            current = Builtins.tailList(current);
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
