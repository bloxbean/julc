package com.bloxbean.cardano.julc.clientlib.eval;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.vm.PlutusLanguage;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds V1/V2 ScriptContext as raw PlutusData.
 * <p>
 * V1/V2 ScriptContext has a different structure than V3:
 * <ul>
 *   <li>ScriptContext = Constr 0 [txInfo, scriptPurpose]</li>
 *   <li>ScriptPurpose (not ScriptInfo) — different encoding from V3</li>
 *   <li>TxInfo has fewer fields (no governance)</li>
 * </ul>
 * <p>
 * V1/V2 ScriptPurpose encoding:
 * <ul>
 *   <li>Minting(policyId) = Constr 0 [B policyId]</li>
 *   <li>Spending(txOutRef) = Constr 1 [txOutRef]</li>
 *   <li>Rewarding(cred) = Constr 2 [cred]</li>
 *   <li>Certifying(cert) = Constr 3 [cert] (no index, unlike V3)</li>
 * </ul>
 */
final class V1V2ScriptContextBuilder {

    private V1V2ScriptContextBuilder() {}

    /**
     * Build a V1 or V2 ScriptContext as raw PlutusData.
     *
     * @param language  PLUTUS_V1 or PLUTUS_V2
     * @param txInfo    the V3 TxInfo (fields will be down-converted)
     * @param purpose   the V3 ScriptPurpose
     * @param converter the converter for accessing tx data
     * @return the script context as PlutusData
     */
    static PlutusData build(PlutusLanguage language, TxInfo txInfo,
                            ScriptPurpose purpose, CclTxConverter converter) {
        PlutusData txInfoData = buildTxInfoData(language, txInfo);
        PlutusData scriptPurposeData = buildScriptPurposeData(purpose);
        return new PlutusData.ConstrData(0, List.of(txInfoData, scriptPurposeData));
    }

    /**
     * Build V1/V2 ScriptPurpose as PlutusData.
     * Note: V1/V2 only has 4 purposes (no Voting/Proposing).
     * Certifying has NO index field (unlike V3).
     */
    private static PlutusData buildScriptPurposeData(ScriptPurpose purpose) {
        return switch (purpose) {
            case ScriptPurpose.Minting(var policyId) ->
                    new PlutusData.ConstrData(0, List.of(policyId.toPlutusData()));
            case ScriptPurpose.Spending(var txOutRef) ->
                    new PlutusData.ConstrData(1, List.of(txOutRef.toPlutusData()));
            case ScriptPurpose.Rewarding(var credential) ->
                    new PlutusData.ConstrData(2, List.of(credential.toPlutusData()));
            case ScriptPurpose.Certifying(var index, var cert) ->
                    // V1/V2: no index field
                    new PlutusData.ConstrData(3, List.of(cert.toPlutusData()));
            case ScriptPurpose.Voting _, ScriptPurpose.Proposing _ ->
                    throw new UnsupportedOperationException(
                            "Voting/Proposing purposes are not available in V1/V2 scripts");
        };
    }

    /**
     * Build V1/V2 TxInfo as PlutusData.
     * <p>
     * V1 TxInfo = Constr 0 [inputs, outputs, fee, mint, dcert, wdrl, validRange,
     *                        signatories, datums, id]
     * V2 TxInfo = Constr 0 [inputs, referenceInputs, outputs, fee, mint, dcert, wdrl,
     *                        validRange, signatories, redeemers, datums, id]
     */
    private static PlutusData buildTxInfoData(PlutusLanguage language, TxInfo txInfo) {
        PlutusData inputsData = encodeList(txInfo.inputs(), i ->
                new PlutusData.ConstrData(0, List.of(i.outRef().toPlutusData(), txOutToData(i.resolved()))));
        PlutusData outputsData = encodeList(txInfo.outputs(), V1V2ScriptContextBuilder::txOutToData);
        PlutusData feeData = encodeValue(Value.lovelace(txInfo.fee()));
        PlutusData mintData = encodeValue(txInfo.mint());
        PlutusData certsData = encodeList(txInfo.certificates(), cert -> cert.toPlutusData());
        PlutusData withdrawalsData = encodeAssocMap(txInfo.withdrawals(),
                Credential::toPlutusData, v -> new PlutusData.IntData(v));
        PlutusData validRangeData = txInfo.validRange().toPlutusData();
        PlutusData signatoriesData = encodeList(txInfo.signatories(), PubKeyHash::toPlutusData);
        PlutusData txIdData = txInfo.id().toPlutusData();

        if (language == PlutusLanguage.PLUTUS_V1) {
            // V1: datums as assoc list (hash -> data)
            PlutusData datumsData = encodeAssocMap(txInfo.datums(),
                    DatumHash::toPlutusData, d -> d);
            return new PlutusData.ConstrData(0, List.of(
                    inputsData, outputsData, feeData, mintData, certsData,
                    withdrawalsData, validRangeData, signatoriesData, datumsData, txIdData));
        } else {
            // V2: includes referenceInputs, redeemers map, datums
            PlutusData refInputsData = encodeList(txInfo.referenceInputs(), i ->
                    new PlutusData.ConstrData(0, List.of(i.outRef().toPlutusData(), txOutToData(i.resolved()))));
            PlutusData redeemersData = encodeRedeemersForV2(txInfo.redeemers());
            PlutusData datumsData = encodeAssocMap(txInfo.datums(),
                    DatumHash::toPlutusData, d -> d);
            return new PlutusData.ConstrData(0, List.of(
                    inputsData, refInputsData, outputsData, feeData, mintData, certsData,
                    withdrawalsData, validRangeData, signatoriesData, redeemersData, datumsData, txIdData));
        }
    }

    private static PlutusData txOutToData(TxOut txOut) {
        return new PlutusData.ConstrData(0, List.of(
                txOut.address().toPlutusData(),
                encodeValue(txOut.value()),
                txOut.datum().toPlutusData(),
                PlutusDataHelper.encodeOptional(txOut.referenceScript(),
                        ScriptHash::toPlutusData)));
    }

    /**
     * Encode a Value as PlutusData (assoc map of policyId -> assoc map of tokenName -> amount).
     */
    private static PlutusData encodeValue(Value value) {
        return value.toPlutusData();
    }

    /**
     * Convert the V3 redeemers map (ScriptPurpose -> Data) to V2 format,
     * re-encoding ScriptPurpose keys to V1/V2 encoding.
     */
    private static PlutusData encodeRedeemersForV2(
            com.bloxbean.cardano.julc.core.types.JulcMap<ScriptPurpose, PlutusData> redeemers) {
        var pairs = new ArrayList<PlutusData.Pair>();
        var keys = redeemers.keys();
        for (long i = 0; i < redeemers.size(); i++) {
            ScriptPurpose sp = keys.get(i);
            PlutusData redeemerData = redeemers.get(sp);
            // Re-encode the ScriptPurpose using V1/V2 encoding
            pairs.add(new PlutusData.Pair(buildScriptPurposeData(sp), redeemerData));
        }
        return new PlutusData.MapData(pairs);
    }

    @FunctionalInterface
    private interface DataEncoder<T> {
        PlutusData encode(T value);
    }

    private static <T> PlutusData encodeList(Iterable<T> items, DataEncoder<T> encoder) {
        var encoded = new ArrayList<PlutusData>();
        for (T item : items) {
            encoded.add(encoder.encode(item));
        }
        return new PlutusData.ListData(encoded);
    }

    private static <K, V> PlutusData encodeAssocMap(
            com.bloxbean.cardano.julc.core.types.JulcMap<K, V> map,
            DataEncoder<K> keyEncoder, DataEncoder<V> valueEncoder) {
        var pairs = new ArrayList<PlutusData.Pair>();
        var keys = map.keys();
        for (long i = 0; i < map.size(); i++) {
            K key = keys.get(i);
            V value = map.get(key);
            pairs.add(new PlutusData.Pair(
                    keyEncoder.encode(key),
                    valueEncoder.encode(value)));
        }
        return new PlutusData.MapData(pairs);
    }
}
