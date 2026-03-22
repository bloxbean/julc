package com.bloxbean.cardano.julc.clientlib.eval;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.vm.PlutusLanguage;

import java.math.BigInteger;
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
     * Rewarding uses StakingCredential (not raw Credential like V3).
     */
    private static PlutusData buildScriptPurposeData(ScriptPurpose purpose) {
        return switch (purpose) {
            case ScriptPurpose.Minting(var policyId) ->
                    new PlutusData.ConstrData(0, List.of(policyId.toPlutusData()));
            case ScriptPurpose.Spending(var txOutRef) ->
                    new PlutusData.ConstrData(1, List.of(encodeTxOutRef(txOutRef)));
            case ScriptPurpose.Rewarding(var credential) ->
                    // V1/V2: Rewarding uses StakingCredential, not raw Credential
                    new PlutusData.ConstrData(2, List.of(encodeStakingHash(credential)));
            case ScriptPurpose.Certifying(var index, var cert) ->
                    // V1/V2: no index field, uses DCert encoding (not V3 TxCert)
                    new PlutusData.ConstrData(3, List.of(encodeDCert(cert)));
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
        // V1/V2: TxOut encoding differs — V1 has 3 fields, V2 has 4 fields
        DataEncoder<TxOut> txOutEncoder = language == PlutusLanguage.PLUTUS_V1
                ? V1V2ScriptContextBuilder::txOutToDataV1
                : V1V2ScriptContextBuilder::txOutToDataV2;

        PlutusData inputsData = encodeList(txInfo.inputs(), i ->
                new PlutusData.ConstrData(0, List.of(encodeTxOutRef(i.outRef()), txOutEncoder.encode(i.resolved()))));
        PlutusData outputsData = encodeList(txInfo.outputs(), txOutEncoder);
        PlutusData feeData = encodeValue(Value.lovelace(txInfo.fee()));
        PlutusData mintData = encodeMintValue(txInfo.mint());
        // V1/V2: uses DCert encoding (not V3 TxCert)
        PlutusData certsData = encodeList(txInfo.certificates(), V1V2ScriptContextBuilder::encodeDCert);
        // V1/V2: withdrawal keys are StakingCredential (not raw Credential like V3)
        PlutusData withdrawalsData = encodeAssocMap(txInfo.withdrawals(),
                V1V2ScriptContextBuilder::encodeStakingHash, v -> new PlutusData.IntData(v));
        PlutusData validRangeData = txInfo.validRange().toPlutusData();
        PlutusData signatoriesData = encodeList(txInfo.signatories(), PubKeyHash::toPlutusData);
        PlutusData txIdData = encodeTxId(txInfo.id());

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
                    new PlutusData.ConstrData(0, List.of(encodeTxOutRef(i.outRef()), txOutEncoder.encode(i.resolved()))));
            PlutusData redeemersData = encodeRedeemersForV2(txInfo.redeemers());
            PlutusData datumsData = encodeAssocMap(txInfo.datums(),
                    DatumHash::toPlutusData, d -> d);
            return new PlutusData.ConstrData(0, List.of(
                    inputsData, refInputsData, outputsData, feeData, mintData, certsData,
                    withdrawalsData, validRangeData, signatoriesData, redeemersData, datumsData, txIdData));
        }
    }

    /**
     * V1 TxOut = Constr 0 [address, value, maybeDatumHash] — 3 fields.
     * V1 datum is Maybe DatumHash: Nothing=Constr(1,[]), Just=Constr(0,[B hash]).
     */
    private static PlutusData txOutToDataV1(TxOut txOut) {
        // Convert V3 OutputDatum to V1 Maybe DatumHash
        PlutusData maybeDatumHash = switch (txOut.datum()) {
            case OutputDatum.NoOutputDatum() ->
                    // Nothing = Constr 1 []
                    new PlutusData.ConstrData(1, List.of());
            case OutputDatum.OutputDatumHash(var hash) ->
                    // Just hash = Constr 0 [B hash]
                    new PlutusData.ConstrData(0, List.of(hash.toPlutusData()));
            case OutputDatum.OutputDatumInline _ ->
                    // V1 doesn't have inline datums — encode as Nothing
                    new PlutusData.ConstrData(1, List.of());
        };
        return new PlutusData.ConstrData(0, List.of(
                txOut.address().toPlutusData(),
                encodeValue(txOut.value()),
                maybeDatumHash));
    }

    /**
     * V2 TxOut = Constr 0 [address, value, outputDatum, maybeRefScript] — 4 fields.
     */
    private static PlutusData txOutToDataV2(TxOut txOut) {
        return new PlutusData.ConstrData(0, List.of(
                txOut.address().toPlutusData(),
                encodeValue(txOut.value()),
                txOut.datum().toPlutusData(),
                PlutusDataHelper.encodeOptional(txOut.referenceScript(),
                        ScriptHash::toPlutusData)));
    }

    /**
     * Wrap a Credential as StakingCredential.StakingHash for V1/V2 encoding.
     * V1/V2 uses StakingCredential in withdrawals and Rewarding purpose,
     * while V3 uses raw Credential.
     * StakingHash cred = Constr 0 [cred.toPlutusData()]
     */
    private static PlutusData encodeStakingHash(Credential credential) {
        return new PlutusData.ConstrData(0, List.of(credential.toPlutusData()));
    }

    /**
     * Encode a V3 TxCert as a V1/V2 DCert.
     * <p>
     * V1/V2 DCert encoding (7 variants, tags 0-6):
     * <ul>
     *   <li>DCertDelegRegKey   stakingCred         → Constr 0 [stakingCred]</li>
     *   <li>DCertDelegDeRegKey stakingCred         → Constr 1 [stakingCred]</li>
     *   <li>DCertDelegDelegate stakingCred poolId  → Constr 2 [stakingCred, B poolId]</li>
     *   <li>DCertPoolRegister  poolId vrfKey       → Constr 3 [B poolId, B vrfKey]</li>
     *   <li>DCertPoolRetire    poolId epoch        → Constr 4 [B poolId, I epoch]</li>
     *   <li>DCertGenesis                           → Constr 5 []</li>
     *   <li>DCertMir                               → Constr 6 []</li>
     * </ul>
     */
    private static PlutusData encodeDCert(TxCert cert) {
        return switch (cert) {
            case TxCert.RegStaking(var credential, var _deposit) ->
                    // DCertDelegRegKey = Constr 0 [StakingHash(cred)]
                    new PlutusData.ConstrData(0, List.of(encodeStakingHash(credential)));
            case TxCert.UnRegStaking(var credential, var _refund) ->
                    // DCertDelegDeRegKey = Constr 1 [StakingHash(cred)]
                    new PlutusData.ConstrData(1, List.of(encodeStakingHash(credential)));
            case TxCert.DelegStaking(var credential, var delegatee) -> {
                // DCertDelegDelegate = Constr 2 [StakingHash(cred), B poolId]
                // V1/V2 only has pool delegation (not DRep/vote delegation)
                if (delegatee instanceof Delegatee.Stake(var poolId)) {
                    yield new PlutusData.ConstrData(2, List.of(
                            encodeStakingHash(credential), poolId.toPlutusData()));
                } else {
                    throw new UnsupportedOperationException(
                            "V1/V2 DCertDelegDelegate only supports pool delegation (Delegatee.Stake), got: " + delegatee);
                }
            }
            case TxCert.PoolRegister(var poolId, var poolVfr) ->
                    // DCertPoolRegister = Constr 3 [B poolId, B vrfKey]
                    new PlutusData.ConstrData(3, List.of(poolId.toPlutusData(), poolVfr.toPlutusData()));
            case TxCert.PoolRetire(var pubKeyHash, var epoch) ->
                    // DCertPoolRetire = Constr 4 [B poolId, I epoch]
                    new PlutusData.ConstrData(4, List.of(pubKeyHash.toPlutusData(), new PlutusData.IntData(epoch)));
            // Conway governance certs are not available in V1/V2
            case TxCert.RegDeleg _, TxCert.RegDRep _, TxCert.UpdateDRep _,
                 TxCert.UnRegDRep _, TxCert.AuthHotCommittee _, TxCert.ResignColdCommittee _ ->
                    throw new UnsupportedOperationException(
                            "Conway governance certificates are not available in V1/V2 scripts: " + cert);
        };
    }

    /**
     * Encode a Value as PlutusData (assoc map of policyId -> assoc map of tokenName -> amount).
     */
    private static PlutusData encodeValue(Value value) {
        return value.toPlutusData();
    }

    /**
     * Encode the mint value for V1/V2 TxInfo.
     * <p>
     * Per the Cardano ledger, the V1/V2 mint field always includes a zero ADA entry
     * prepended to the actual mint entries. This is for backwards compatibility
     * ("hysterical raisins") — the original MaryValue included ADA in mint, and
     * changing the encoding would break existing scripts.
     * <p>
     * See cardano-ledger: {@code transMintValue m = transCoinToValue zero <> transMultiAsset m}
     */
    private static PlutusData encodeMintValue(Value mint) {
        // Always prepend a zero ADA entry: Map { B"" → Map { B"" → I 0 } }
        var zeroAda = new PlutusData.Pair(
                PlutusData.bytes(new byte[0]),
                new PlutusData.MapData(List.of(
                        new PlutusData.Pair(
                                PlutusData.bytes(new byte[0]),
                                PlutusData.integer(0)))));

        PlutusData.MapData mintMap = mint.toPlutusData();
        var entries = new ArrayList<>(mintMap.entries());

        // Remove any existing ADA entry (shouldn't be there for mint, but be safe)
        entries.removeIf(p -> p.key() instanceof PlutusData.BytesData b && b.value().length == 0);

        // Prepend zero ADA entry
        entries.addFirst(zeroAda);

        return new PlutusData.MapData(entries);
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

    /**
     * Encode TxId as Constr 0 [B hash] for V1/V2 compatibility.
     * PlutusTx uses {@code makeIsDataIndexed ''TxId [('TxId, 0)]} which wraps the
     * hash bytes in a ConstrData, unlike other hash newtypes that use
     * {@code deriving newtype (ToData)} and encode as plain BytesData.
     */
    private static PlutusData encodeTxId(TxId txId) {
        return new PlutusData.ConstrData(0, List.of(txId.toPlutusData()));
    }

    /**
     * Encode TxOutRef with V1/V2 TxId wrapping: Constr 0 [encodeTxId(txId), I index].
     */
    private static PlutusData encodeTxOutRef(TxOutRef ref) {
        return new PlutusData.ConstrData(0, List.of(
                encodeTxId(ref.txId()),
                new PlutusData.IntData(ref.index())));
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
