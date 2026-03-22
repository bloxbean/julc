package com.bloxbean.cardano.julc.clientlib.eval;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.types.JulcArrayList;
import com.bloxbean.cardano.julc.core.types.JulcAssocMap;
import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.vm.PlutusLanguage;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Conformance tests for V1/V2 ScriptContext encoding.
 * Verifies that the PlutusData tree matches the Haskell PlutusTx encoding spec.
 */
class V1V2EncodingConformanceTest {

    private static final byte[] HASH_28 = new byte[28];
    private static final byte[] HASH_32 = new byte[32];
    private static final TxId TX_ID = new TxId(HASH_32);
    private static final TxOutRef TX_OUT_REF = new TxOutRef(TX_ID, BigInteger.ZERO);
    private static final PubKeyHash PKH = new PubKeyHash(HASH_28);
    private static final ScriptHash SCRIPT_HASH = new ScriptHash(HASH_28);
    private static final Credential PK_CRED = new Credential.PubKeyCredential(PKH);
    private static final Credential SC_CRED = new Credential.ScriptCredential(SCRIPT_HASH);

    // --- V1 TxOut encoding: 3 fields ---

    @Test
    void v1TxOut_has3Fields_noOutputDatum() {
        var ctx = buildContext(PlutusLanguage.PLUTUS_V1, List.of(), JulcAssocMap.empty());
        var txInfo = extractTxInfo(ctx);
        // V1 TxInfo: field 1 = outputs
        var outputs = expectList(txInfo.fields().get(1));
        var txOut = expectConstr(outputs.items().getFirst(), 0);
        assertEquals(3, txOut.fields().size(), "V1 TxOut should have exactly 3 fields");
    }

    @Test
    void v1TxOut_noDatum_encodedAsNothing() {
        var txOut = buildSingleV1TxOut(new OutputDatum.NoOutputDatum());
        // Field 2 = maybeDatumHash
        var maybeDatum = expectConstr(txOut.fields().get(2), 1); // Nothing = Constr 1 []
        assertTrue(maybeDatum.fields().isEmpty(), "Nothing should have no fields");
    }

    @Test
    void v1TxOut_datumHash_encodedAsJust() {
        byte[] datumHashBytes = "abcdefgh01234567abcdefgh01234567".getBytes();
        var txOut = buildSingleV1TxOut(new OutputDatum.OutputDatumHash(new DatumHash(datumHashBytes)));
        // Field 2 = Just(hash) = Constr 0 [B hash]
        var justDatum = expectConstr(txOut.fields().get(2), 0);
        assertEquals(1, justDatum.fields().size());
        assertInstanceOf(PlutusData.BytesData.class, justDatum.fields().getFirst());
    }

    @Test
    void v1TxOut_inlineDatum_encodedAsNothing() {
        // V1 doesn't support inline datums — should degrade to Nothing
        var txOut = buildSingleV1TxOut(new OutputDatum.OutputDatumInline(
                new PlutusData.IntData(BigInteger.valueOf(42))));
        var maybeDatum = expectConstr(txOut.fields().get(2), 1); // Nothing
        assertTrue(maybeDatum.fields().isEmpty());
    }

    @Test
    void v1TxOut_noReferenceScript_field() {
        // V1 TxOut should NOT have a 4th field (referenceScript)
        var txOut = buildSingleV1TxOut(new OutputDatum.NoOutputDatum());
        assertEquals(3, txOut.fields().size(), "V1 TxOut must have exactly 3 fields (no referenceScript)");
    }

    // --- V2 TxOut encoding: 4 fields ---

    @Test
    void v2TxOut_has4Fields() {
        var ctx = buildContext(PlutusLanguage.PLUTUS_V2, List.of(), JulcAssocMap.empty());
        var txInfo = extractTxInfo(ctx);
        // V2 TxInfo: field 2 = outputs (after refInputs)
        var outputs = expectList(txInfo.fields().get(2));
        var txOut = expectConstr(outputs.items().getFirst(), 0);
        assertEquals(4, txOut.fields().size(), "V2 TxOut should have exactly 4 fields");
    }

    @Test
    void v2TxOut_outputDatum_uses3VariantEncoding() {
        var ctx = buildContext(PlutusLanguage.PLUTUS_V2, List.of(), JulcAssocMap.empty());
        var txInfo = extractTxInfo(ctx);
        var outputs = expectList(txInfo.fields().get(2));
        var txOut = expectConstr(outputs.items().getFirst(), 0);
        // Field 2 = OutputDatum (NoOutputDatum = Constr 0 [])
        var outputDatum = expectConstr(txOut.fields().get(2), 0);
        assertTrue(outputDatum.fields().isEmpty());
    }

    // --- Withdrawal key encoding: StakingCredential ---

    @Test
    void v1_withdrawalKeys_wrappedAsStakingHash() {
        var withdrawals = JulcAssocMap.<Credential, BigInteger>empty()
                .insert(PK_CRED, BigInteger.valueOf(1_000_000));

        var ctx = buildContext(PlutusLanguage.PLUTUS_V1, List.of(), withdrawals);
        var txInfo = extractTxInfo(ctx);
        // V1 TxInfo: field 5 = withdrawals
        var wdrlMap = expectMap(txInfo.fields().get(5));
        assertEquals(1, wdrlMap.entries().size());

        // Key should be StakingHash(Credential) = Constr 0 [Constr 0 [B hash]]
        var key = expectConstr(wdrlMap.entries().getFirst().key(), 0);
        // Inner = PubKeyCredential = Constr 0 [B hash]
        var innerCred = expectConstr(key.fields().getFirst(), 0);
        assertInstanceOf(PlutusData.BytesData.class, innerCred.fields().getFirst());
    }

    @Test
    void v2_withdrawalKeys_wrappedAsStakingHash() {
        var withdrawals = JulcAssocMap.<Credential, BigInteger>empty()
                .insert(SC_CRED, BigInteger.valueOf(500_000));

        var ctx = buildContext(PlutusLanguage.PLUTUS_V2, List.of(), withdrawals);
        var txInfo = extractTxInfo(ctx);
        // V2 TxInfo: field 6 = withdrawals
        var wdrlMap = expectMap(txInfo.fields().get(6));
        assertEquals(1, wdrlMap.entries().size());

        // Key = StakingHash(ScriptCredential) = Constr 0 [Constr 1 [B hash]]
        var stakingHash = expectConstr(wdrlMap.entries().getFirst().key(), 0);
        var scriptCred = expectConstr(stakingHash.fields().getFirst(), 1);
        assertInstanceOf(PlutusData.BytesData.class, scriptCred.fields().getFirst());
    }

    // --- DCert encoding ---

    @Test
    void dcert_regStaking_encodedAsTag0_withStakingHash() {
        var cert = new TxCert.RegStaking(PK_CRED, Optional.of(BigInteger.valueOf(2_000_000)));
        var ctx = buildContext(PlutusLanguage.PLUTUS_V2, List.of(cert), JulcAssocMap.empty());
        var txInfo = extractTxInfo(ctx);
        // V2 TxInfo: field 5 = dcerts
        var certs = expectList(txInfo.fields().get(5));
        var dcert = expectConstr(certs.items().getFirst(), 0); // DCertDelegRegKey = tag 0
        assertEquals(1, dcert.fields().size(), "DCertDelegRegKey has 1 field (StakingHash)");
        // StakingHash wrap
        var stakingHash = expectConstr(dcert.fields().getFirst(), 0);
        expectConstr(stakingHash.fields().getFirst(), 0); // PubKeyCredential
    }

    @Test
    void dcert_unRegStaking_encodedAsTag1() {
        var cert = new TxCert.UnRegStaking(SC_CRED, Optional.empty());
        var ctx = buildContext(PlutusLanguage.PLUTUS_V1, List.of(cert), JulcAssocMap.empty());
        var txInfo = extractTxInfo(ctx);
        // V1 TxInfo: field 4 = dcerts
        var certs = expectList(txInfo.fields().get(4));
        var dcert = expectConstr(certs.items().getFirst(), 1); // DCertDelegDeRegKey = tag 1
        assertEquals(1, dcert.fields().size());
    }

    @Test
    void dcert_delegStaking_encodedAsTag2_withPoolId() {
        var poolId = new PubKeyHash("aa".repeat(28).getBytes().length == 56
                ? hexToBytes("aa".repeat(28)) : HASH_28);
        var cert = new TxCert.DelegStaking(PK_CRED, new Delegatee.Stake(poolId));
        var ctx = buildContext(PlutusLanguage.PLUTUS_V2, List.of(cert), JulcAssocMap.empty());
        var txInfo = extractTxInfo(ctx);
        var certs = expectList(txInfo.fields().get(5));
        var dcert = expectConstr(certs.items().getFirst(), 2); // DCertDelegDelegate = tag 2
        assertEquals(2, dcert.fields().size(), "DCertDelegDelegate has 2 fields (StakingHash, poolId)");
        // First field = StakingHash
        expectConstr(dcert.fields().get(0), 0);
        // Second field = B poolId (raw bytes)
        assertInstanceOf(PlutusData.BytesData.class, dcert.fields().get(1));
    }

    @Test
    void dcert_poolRegister_encodedAsTag3() {
        var poolId = new PubKeyHash(HASH_28);
        var vrfKey = new PubKeyHash(HASH_28);
        var cert = new TxCert.PoolRegister(poolId, vrfKey);
        var ctx = buildContext(PlutusLanguage.PLUTUS_V2, List.of(cert), JulcAssocMap.empty());
        var txInfo = extractTxInfo(ctx);
        var certs = expectList(txInfo.fields().get(5));
        var dcert = expectConstr(certs.items().getFirst(), 3); // DCertPoolRegister = tag 3
        assertEquals(2, dcert.fields().size());
        assertInstanceOf(PlutusData.BytesData.class, dcert.fields().get(0));
        assertInstanceOf(PlutusData.BytesData.class, dcert.fields().get(1));
    }

    @Test
    void dcert_poolRetire_encodedAsTag4() {
        var cert = new TxCert.PoolRetire(PKH, BigInteger.valueOf(100));
        var ctx = buildContext(PlutusLanguage.PLUTUS_V2, List.of(cert), JulcAssocMap.empty());
        var txInfo = extractTxInfo(ctx);
        var certs = expectList(txInfo.fields().get(5));
        var dcert = expectConstr(certs.items().getFirst(), 4); // DCertPoolRetire = tag 4
        assertEquals(2, dcert.fields().size());
        assertInstanceOf(PlutusData.BytesData.class, dcert.fields().get(0));
        assertEquals(BigInteger.valueOf(100), ((PlutusData.IntData) dcert.fields().get(1)).value());
    }

    @Test
    void dcert_conwayCerts_throwUnsupported() {
        var conwayCert = new TxCert.RegDRep(PK_CRED, BigInteger.valueOf(500_000_000));
        assertThrows(UnsupportedOperationException.class, () ->
                buildContext(PlutusLanguage.PLUTUS_V2, List.of(conwayCert), JulcAssocMap.empty()));
    }

    @Test
    void dcert_delegStaking_nonPoolDelegatee_throwUnsupported() {
        var cert = new TxCert.DelegStaking(PK_CRED,
                new Delegatee.Vote(new DRep.AlwaysAbstain()));
        assertThrows(UnsupportedOperationException.class, () ->
                buildContext(PlutusLanguage.PLUTUS_V2, List.of(cert), JulcAssocMap.empty()));
    }

    // --- ScriptPurpose encoding ---

    @Test
    void scriptPurpose_rewarding_wrapsCredentialAsStakingHash() {
        var purpose = new ScriptPurpose.Rewarding(PK_CRED);
        var ctx = buildContextWithPurpose(PlutusLanguage.PLUTUS_V2, purpose);
        var scriptPurpose = expectConstr(((PlutusData.ConstrData) ctx).fields().get(1), 2);
        // Single field = StakingHash(Credential) = Constr 0 [Credential]
        var stakingHash = expectConstr(scriptPurpose.fields().getFirst(), 0);
        expectConstr(stakingHash.fields().getFirst(), 0); // PubKeyCredential
    }

    @Test
    void scriptPurpose_certifying_usesDCertEncoding() {
        var cert = new TxCert.RegStaking(SC_CRED, Optional.empty());
        var purpose = new ScriptPurpose.Certifying(BigInteger.ZERO, cert);
        var ctx = buildContextWithPurpose(PlutusLanguage.PLUTUS_V2, purpose);
        var scriptPurpose = expectConstr(((PlutusData.ConstrData) ctx).fields().get(1), 3);
        // Single field = DCert (no index in V1/V2)
        assertEquals(1, scriptPurpose.fields().size(), "V1/V2 Certifying has no index field");
        // DCertDelegRegKey = Constr 0 [StakingHash]
        var dcert = expectConstr(scriptPurpose.fields().getFirst(), 0);
        expectConstr(dcert.fields().getFirst(), 0); // StakingHash
    }

    // --- V1 vs V2 TxInfo field count ---

    @Test
    void v1TxInfo_has10Fields() {
        var ctx = buildContext(PlutusLanguage.PLUTUS_V1, List.of(), JulcAssocMap.empty());
        var txInfo = extractTxInfo(ctx);
        assertEquals(10, txInfo.fields().size(),
                "V1 TxInfo should have 10 fields: inputs, outputs, fee, mint, dcert, wdrl, validRange, signatories, datums, id");
    }

    @Test
    void v2TxInfo_has12Fields() {
        var ctx = buildContext(PlutusLanguage.PLUTUS_V2, List.of(), JulcAssocMap.empty());
        var txInfo = extractTxInfo(ctx);
        assertEquals(12, txInfo.fields().size(),
                "V2 TxInfo should have 12 fields: inputs, refInputs, outputs, fee, mint, dcert, wdrl, validRange, signatories, redeemers, datums, id");
    }

    // --- V1 input TxOut also uses 3-field encoding ---

    @Test
    void v1Input_resolvedTxOut_has3Fields() {
        var ctx = buildContext(PlutusLanguage.PLUTUS_V1, List.of(), JulcAssocMap.empty());
        var txInfo = extractTxInfo(ctx);
        // V1 TxInfo: field 0 = inputs
        var inputs = expectList(txInfo.fields().get(0));
        var txInInfo = expectConstr(inputs.items().getFirst(), 0);
        // Field 1 of TxInInfo = resolved TxOut
        var resolvedTxOut = expectConstr(txInInfo.fields().get(1), 0);
        assertEquals(3, resolvedTxOut.fields().size(), "V1 resolved TxOut in inputs should also have 3 fields");
    }

    // --- Helpers ---

    private PlutusData buildContext(PlutusLanguage language, List<TxCert> certs,
                                    com.bloxbean.cardano.julc.core.types.JulcMap<Credential, BigInteger> withdrawals) {
        var txInfo = buildTxInfo(certs, withdrawals);
        var purpose = new ScriptPurpose.Spending(TX_OUT_REF);
        return V1V2ScriptContextBuilder.build(language, txInfo, purpose, null);
    }

    private PlutusData buildContextWithPurpose(PlutusLanguage language, ScriptPurpose purpose) {
        var txInfo = buildTxInfo(List.of(), JulcAssocMap.empty());
        return V1V2ScriptContextBuilder.build(language, txInfo, purpose, null);
    }

    private TxInfo buildTxInfo(List<TxCert> certs,
                               com.bloxbean.cardano.julc.core.types.JulcMap<Credential, BigInteger> withdrawals) {
        var address = new Address(PK_CRED, Optional.empty());
        var txOut = new TxOut(address, Value.lovelace(BigInteger.valueOf(5_000_000)),
                new OutputDatum.NoOutputDatum(), Optional.empty());
        var txInInfo = new TxInInfo(TX_OUT_REF, txOut);

        return new TxInfo(
                new JulcArrayList<>(List.of(txInInfo)),     // inputs
                new JulcArrayList<>(List.of()),             // referenceInputs
                new JulcArrayList<>(List.of(txOut)),        // outputs
                BigInteger.valueOf(200_000),                // fee
                Value.zero(),                              // mint
                new JulcArrayList<>(certs),                 // certificates
                withdrawals,                                // withdrawals
                Interval.always(),                          // validRange
                new JulcArrayList<>(List.of(PKH)),          // signatories
                JulcAssocMap.empty(),                       // redeemers
                JulcAssocMap.empty(),                       // datums
                TX_ID,                                      // id
                JulcAssocMap.empty(),                       // votes
                new JulcArrayList<>(List.of()),             // proposalProcedures
                Optional.empty(),                           // currentTreasuryAmount
                Optional.empty()                            // treasuryDonation
        );
    }

    private PlutusData.ConstrData buildSingleV1TxOut(OutputDatum datum) {
        var address = new Address(PK_CRED, Optional.empty());
        var txOut = new TxOut(address, Value.lovelace(BigInteger.valueOf(1_000_000)),
                datum, Optional.empty());
        var txInInfo = new TxInInfo(TX_OUT_REF, txOut);

        var txInfo = new TxInfo(
                new JulcArrayList<>(List.of(txInInfo)),
                new JulcArrayList<>(List.of()),
                new JulcArrayList<>(List.of(txOut)),
                BigInteger.valueOf(200_000),
                Value.zero(),
                new JulcArrayList<>(List.of()),
                JulcAssocMap.empty(),
                Interval.always(),
                new JulcArrayList<>(List.of(PKH)),
                JulcAssocMap.empty(),
                JulcAssocMap.empty(),
                TX_ID,
                JulcAssocMap.empty(),
                new JulcArrayList<>(List.of()),
                Optional.empty(),
                Optional.empty()
        );

        var ctx = V1V2ScriptContextBuilder.build(PlutusLanguage.PLUTUS_V1, txInfo,
                new ScriptPurpose.Spending(TX_OUT_REF), null);
        var ctxConstr = extractTxInfo(ctx);
        // V1 TxInfo: field 1 = outputs
        var outputs = expectList(ctxConstr.fields().get(1));
        return expectConstr(outputs.items().getFirst(), 0);
    }

    private PlutusData.ConstrData extractTxInfo(PlutusData ctx) {
        var ctxConstr = expectConstr(ctx, 0);
        return expectConstr(ctxConstr.fields().get(0), 0);
    }

    private static PlutusData.ConstrData expectConstr(PlutusData data, int expectedTag) {
        assertInstanceOf(PlutusData.ConstrData.class, data, "Expected ConstrData but got: " + data.getClass().getSimpleName());
        var c = (PlutusData.ConstrData) data;
        assertEquals(expectedTag, c.tag(), "Expected Constr tag " + expectedTag + " but got " + c.tag());
        return c;
    }

    private static PlutusData.ListData expectList(PlutusData data) {
        assertInstanceOf(PlutusData.ListData.class, data, "Expected ListData but got: " + data.getClass().getSimpleName());
        return (PlutusData.ListData) data;
    }

    private static PlutusData.MapData expectMap(PlutusData data) {
        assertInstanceOf(PlutusData.MapData.class, data, "Expected MapData but got: " + data.getClass().getSimpleName());
        return (PlutusData.MapData) data;
    }

    private static byte[] hexToBytes(String hex) {
        return java.util.HexFormat.of().parseHex(hex);
    }
}
