package org.julclang.clientlib.eval;

import org.julclang.core.PlutusData;
import org.julclang.core.types.JulcList;
import org.julclang.core.types.JulcMap;
import org.julclang.ledger.*;
import org.julclang.vm.PlutusLanguage;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

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
public final class V1V2ScriptContextBuilder {

    private V1V2ScriptContextBuilder() {}

    /** The first protocol major version of the Conway era. */
    private static final int CONWAY_PROTOCOL_MAJOR = 9;

    /**
     * Build a V1 or V2 ScriptContext as raw PlutusData from a V3 {@link TxInfo}, under the Conway era's rules.
     *
     * @see #build(PlutusLanguage, TxInfo, ScriptPurpose, int)
     */
    public static PlutusData build(PlutusLanguage language, TxInfo txInfo, ScriptPurpose purpose) {
        return build(language, txInfo, purpose, CONWAY_PROTOCOL_MAJOR);
    }

    /**
     * Build a V1 or V2 ScriptContext as raw PlutusData from a V3 {@link TxInfo}.
     * <p>
     * A transaction the ledger cannot translate into this language's context fails with the ledger's error, the
     * first one in the order the era's {@code toPlutusTxInfo} checks them (Conway/TxInfo.hs:404-417 for V1 and
     * :444-458 for V2; Babbage/TxInfo.hs:324-339 and :366-378): the Conway-feature guard, (before Conway) the V1
     * reference inputs, the inputs, the reference inputs, the outputs, the certificates, and then the purposes.
     * Not checked here: {@code TimeTranslationPastHorizon} (the validity interval's slots past the ledger's forecast
     * horizon, which a {@link TxInfo} does not carry), and Byron addresses, which the {@link TxInfo} conversion
     * already rejects ({@code ByronTxOutInContext}) before this guard runs, so for a transaction that also has an
     * error Haskell checks earlier, the Byron error is the one reported (the verdict is the same). A missing input
     * fails earlier still, in the conversion's UTxO lookup (Haskell reports {@code BadInputsUTxO} first).
     *
     * @param language      PLUTUS_V1 or PLUTUS_V2
     * @param txInfo        the V3 TxInfo (fields will be down-converted)
     * @param purpose       the V3 ScriptPurpose (Voting and Proposing do not exist before V3)
     * @param protocolMajor the protocol major version, which selects the era's rules (Babbage before 9)
     * @return the script context as PlutusData
     * @throws BadTranslationException when the ledger cannot translate the transaction for this language
     */
    public static PlutusData build(PlutusLanguage language, TxInfo txInfo, ScriptPurpose purpose,
                                   int protocolMajor) {
        PlutusData txInfoData = buildTxInfoData(language, txInfo, protocolMajor < CONWAY_PROTOCOL_MAJOR);
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
                    // transPlutusPurposeV1V2 (Conway/TxInfo.hs:724-739)
                    throw new BadTranslationException(BadTranslationException.ContextError.PlutusPurposeNotSupported,
                            purpose.toString());
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
    private static PlutusData buildTxInfoData(PlutusLanguage language, TxInfo txInfo, boolean babbage) {
        boolean v1 = language == PlutusLanguage.PLUTUS_V1;
        guardConwayFeatures(txInfo);
        if (v1 && babbage && !txInfo.referenceInputs().isEmpty()) {
            // Babbage/TxInfo.hs:329, before the validity interval
            throw new BadTranslationException(BadTranslationException.ContextError.ReferenceInputsNotSupported,
                    sources(txInfo.referenceInputs()));
        }

        PlutusData inputsData = encodeList(txInfo.inputs(), i -> encodeTxInInfo(language, babbage, i));
        // V1 leaves the reference inputs out, but Conway translates them to check them (Conway/TxInfo.hs:411)
        PlutusData refInputsData = encodeList(txInfo.referenceInputs(), i -> encodeTxInInfo(language, babbage, i));
        var outputs = new ArrayList<PlutusData>();
        long index = 0;
        for (TxOut output : txInfo.outputs()) {
            outputs.add(encodeTxOut(language, babbage, output, "Output: " + index++));
        }
        PlutusData outputsData = new PlutusData.ListData(outputs);
        PlutusData feeData = encodeValue(Value.lovelace(txInfo.fee()));
        PlutusData mintData = encodeMintValue(txInfo.mint());
        // V1/V2: uses DCert encoding (not V3 TxCert)
        PlutusData certsData = encodeList(txInfo.certificates(), V1V2ScriptContextBuilder::encodeDCert);
        // V1/V2: withdrawal keys are StakingCredential (not raw Credential like V3), in plutus-ledger-api's
        // Credential order (PubKeyCredential first), not the ledger's (transWithdrawals, Alonzo/Plutus/TxInfo.hs)
        List<PlutusData.Pair> withdrawals = new ArrayList<>();
        var credentials = new ArrayList<Credential>();
        txInfo.withdrawals().keys().forEach(credentials::add);
        credentials.sort(LedgerOrder.PLUTUS_CREDENTIAL);
        for (Credential credential : credentials) {
            withdrawals.add(new PlutusData.Pair(encodeStakingHash(credential),
                    new PlutusData.IntData(txInfo.withdrawals().get(credential))));
        }
        List<PlutusData.Pair> datums = encodePairs(txInfo.datums(), DatumHash::toPlutusData, d -> d);
        PlutusData validRangeData = txInfo.validRange().toPlutusData();
        PlutusData signatoriesData = encodeList(txInfo.signatories(), PubKeyHash::toPlutusData);
        PlutusData txIdData = encodeTxId(txInfo.id());

        if (v1) {
            // V1: withdrawals and datums are lists of pairs, [(k, v)], not maps (V1/Contexts.hs)
            return new PlutusData.ConstrData(0, List.of(
                    inputsData, outputsData, feeData, mintData, certsData,
                    tupleList(withdrawals), validRangeData, signatoriesData, tupleList(datums), txIdData));
        } else {
            // V2: includes referenceInputs, redeemers map, datums
            PlutusData redeemersData = new PlutusData.MapData(
                    encodePairs(txInfo.redeemers(), V1V2ScriptContextBuilder::buildScriptPurposeData, d -> d));
            return new PlutusData.ConstrData(0, List.of(
                    inputsData, refInputsData, outputsData, feeData, mintData, certsData,
                    new PlutusData.MapData(withdrawals), validRangeData, signatoriesData, redeemersData,
                    new PlutusData.MapData(datums), txIdData));
        }
    }

    /**
     * {@code guardConwayFeaturesForPlutusV1V2} (Conway/TxInfo.hs:352-381). A transaction before Conway has none of
     * these fields.
     */
    private static void guardConwayFeatures(TxInfo txInfo) {
        if (!txInfo.votes().isEmpty()) {
            throw new BadTranslationException(
                    BadTranslationException.ContextError.VotingProceduresFieldNotSupported,
                    txInfo.votes().size() + " voter(s)");
        }
        if (!txInfo.proposalProcedures().isEmpty()) {
            throw new BadTranslationException(
                    BadTranslationException.ContextError.ProposalProceduresFieldNotSupported,
                    txInfo.proposalProcedures().size() + " proposal(s)");
        }
        Optional<BigInteger> donation = txInfo.treasuryDonation().filter(d -> d.signum() != 0);
        if (donation.isPresent()) {
            throw new BadTranslationException(
                    BadTranslationException.ContextError.TreasuryDonationFieldNotSupported,
                    donation.get().toString());
        }
        if (txInfo.currentTreasuryAmount().isPresent()) {
            throw new BadTranslationException(
                    BadTranslationException.ContextError.CurrentTreasuryFieldNotSupported,
                    txInfo.currentTreasuryAmount().get().toString());
        }
    }

    /** {@code transTxInInfoV1}/{@code transTxInInfoV2}: Constr 0 [txOutRef, txOut]. */
    private static PlutusData encodeTxInInfo(PlutusLanguage language, boolean babbage, TxInInfo input) {
        return new PlutusData.ConstrData(0, List.of(encodeTxOutRef(input.outRef()),
                encodeTxOut(language, babbage, input.resolved(), source(input.outRef()))));
    }

    private static PlutusData encodeTxOut(PlutusLanguage language, boolean babbage, TxOut txOut, String source) {
        return language == PlutusLanguage.PLUTUS_V1 ? txOutToDataV1(txOut, babbage, source) : txOutToDataV2(txOut);
    }

    /** The ledger's {@code txOutSourceToText} of an input: {@code Input: <txId>#<index>}. */
    private static String source(TxOutRef ref) {
        return "Input: " + txIn(ref);
    }

    private static String sources(JulcList<TxInInfo> inputs) {
        var refs = new ArrayList<String>();
        for (TxInInfo input : inputs) {
            refs.add(txIn(input.outRef()));
        }
        return String.join(", ", refs);
    }

    /** The ledger's {@code txInToText}: {@code <txId>#<index>}. */
    private static String txIn(TxOutRef ref) {
        return HexFormat.of().formatHex(ref.txId().hash()) + "#" + ref.index();
    }

    /**
     * V1 TxOut = Constr 0 [address, value, maybeDatumHash] — 3 fields.
     * V1 datum is Maybe DatumHash: Nothing=Constr(1,[]), Just=Constr(0,[B hash]).
     * <p>
     * The ledger's {@code transTxOutV1}: an inline datum fails (Conway/TxInfo.hs:306-320), and before Conway a
     * reference script fails first (Babbage/TxInfo.hs:110-126); from Conway a reference script is left out.
     */
    private static PlutusData txOutToDataV1(TxOut txOut, boolean babbage, String source) {
        if (babbage && txOut.referenceScript().isPresent()) {
            throw new BadTranslationException(BadTranslationException.ContextError.ReferenceScriptsNotSupported,
                    source);
        }
        // Convert V3 OutputDatum to V1 Maybe DatumHash
        PlutusData maybeDatumHash = switch (txOut.datum()) {
            case OutputDatum.NoOutputDatum() ->
                    // Nothing = Constr 1 []
                    new PlutusData.ConstrData(1, List.of());
            case OutputDatum.OutputDatumHash(var hash) ->
                    // Just hash = Constr 0 [B hash]
                    new PlutusData.ConstrData(0, List.of(hash.toPlutusData()));
            case OutputDatum.OutputDatumInline _ ->
                    throw new BadTranslationException(BadTranslationException.ContextError.InlineDatumsNotSupported,
                            source);
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
     * Any other certificate fails with {@code CertificateNotSupported} ({@code transTxCertV1V2},
     * Conway/TxInfo.hs:383-397).
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
                    throw new BadTranslationException(BadTranslationException.ContextError.CertificateNotSupported,
                            cert.toString());
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
                    throw new BadTranslationException(BadTranslationException.ContextError.CertificateNotSupported,
                            cert.toString());
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

    /** The entries of a map, in its order, keys and values encoded. */
    private static <K, V> List<PlutusData.Pair> encodePairs(
            JulcMap<K, V> map,
            DataEncoder<K> keyEncoder, DataEncoder<V> valueEncoder) {
        var pairs = new ArrayList<PlutusData.Pair>();
        for (K key : map.keys()) {
            pairs.add(new PlutusData.Pair(keyEncoder.encode(key), valueEncoder.encode(map.get(key))));
        }
        return pairs;
    }

    /** A V1 {@code [(k, v)]}: a list of 2-tuples, each {@code Constr 0 [k, v]}. */
    private static PlutusData tupleList(List<PlutusData.Pair> pairs) {
        var items = new ArrayList<PlutusData>(pairs.size());
        for (PlutusData.Pair pair : pairs) {
            items.add(new PlutusData.ConstrData(0, List.of(pair.key(), pair.value())));
        }
        return new PlutusData.ListData(items);
    }
}
