package org.julclang.clientlib.eval;

import org.julclang.core.PlutusData;
import org.julclang.core.types.JulcArrayList;
import org.julclang.core.types.JulcAssocMap;
import org.julclang.core.types.JulcMap;
import org.julclang.clientlib.eval.BadTranslationException.ContextError;
import org.julclang.ledger.*;
import org.julclang.vm.PlutusLanguage;
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
    void v1TxOut_inlineDatum_failsInlineDatumsNotSupported() {
        // V1 has no inline datums: the ledger rejects the transaction (transTxOutV1, Conway/TxInfo.hs:315-317)
        var e = assertThrows(BadTranslationException.class, () -> buildSingleV1TxOut(
                new OutputDatum.OutputDatumInline(new PlutusData.IntData(BigInteger.valueOf(42)))));
        assertEquals(BadTranslationException.ContextError.InlineDatumsNotSupported, e.contextError());
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
        // V1 TxInfo: field 5 = withdrawals, a list of pairs [(StakingCredential, Integer)]
        var wdrl = assertInstanceOf(PlutusData.ListData.class, txInfo.fields().get(5));
        assertEquals(1, wdrl.items().size());
        var pair = expectConstr(wdrl.items().getFirst(), 0);
        assertEquals(new PlutusData.IntData(BigInteger.valueOf(1_000_000)), pair.fields().get(1));

        // Key should be StakingHash(Credential) = Constr 0 [Constr 0 [B hash]]
        var key = expectConstr(pair.fields().getFirst(), 0);
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
        var e = assertThrows(BadTranslationException.class, () ->
                buildContext(PlutusLanguage.PLUTUS_V2, List.of(conwayCert), JulcAssocMap.empty()));
        assertEquals(BadTranslationException.ContextError.CertificateNotSupported, e.contextError());
    }

    @Test
    void dcert_delegStaking_nonPoolDelegatee_throwUnsupported() {
        var cert = new TxCert.DelegStaking(PK_CRED,
                new Delegatee.Vote(new DRep.AlwaysAbstain()));
        var e = assertThrows(BadTranslationException.class, () ->
                buildContext(PlutusLanguage.PLUTUS_V2, List.of(cert), JulcAssocMap.empty()));
        assertEquals(BadTranslationException.ContextError.CertificateNotSupported, e.contextError());
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

    // --- Interval upper bound closure (PV-dependent) ---

    @Test
    void v2_ttlOnly_upperBoundInclusive_pv8() {
        // PV 8 (V2 Babbage): only TTL set → upper bound inclusive (true)
        var converter = TestConverters.of(buildTxWithBounds(0, 1000), dummyUtxoSet(), 8);
        var txInfo = converter.buildTxInfo();
        assertTrue(txInfo.validRange().to().isInclusive(),
                "PV 8 with only TTL → upper bound should be inclusive");
    }

    @Test
    void v2_bothBounds_upperBoundExclusive_pv8() {
        // PV 8 (V2 Babbage): both bounds set → upper bound exclusive (false)
        var converter = TestConverters.of(buildTxWithBounds(500, 1000), dummyUtxoSet(), 8);
        var txInfo = converter.buildTxInfo();
        assertFalse(txInfo.validRange().to().isInclusive(),
                "PV 8 with both bounds → upper bound should be exclusive");
    }

    @Test
    void v3_ttlOnly_upperBoundExclusive_pv10() {
        // PV 10 (V3 Conway+): only TTL set → upper bound exclusive (false)
        var converter = TestConverters.of(buildTxWithBounds(0, 1000), dummyUtxoSet(), 10);
        var txInfo = converter.buildTxInfo();
        assertFalse(txInfo.validRange().to().isInclusive(),
                "PV 10 with only TTL → upper bound should be exclusive");
    }

    @Test
    void v2_ttlOnly_intervalDataEncoding_pv8() {
        // Verify the PlutusData encoding has closure=true for PV8 TTL-only
        var converter = TestConverters.of(buildTxWithBounds(0, 1000), dummyUtxoSet(), 8);
        var txInfo = converter.buildTxInfo();
        PlutusData intervalData = txInfo.validRange().toPlutusData();
        // Interval = Constr 0 [lowerBound, upperBound]
        var intervalConstr = expectConstr(intervalData, 0);
        // upperBound = Constr 0 [boundType, closure]
        var upperBound = expectConstr(intervalConstr.fields().get(1), 0);
        // closure = True (Constr 1 []) for PV 8 TTL-only
        expectConstr(upperBound.fields().get(1), 1); // True = Constr 1
    }

    @Test
    void v3_ttlOnly_intervalDataEncoding_pv10() {
        // Verify the PlutusData encoding has closure=false for PV10 TTL-only
        var converter = TestConverters.of(buildTxWithBounds(0, 1000), dummyUtxoSet(), 10);
        var txInfo = converter.buildTxInfo();
        PlutusData intervalData = txInfo.validRange().toPlutusData();
        var intervalConstr = expectConstr(intervalData, 0);
        var upperBound = expectConstr(intervalConstr.fields().get(1), 0);
        // closure = False (Constr 0 []) for PV 10 TTL-only
        expectConstr(upperBound.fields().get(1), 0); // False = Constr 0
    }

    private static final String DUMMY_ADDR =
            "addr_test1qz2fxv2umyhttkxyxp8x0dlpdt3k6cwng5pxj3jhsydzer3jcu5d8ps7zex2k2xt3uqxgjqnnj83ws8lhrn648jjxtwq2ytjqp";

    private static java.util.Set<com.bloxbean.cardano.client.api.model.Utxo> dummyUtxoSet() {
        return java.util.Set.of(com.bloxbean.cardano.client.api.model.Utxo.builder()
                .txHash(java.util.HexFormat.of().formatHex(HASH_32))
                .outputIndex(0)
                .address(DUMMY_ADDR)
                .amount(java.util.List.of(com.bloxbean.cardano.client.api.model.Amount
                        .lovelace(BigInteger.valueOf(5_000_000))))
                .build());
    }

    private static com.bloxbean.cardano.client.transaction.spec.Transaction buildTxWithBounds(
            long validityStart, long ttl) {
        return com.bloxbean.cardano.client.transaction.spec.Transaction.builder()
                .body(com.bloxbean.cardano.client.transaction.spec.TransactionBody.builder()
                        .inputs(java.util.List.of(
                                new com.bloxbean.cardano.client.transaction.spec.TransactionInput(
                                        java.util.HexFormat.of().formatHex(HASH_32), 0)))
                        .outputs(java.util.List.of())
                        .fee(BigInteger.valueOf(200_000))
                        .ttl(ttl)
                        .validityStartInterval(validityStart)
                        .build())
                .build();
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

    // --- Translation errors: the ledger's BadTranslation, first in toPlutusTxInfo's order ---

    private static final TxOutRef REF_A = new TxOutRef(new TxId(filled(32, 0x0a)), BigInteger.ZERO);
    private static final TxOutRef REF_B = new TxOutRef(new TxId(filled(32, 0x0b)), BigInteger.ONE);
    private static final TxOut PLAIN = txOut(new OutputDatum.NoOutputDatum(), Optional.empty());
    private static final TxOut INLINE = txOut(new OutputDatum.OutputDatumInline(PlutusData.integer(42)),
            Optional.empty());
    private static final TxOut REF_SCRIPT = txOut(new OutputDatum.NoOutputDatum(), Optional.of(SCRIPT_HASH));
    private static final TxOut INLINE_AND_REF_SCRIPT = txOut(
            new OutputDatum.OutputDatumInline(PlutusData.integer(42)), Optional.of(SCRIPT_HASH));
    private static final TxCert DREP_CERT = new TxCert.RegDRep(PK_CRED, BigInteger.valueOf(500_000_000));
    private static final ScriptPurpose SPEND_A = new ScriptPurpose.Spending(REF_A);

    /** A transaction the V1/V2 contexts can express; each test changes the fields it needs. */
    private record Tx(List<TxInInfo> inputs, List<TxInInfo> referenceInputs, List<TxOut> outputs,
                      List<TxCert> certificates, boolean votes, boolean proposals,
                      Optional<BigInteger> treasury, Optional<BigInteger> donation) {

        static Tx plain() {
            return new Tx(List.of(new TxInInfo(REF_A, PLAIN)), List.of(), List.of(PLAIN), List.of(),
                    false, false, Optional.empty(), Optional.empty());
        }

        Tx inputs(TxInInfo... v) {
            return new Tx(List.of(v), referenceInputs, outputs, certificates, votes, proposals, treasury, donation);
        }

        Tx referenceInputs(TxInInfo... v) {
            return new Tx(inputs, List.of(v), outputs, certificates, votes, proposals, treasury, donation);
        }

        Tx outputs(TxOut... v) {
            return new Tx(inputs, referenceInputs, List.of(v), certificates, votes, proposals, treasury, donation);
        }

        Tx certificates(TxCert... v) {
            return new Tx(inputs, referenceInputs, outputs, List.of(v), votes, proposals, treasury, donation);
        }

        Tx withVotes() {
            return new Tx(inputs, referenceInputs, outputs, certificates, true, proposals, treasury, donation);
        }

        Tx withProposals() {
            return new Tx(inputs, referenceInputs, outputs, certificates, votes, true, treasury, donation);
        }

        Tx treasury(long v) {
            return new Tx(inputs, referenceInputs, outputs, certificates, votes, proposals,
                    Optional.of(BigInteger.valueOf(v)), donation);
        }

        Tx donation(long v) {
            return new Tx(inputs, referenceInputs, outputs, certificates, votes, proposals, treasury,
                    Optional.of(BigInteger.valueOf(v)));
        }

        TxInfo txInfo() {
            var voter = new Voter.DRepVoter(PK_CRED);
            var actionId = new GovernanceActionId(TX_ID, BigInteger.ZERO);
            var proposal = new ProposalProcedure(BigInteger.valueOf(100_000_000_000L), PK_CRED,
                    new GovernanceAction.InfoAction());
            JulcMap<Voter, JulcMap<GovernanceActionId, Vote>> voteMap = votes
                    ? JulcAssocMap.of(voter, JulcAssocMap.of(actionId, new Vote.VoteYes()))
                    : JulcAssocMap.empty();
            return new TxInfo(
                    new JulcArrayList<>(inputs), new JulcArrayList<>(referenceInputs), new JulcArrayList<>(outputs),
                    BigInteger.valueOf(200_000), Value.zero(), new JulcArrayList<>(certificates),
                    JulcAssocMap.empty(), Interval.always(), new JulcArrayList<>(List.of(PKH)),
                    JulcAssocMap.empty(), JulcAssocMap.empty(), TX_ID,
                    voteMap,
                    new JulcArrayList<>(proposals ? List.of(proposal) : List.of()),
                    treasury, donation);
        }
    }

    @Test
    void guard_votingProcedures() {
        assertTranslationError(ContextError.VotingProceduresFieldNotSupported, "1 voter(s)",
                PlutusLanguage.PLUTUS_V1, 10, Tx.plain().withVotes());
        assertTranslationError(ContextError.VotingProceduresFieldNotSupported, "1 voter(s)",
                PlutusLanguage.PLUTUS_V2, 10, Tx.plain().withVotes());
    }

    @Test
    void guard_proposalProcedures() {
        assertTranslationError(ContextError.ProposalProceduresFieldNotSupported, "1 proposal(s)",
                PlutusLanguage.PLUTUS_V2, 10, Tx.plain().withProposals());
    }

    @Test
    void guard_treasuryDonation() {
        assertTranslationError(ContextError.TreasuryDonationFieldNotSupported, "5",
                PlutusLanguage.PLUTUS_V2, 10, Tx.plain().donation(5));
        // treasuryDonation == Coin 0 passes the guard (Conway/TxInfo.hs:374)
        assertDoesNotThrow(() -> build(PlutusLanguage.PLUTUS_V2, 10, Tx.plain().donation(0)));
    }

    @Test
    void guard_currentTreasuryValue() {
        assertTranslationError(ContextError.CurrentTreasuryFieldNotSupported, "7",
                PlutusLanguage.PLUTUS_V1, 10, Tx.plain().treasury(7));
    }

    @Test
    void guard_checksInLedgerOrder() {
        Tx all = Tx.plain().withVotes().withProposals().donation(5).treasury(7);
        assertTranslationError(ContextError.VotingProceduresFieldNotSupported, null, PlutusLanguage.PLUTUS_V2, 10,
                all);
        assertTranslationError(ContextError.ProposalProceduresFieldNotSupported, null, PlutusLanguage.PLUTUS_V2, 10,
                Tx.plain().withProposals().donation(5).treasury(7));
        assertTranslationError(ContextError.TreasuryDonationFieldNotSupported, null, PlutusLanguage.PLUTUS_V2, 10,
                Tx.plain().donation(5).treasury(7));
    }

    @Test
    void v1_inlineDatumInInput() {
        assertTranslationError(ContextError.InlineDatumsNotSupported, "Input: " + "0a".repeat(32) + "#0",
                PlutusLanguage.PLUTUS_V1, 10, Tx.plain().inputs(new TxInInfo(REF_A, INLINE)));
    }

    @Test
    void v1_inlineDatumInReferenceInput() {
        // Conway translates a V1 reference input to check it (Conway/TxInfo.hs:411)
        assertTranslationError(ContextError.InlineDatumsNotSupported, "Input: " + "0b".repeat(32) + "#1",
                PlutusLanguage.PLUTUS_V1, 10, Tx.plain().referenceInputs(new TxInInfo(REF_B, INLINE)));
    }

    @Test
    void v1_inlineDatumInOutput() {
        assertTranslationError(ContextError.InlineDatumsNotSupported, "Output: 1",
                PlutusLanguage.PLUTUS_V1, 10, Tx.plain().outputs(PLAIN, INLINE));
    }

    @Test
    void v1_conway_referenceInputAndReferenceScriptAreLeftOut() {
        // From Conway a V1 context checks reference inputs but leaves them out, and drops reference scripts
        Tx tx = Tx.plain().referenceInputs(new TxInInfo(REF_B, REF_SCRIPT)).outputs(REF_SCRIPT);
        var txInfo = extractTxInfo(build(PlutusLanguage.PLUTUS_V1, 10, tx));
        assertEquals(10, txInfo.fields().size());
        var output = expectConstr(expectList(txInfo.fields().get(1)).items().getFirst(), 0);
        assertEquals(3, output.fields().size());
    }

    @Test
    void v1_babbage_referenceInputs() {
        assertTranslationError(ContextError.ReferenceInputsNotSupported, "0b".repeat(32) + "#1",
                PlutusLanguage.PLUTUS_V1, 8, Tx.plain().referenceInputs(new TxInInfo(REF_B, PLAIN)));
    }

    @Test
    void v1_babbage_referenceScriptInInputAndOutput() {
        assertTranslationError(ContextError.ReferenceScriptsNotSupported, "Input: " + "0a".repeat(32) + "#0",
                PlutusLanguage.PLUTUS_V1, 8, Tx.plain().inputs(new TxInInfo(REF_A, REF_SCRIPT)));
        assertTranslationError(ContextError.ReferenceScriptsNotSupported, "Output: 0",
                PlutusLanguage.PLUTUS_V1, 7, Tx.plain().outputs(REF_SCRIPT));
    }

    @Test
    void v1_babbage_inlineDatum() {
        assertTranslationError(ContextError.InlineDatumsNotSupported, "Output: 0",
                PlutusLanguage.PLUTUS_V1, 8, Tx.plain().outputs(INLINE));
    }

    @Test
    void v2_inlineDatumsReferenceScriptsAndReferenceInputsTranslate() {
        Tx tx = Tx.plain().inputs(new TxInInfo(REF_A, INLINE_AND_REF_SCRIPT))
                .referenceInputs(new TxInInfo(REF_B, INLINE_AND_REF_SCRIPT)).outputs(INLINE_AND_REF_SCRIPT);
        for (int pv : new int[]{8, 10}) {
            var txInfo = extractTxInfo(build(PlutusLanguage.PLUTUS_V2, pv, tx));
            assertEquals(1, expectList(txInfo.fields().get(1)).items().size(), "reference inputs at PV " + pv);
            var output = expectConstr(expectList(txInfo.fields().get(2)).items().getFirst(), 0);
            expectConstr(output.fields().get(2), 2); // OutputDatum
            expectConstr(output.fields().get(3), 0); // Just scriptHash
        }
    }

    @Test
    void certificateNotSupported_forEveryLanguageAndEra() {
        for (var language : List.of(PlutusLanguage.PLUTUS_V1, PlutusLanguage.PLUTUS_V2)) {
            assertTranslationError(ContextError.CertificateNotSupported, null, language, 10,
                    Tx.plain().certificates(new TxCert.RegStaking(PK_CRED, Optional.empty()),
                            new TxCert.RegDeleg(PK_CRED, new Delegatee.Stake(PKH), BigInteger.TWO)));
        }
    }

    @Test
    void plutusPurposeNotSupported_votingAndProposing() {
        var voting = new ScriptPurpose.Voting(new Voter.DRepVoter(SC_CRED));
        var e = assertThrows(BadTranslationException.class, () -> V1V2ScriptContextBuilder.build(
                PlutusLanguage.PLUTUS_V2, Tx.plain().txInfo(), voting, 10));
        assertEquals(ContextError.PlutusPurposeNotSupported, e.contextError());
    }

    @Test
    void order_guardBeforeInputs() {
        assertTranslationError(ContextError.TreasuryDonationFieldNotSupported, null, PlutusLanguage.PLUTUS_V1, 10,
                Tx.plain().donation(1).inputs(new TxInInfo(REF_A, INLINE)));
    }

    @Test
    void order_inputsBeforeReferenceInputs() {
        assertTranslationError(ContextError.InlineDatumsNotSupported, "Input: " + "0a".repeat(32) + "#0",
                PlutusLanguage.PLUTUS_V1, 10,
                Tx.plain().inputs(new TxInInfo(REF_A, INLINE)).referenceInputs(new TxInInfo(REF_B, INLINE)));
    }

    @Test
    void order_referenceInputsBeforeOutputs() {
        assertTranslationError(ContextError.InlineDatumsNotSupported, "Input: " + "0b".repeat(32) + "#1",
                PlutusLanguage.PLUTUS_V1, 10, Tx.plain().referenceInputs(new TxInInfo(REF_B, INLINE)).outputs(INLINE));
    }

    @Test
    void order_outputsBeforeCertificates() {
        assertTranslationError(ContextError.InlineDatumsNotSupported, "Output: 0", PlutusLanguage.PLUTUS_V1, 10,
                Tx.plain().outputs(INLINE).certificates(DREP_CERT));
    }

    @Test
    void order_certificatesBeforePurpose() {
        var voting = new ScriptPurpose.Voting(new Voter.DRepVoter(SC_CRED));
        var e = assertThrows(BadTranslationException.class, () -> V1V2ScriptContextBuilder.build(
                PlutusLanguage.PLUTUS_V2, Tx.plain().certificates(DREP_CERT).txInfo(), voting, 10));
        assertEquals(ContextError.CertificateNotSupported, e.contextError());
    }

    @Test
    void order_babbageReferenceInputsBeforeInputs() {
        assertTranslationError(ContextError.ReferenceInputsNotSupported, null, PlutusLanguage.PLUTUS_V1, 8,
                Tx.plain().inputs(new TxInInfo(REF_A, INLINE)).referenceInputs(new TxInInfo(REF_B, PLAIN)));
    }

    @Test
    void order_perOutput_babbageReferenceScriptFirst_conwayInlineDatumOnly() {
        Tx tx = Tx.plain().outputs(INLINE_AND_REF_SCRIPT);
        assertTranslationError(ContextError.ReferenceScriptsNotSupported, "Output: 0", PlutusLanguage.PLUTUS_V1, 8,
                tx);
        assertTranslationError(ContextError.InlineDatumsNotSupported, "Output: 0", PlutusLanguage.PLUTUS_V1, 9, tx);
    }

    @Test
    void threeArgumentBuildUsesConwayRules() {
        Tx tx = Tx.plain().referenceInputs(new TxInInfo(REF_B, PLAIN));
        assertEquals(build(PlutusLanguage.PLUTUS_V1, 9, tx),
                V1V2ScriptContextBuilder.build(PlutusLanguage.PLUTUS_V1, tx.txInfo(), SPEND_A));
    }

    private static PlutusData build(PlutusLanguage language, int protocolMajor, Tx tx) {
        return V1V2ScriptContextBuilder.build(language, tx.txInfo(), SPEND_A, protocolMajor);
    }

    private static void assertTranslationError(ContextError expected, String detail, PlutusLanguage language,
                                               int protocolMajor, Tx tx) {
        var e = assertThrows(BadTranslationException.class, () -> build(language, protocolMajor, tx));
        assertEquals(expected, e.contextError(), e.getMessage());
        if (detail != null) {
            assertEquals("BadTranslation " + expected + ": " + detail, e.getMessage());
        }
    }

    private static TxOut txOut(OutputDatum datum, Optional<ScriptHash> referenceScript) {
        return new TxOut(new Address(PK_CRED, Optional.empty()), Value.lovelace(BigInteger.valueOf(2_000_000)),
                datum, referenceScript);
    }

    private static byte[] filled(int length, int value) {
        var bytes = new byte[length];
        java.util.Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    // --- Helpers ---

    private PlutusData buildContext(PlutusLanguage language, List<TxCert> certs,
                                    org.julclang.core.types.JulcMap<Credential, BigInteger> withdrawals) {
        var txInfo = buildTxInfo(certs, withdrawals);
        var purpose = new ScriptPurpose.Spending(TX_OUT_REF);
        return V1V2ScriptContextBuilder.build(language, txInfo, purpose);
    }

    private PlutusData buildContextWithPurpose(PlutusLanguage language, ScriptPurpose purpose) {
        var txInfo = buildTxInfo(List.of(), JulcAssocMap.empty());
        return V1V2ScriptContextBuilder.build(language, txInfo, purpose);
    }

    private TxInfo buildTxInfo(List<TxCert> certs,
                               org.julclang.core.types.JulcMap<Credential, BigInteger> withdrawals) {
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
                new ScriptPurpose.Spending(TX_OUT_REF));
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
