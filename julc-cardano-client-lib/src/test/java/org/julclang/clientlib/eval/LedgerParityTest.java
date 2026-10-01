package org.julclang.clientlib.eval;

import com.bloxbean.cardano.client.api.ProtocolParamsSupplier;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.common.OrderEnum;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
import com.bloxbean.cardano.client.plutus.spec.PlutusV2Script;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import org.julclang.clientlib.JulcScriptAdapter;
import org.julclang.core.Constant;
import org.julclang.core.PlutusData;
import org.julclang.core.Program;
import org.julclang.core.Term;
import org.julclang.ledger.*;
import org.julclang.vm.PlutusLanguage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The script context matches cardano-ledger ({@code f649f975}, {@code Conway/TxInfo.hs} and the Alonzo/Babbage
 * helpers it calls) and plutus-ledger-api 1.65. One test per difference in bloxbean/julc#220. The transactions are
 * written as raw CBOR so that the encoding the ledger hashes, and the key order the ledger sorts, are under the
 * test's control.
 */
class LedgerParityTest {

    private static final String INPUT_TX = "aa".repeat(32);
    private static final String INPUTS = "00d9010281825820" + INPUT_TX + "00";
    private static final String FEE = "021a00030d40";
    private static final String NO_OUTPUTS = "0180";
    private static final String NO_WITNESSES = "a10580"; // CCL leaves an empty witness map null
    private static final String ANCHOR = "82605820" + "00".repeat(32);

    // --- helpers ---

    private static String h28(String b) {
        return "581c" + b.repeat(28);
    }

    private static String account(String header, String hash) {
        return "581d" + header + hash;
    }

    private static String key(String b) {
        return "8200" + h28(b);
    }

    private static String script(String b) {
        return "8201" + h28(b);
    }

    /** {@code [body, witnesses, true, null]}; the body is a map of {@code entries} fields. */
    private static byte[] tx(int entries, String bodyFields, String witnesses) {
        return HexFormat.of().parseHex("84" + mapHeader(entries) + bodyFields + witnesses + "f5f6");
    }

    private static String mapHeader(int entries) {
        return Integer.toHexString(0xa0 + entries);
    }

    private static CclTxConverter converter(byte[] cbor, int pv) throws Exception {
        return new CclTxConverter(Transaction.deserialize(cbor), cbor, Set.of(inputUtxo(null)), null, null, pv);
    }

    private static Utxo inputUtxo(String address) {
        return Utxo.builder()
                .txHash(INPUT_TX).outputIndex(0)
                .address(address != null ? address : com.bloxbean.cardano.client.address.AddressProvider.getEntAddress(
                        com.bloxbean.cardano.client.address.Credential.fromKey(new byte[28]),
                        com.bloxbean.cardano.client.common.model.Networks.testnet()).toBech32())
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(5_000_000))))
                .build();
    }

    private static byte[] b(String hex) {
        return HexFormat.of().parseHex(hex);
    }

    private static List<PlutusData> keysOf(PlutusData map) {
        var keys = new ArrayList<PlutusData>();
        for (var pair : ((PlutusData.MapData) map).entries()) {
            keys.add(pair.key());
        }
        return keys;
    }

    private static PlutusData bytes(String hex) {
        return new PlutusData.BytesData(b(hex));
    }

    // --- 1. TxId and witness-datum hashes from the original bytes ---

    /** The witness datum set untagged, and tagged 258 with a 2, 4 and 8 byte argument: the ledger reads them all. */
    static Stream<String> setTags() {
        return Stream.of("", "d90102", "da00000102", "db0000000000000102");
    }

    @ParameterizedTest
    @MethodSource("setTags")
    void txIdAndWitnessDatumHashesComeFromTheOriginalBytes(String setTag) throws Exception {
        // fee as a non-minimal uint64 and a datum map with keys out of canonical order: CCL re-encodes both
        String nonMinimalFee = "021b0000000000030d40";
        String datum = "a202000100";
        byte[] cbor = tx(3, INPUTS + NO_OUTPUTS + nonMinimalFee, "a104" + setTag + "81" + datum);
        TxInfo info = converter(cbor, 10).buildTxInfo();

        byte[] body = TransactionUtil.extractTransactionBodyFromTx(cbor);
        assertArrayEquals(Blake2bUtil.blake2bHash256(body), info.id().hash());
        byte[] reEncoded = CborSerializationUtil.serialize(Transaction.deserialize(cbor).getBody().serialize());
        assertFalse(java.util.Arrays.equals(Blake2bUtil.blake2bHash256(reEncoded), info.id().hash()),
                "the test needs a body CCL does not reproduce");

        DatumHash hash = DatumHash.of(Blake2bUtil.blake2bHash256(b(datum)));
        assertEquals(List.of(hash), toList(info.datums().keys()));
        assertEquals(List.of(new PlutusData.IntData(2), new PlutusData.IntData(1)), keysOf(info.datums().get(hash)),
                "the datum keeps its own key order");
    }

    // --- 2. Maps and Values in key order ---

    @Test
    void valuesDatumsAndRedeemersAreInKeyOrder() throws Exception {
        String policyA = "0a".repeat(28);
        String policyB = "0b".repeat(28);
        String policyC = "0c".repeat(28);
        // Neither the body's order nor its reverse is the ledger's: policies B, A, C; B's names 02, 0101, 03
        // (byte order 0101, 02, 03; CCL's canonical length-first order would be 02, 03, 0101)
        String value = "821a001e8480a3"
                + "581c" + policyB + "a3" + "410201" + "42010102" + "410303"
                + "581c" + policyA + "a14003"
                + "581c" + policyC + "a14004";
        String outputs = "0181825839" + "00" + "00".repeat(56) + value;
        String mint = "09a3" + "581c" + policyB + "a14101" + "01" + "581c" + policyA + "a14101" + "01"
                + "581c" + policyC + "a14101" + "01";
        // Witness datums in ascending hash order (Julc used to reverse them) and redeemers spend[0] before
        // mint[0] (Julc used to reverse them too)
        var datums = new ArrayList<>(List.of("01", "02"));
        datums.sort((x, y) -> LedgerOrder.BYTES.compare(Blake2bUtil.blake2bHash256(b(x)),
                Blake2bUtil.blake2bHash256(b(y))));
        String witnesses = "a2" + "04d9010282" + datums.get(0) + datums.get(1)
                + "0582" + "84000000820000" + "84010000820000";
        byte[] cbor = tx(4, INPUTS + outputs + FEE + mint, witnesses);
        TxInfo info = converter(cbor, 10).buildTxInfo();

        var outValue = info.outputs().get(0).value().toPlutusData();
        assertEquals(List.of(bytes(""), bytes(policyA), bytes(policyB), bytes(policyC)), keysOf(outValue));
        PlutusData namesOfB = ((PlutusData.MapData) outValue).entries().get(2).value();
        assertEquals(List.of(bytes("0101"), bytes("02"), bytes("03")), keysOf(namesOfB),
                "names in byte order, not length-first");
        assertEquals(List.of(bytes(policyA), bytes(policyB), bytes(policyC)), keysOf(info.mint().toPlutusData()));

        assertEquals(datums.stream().map(d -> DatumHash.of(Blake2bUtil.blake2bHash256(b(d)))).toList(),
                toList(info.datums().keys()));

        var purposes = toList(info.redeemers().keys());
        assertInstanceOf(ScriptPurpose.Spending.class, purposes.get(0), "spend (tag 0) before mint (tag 1)");
        assertInstanceOf(ScriptPurpose.Minting.class, purposes.get(1));
    }

    // --- 3. Signatories sorted and deduplicated ---

    @Test
    void signatoriesAreASortedSet() throws Exception {
        String signers = "0ed9010283" + h28("cc") + h28("aa") + h28("cc");
        TxInfo info = converter(tx(4, INPUTS + NO_OUTPUTS + FEE + signers, NO_WITNESSES), 10).buildTxInfo();
        assertEquals(List.of(PubKeyHash.of(b("aa".repeat(28))), PubKeyHash.of(b("cc".repeat(28)))),
                toList(info.signatories()));
    }

    // --- 4. Withdrawal and voter order (redeemer indexes and the maps) ---

    @Test
    void withdrawalsFollowTheLedgerOrderInV3AndPlutusOrderInV1V2() throws Exception {
        String key00 = "00".repeat(28);
        String key40 = "40" + "00".repeat(27);   // bech32 sorts this one before key00
        String script11 = "11".repeat(28);
        String withdrawals = "05a3" + account("e0", key00) + "01" + account("e0", key40) + "02"
                + account("f0", script11) + "03";
        var converter = converter(tx(4, INPUTS + NO_OUTPUTS + FEE + withdrawals, NO_WITNESSES), 10);
        TxInfo info = converter.buildTxInfo();

        var scriptCred = new Credential.ScriptCredential(ScriptHash.of(b(script11)));
        var cred00 = new Credential.PubKeyCredential(PubKeyHash.of(b(key00)));
        var cred40 = new Credential.PubKeyCredential(PubKeyHash.of(b(key40)));
        assertEquals(List.of(scriptCred, cred00, cred40), converter.getSortedWithdrawalCredentials());
        assertEquals(List.of(scriptCred, cred00, cred40), toList(info.withdrawals().keys()));

        // V2 txInfoWdrl: plutus-ledger-api Credential order, PubKeyCredential first
        var v2 = (PlutusData.ConstrData) ((PlutusData.ConstrData) V1V2ScriptContextBuilder.build(
                PlutusLanguage.PLUTUS_V2, info, new ScriptPurpose.Rewarding(scriptCred))).fields().get(0);
        var stakingHash = (java.util.function.Function<Credential, PlutusData>) c ->
                new PlutusData.ConstrData(0, List.of(c.toPlutusData()));
        assertEquals(List.of(stakingHash.apply(cred00), stakingHash.apply(cred40), stakingHash.apply(scriptCred)),
                keysOf(v2.fields().get(6)));
    }

    @Test
    void votersFollowTheLedgerOrder() throws Exception {
        String actionId = "825820" + "bb".repeat(32) + "00";
        String votes = "13a2" + "8202" + h28("00") + "a1" + actionId + "8201f6"
                + "8203" + h28("22") + "a1" + actionId + "8200f6";
        var converter = converter(tx(4, INPUTS + NO_OUTPUTS + FEE + votes, NO_WITNESSES), 10);
        TxInfo info = converter.buildTxInfo();

        var scriptDRep = new Voter.DRepVoter(new Credential.ScriptCredential(ScriptHash.of(b("22".repeat(28)))));
        var keyDRep = new Voter.DRepVoter(new Credential.PubKeyCredential(PubKeyHash.of(b("00".repeat(28)))));
        assertEquals(List.of(scriptDRep, keyDRep), converter.getSortedVoters(), "ScriptHashObj before KeyHashObj");
        assertEquals(List.of(scriptDRep, keyDRep), toList(info.votes().keys()));
    }

    // --- 5. V1 withdrawals and datums are lists of pairs ---

    @Test
    void v1DatumsAreAListOfPairs() throws Exception {
        byte[] cbor = tx(3, INPUTS + NO_OUTPUTS + FEE, "a104d901028101");
        var converter = converter(cbor, 10);
        TxInfo info = converter.buildTxInfo();
        var v1 = (PlutusData.ConstrData) ((PlutusData.ConstrData) V1V2ScriptContextBuilder.build(
                PlutusLanguage.PLUTUS_V1, info,
                new ScriptPurpose.Spending(new TxOutRef(TxId.of(b(INPUT_TX)), BigInteger.ZERO)))).fields().get(0);
        var datums = assertInstanceOf(PlutusData.ListData.class, v1.fields().get(8));
        assertEquals(List.of(new PlutusData.ConstrData(0, List.of(
                        bytes(HexFormat.of().formatHex(Blake2bUtil.blake2bHash256(b("01")))), new PlutusData.IntData(1)))),
                datums.items());
        assertInstanceOf(PlutusData.ListData.class, v1.fields().get(5), "withdrawals");
    }

    // --- 6. Governance actions translated, not InfoAction ---

    @Test
    void governanceActionsAreTranslated() throws Exception {
        String policy = "33".repeat(28);
        String returnAccount = account("e0", "00".repeat(28));
        String prevId = "825820" + "cc".repeat(32) + "01";
        String[] actions = {
                // ParameterChange: a0 (key 9) = 6/20, then minFeeA (key 0) = 44; guardrails policy
                "8400f6a209d81e820614" + "00182c" + h28("33"),
                "8301" + prevId + "820a00",                                          // HardForkInitiation
                "8302a2" + account("e0", "40".repeat(28)) + "05" + account("f0", "11".repeat(28)) + "06f6",
                "8203f6",                                                            // NoConfidence
                "8504f6d9010281" + key("00") + "a1" + script("22") + "0ad81e820204", // UpdateCommittee
                "8305" + prevId + "82" + ANCHOR + h28("33"),                          // NewConstitution
                "8106"                                                               // InfoAction
        };
        var proposals = new StringBuilder("14d901028" + Integer.toHexString(actions.length));
        for (String action : actions) {
            proposals.append("841a000f4240").append(returnAccount).append(action).append(ANCHOR);
        }
        TxInfo info = converter(tx(4, INPUTS + NO_OUTPUTS + FEE + proposals, NO_WITNESSES), 10).buildTxInfo();
        var got = toList(info.proposalProcedures()).stream().map(ProposalProcedure::governanceAction).toList();

        var params = new PlutusData.MapData(List.of(
                new PlutusData.Pair(new PlutusData.IntData(0), new PlutusData.IntData(44)),
                new PlutusData.Pair(new PlutusData.IntData(9), new PlutusData.ListData(List.of(
                        new PlutusData.IntData(3), new PlutusData.IntData(10))))));
        var prev = Optional.of(new GovernanceActionId(TxId.of(b("cc".repeat(32))), BigInteger.ONE));
        var guardrails = Optional.of(ScriptHash.of(b(policy)));
        assertEquals(new GovernanceAction.ParameterChange(Optional.empty(), params, guardrails).toPlutusData(),
                got.get(0).toPlutusData());
        assertEquals(new GovernanceAction.HardForkInitiation(prev,
                new ProtocolVersion(BigInteger.TEN, BigInteger.ZERO)).toPlutusData(), got.get(1).toPlutusData());
        var treasury = (GovernanceAction.TreasuryWithdrawals) got.get(2);
        assertEquals(List.of(new Credential.ScriptCredential(ScriptHash.of(b("11".repeat(28)))),
                new Credential.PubKeyCredential(PubKeyHash.of(b("40".repeat(28))))),
                toList(treasury.withdrawals().keys()), "ledger AccountAddress order");
        assertInstanceOf(GovernanceAction.NoConfidence.class, got.get(3));
        var committee = (GovernanceAction.UpdateCommittee) got.get(4);
        assertEquals(new Rational(BigInteger.ONE, BigInteger.TWO), committee.newQuorum(), "in lowest terms");
        assertEquals(BigInteger.TEN, committee.addedMembers().get(
                new Credential.ScriptCredential(ScriptHash.of(b("22".repeat(28))))));
        assertEquals(new GovernanceAction.NewConstitution(prev, guardrails), got.get(5));
        assertInstanceOf(GovernanceAction.InfoAction.class, got.get(6));
        assertEquals(new Credential.PubKeyCredential(PubKeyHash.of(b("00".repeat(28)))),
                toList(info.proposalProcedures()).get(0).returnAddress());
    }

    // --- 7. PV9 omits the reg/unreg certificate deposits in V3 ---

    @Test
    void protocolVersion9OmitsRegistrationDeposits() throws Exception {
        String certs = "04d9010282" + "8307" + key("00") + "1a001e8480" + "8308" + key("00") + "1a001e8480";
        byte[] cbor = tx(4, INPUTS + NO_OUTPUTS + FEE + certs, NO_WITNESSES);
        var cred = new Credential.PubKeyCredential(PubKeyHash.of(b("00".repeat(28))));

        var pv9 = toList(converter(cbor, 9).buildTxInfo().certificates());
        assertEquals(List.of(new TxCert.RegStaking(cred, Optional.empty()),
                new TxCert.UnRegStaking(cred, Optional.empty())), pv9);
        var pv10 = toList(converter(cbor, 10).buildTxInfo().certificates());
        assertEquals(List.of(new TxCert.RegStaking(cred, Optional.of(BigInteger.valueOf(2_000_000))),
                new TxCert.UnRegStaking(cred, Optional.of(BigInteger.valueOf(2_000_000)))), pv10);
    }

    // --- 8. An explicit validity bound of slot 0 is a finite bound ---

    @Test
    void validityStartAtSlotZeroIsFinite() throws Exception {
        TxInfo info = converter(tx(4, INPUTS + NO_OUTPUTS + FEE + "0800", NO_WITNESSES), 10).buildTxInfo();
        assertEquals(new IntervalBoundType.Finite(BigInteger.ZERO), info.validRange().from().boundType());
        assertInstanceOf(IntervalBoundType.PosInf.class, info.validRange().to().boundType());

        TxInfo absent = converter(tx(3, INPUTS + NO_OUTPUTS + FEE, NO_WITNESSES), 10).buildTxInfo();
        assertInstanceOf(IntervalBoundType.NegInf.class, absent.validRange().from().boundType());
    }

    // --- 10. V3 must return (); a V1/V2 spend needs a datum ---

    @Test
    void v3ScriptReturningNonUnitFails() throws Exception {
        var program = Program.plutusV3(Term.lam("ctx", Term.const_(Constant.integer(1))));
        var script = JulcScriptAdapter.fromProgram(program);
        String hash = JulcScriptAdapter.scriptHash(program);
        byte[] cbor = tx(3, INPUTS + NO_OUTPUTS + FEE, "a10581840000008200" + "00");
        var result = evaluator(script, hash).evaluateTx(cbor, Set.of(inputUtxo(scriptAddress(hash))));
        assertFalse(result.isSuccessful());
        assertTrue(result.getResponse().contains("InvalidReturnValue"), result.getResponse());
    }

    @Test
    void v2SpendWithoutDatumFails() throws Exception {
        var program = Program.plutusV2(Term.lam("d", Term.lam("r", Term.lam("ctx", Term.const_(Constant.unit())))));
        var script = PlutusV2Script.builder().cborHex(JulcScriptAdapter.fromProgram(program).getCborHex()).build();
        String hash = HexFormat.of().formatHex(script.getScriptHash());
        byte[] cbor = tx(3, INPUTS + NO_OUTPUTS + FEE, "a10581840000008200" + "00");
        var result = evaluator(script, hash).evaluateTx(cbor, Set.of(inputUtxo(scriptAddress(hash))));
        assertFalse(result.isSuccessful());
        assertTrue(result.getResponse().contains("No datum"), result.getResponse());
    }

    @ParameterizedTest
    @MethodSource("setTags")
    void v2SpendFindsItsWitnessDatum(String setTag) throws Exception {
        var program = Program.plutusV2(Term.lam("d", Term.lam("r", Term.lam("ctx", Term.const_(Constant.unit())))));
        var script = PlutusV2Script.builder().cborHex(JulcScriptAdapter.fromProgram(program).getCborHex()).build();
        String hash = HexFormat.of().formatHex(script.getScriptHash());
        String datum = "a202000100";
        Utxo input = inputUtxo(scriptAddress(hash));
        input.setDataHash(HexFormat.of().formatHex(Blake2bUtil.blake2bHash256(b(datum))));
        byte[] cbor = tx(3, INPUTS + NO_OUTPUTS + FEE, "a204" + setTag + "81" + datum + "0581840000008200" + "00");
        var result = evaluator(script, hash).evaluateTx(cbor, Set.of(input));
        assertTrue(result.isSuccessful(), result.getResponse());
    }

    // --- 12. The proposing script is the guardrails script ---

    @Test
    void proposingRunsTheGuardrailsScript() throws Exception {
        var program = Program.plutusV3(Term.lam("ctx", Term.const_(Constant.unit())));
        var script = JulcScriptAdapter.fromProgram(program);
        String hash = JulcScriptAdapter.scriptHash(program);
        String proposal = "14d9010281841a000f4240" + account("e0", "00".repeat(28))
                + "8400f6a100182c581c" + hash + ANCHOR;
        byte[] cbor = tx(4, INPUTS + NO_OUTPUTS + FEE + proposal, "a10581840500008200" + "00");
        var result = evaluator(script, hash).evaluateTx(cbor, Set.of(inputUtxo(null)));
        assertTrue(result.isSuccessful(), result.getResponse());
        assertEquals(1, result.getValue().size());
    }

    // --- 13. A donation of 0 is Nothing ---

    @Test
    void zeroDonationIsAbsent() throws Exception {
        TxInfo info = converter(tx(4, INPUTS + NO_OUTPUTS + FEE + "1600", NO_WITNESSES), 10).buildTxInfo();
        assertEquals(Optional.empty(), info.treasuryDonation());
    }

    // --- 14. An output's value always has its lovelace entry ---

    @Test
    void zeroCoinOutputKeepsItsLovelaceEntry() throws Exception {
        String outputs = "018182581d61" + "00".repeat(28) + "00";
        TxInfo info = converter(tx(3, INPUTS + outputs + FEE, NO_WITNESSES), 10).buildTxInfo();
        assertEquals(List.of(bytes("")), keysOf(info.outputs().get(0).value().toPlutusData()));
    }

    // --- Regression guards (PR #221 review) ---

    @Test
    void indefiniteLengthBodyWitnessesAndListsAreRead() throws Exception {
        // Indefinite body map, witness map, datum list, redeemer list, proposal list and action array
        String proposals = "149f" + "841a000f4240" + account("e0", "00".repeat(28)) + "9f06ff" + ANCHOR + "ff";
        String body = "bf" + INPUTS + NO_OUTPUTS + FEE + proposals + "ff";
        String witnesses = "bf" + "04d901029f0102ff" + "059f840000a20101010282000 0ff".replace(" ", "") + "ff";
        byte[] cbor = HexFormat.of().parseHex("84" + body + witnesses + "f5f6");
        var converter = converter(cbor, 10);
        TxInfo info = converter.buildTxInfo();

        assertArrayEquals(Blake2bUtil.blake2bHash256(TransactionUtil.extractTransactionBodyFromTx(cbor)),
                info.id().hash());
        assertEquals(2, info.datums().size());
        assertTrue(info.datums().containsKey(DatumHash.of(Blake2bUtil.blake2bHash256(b("01")))));
        assertInstanceOf(GovernanceAction.InfoAction.class,
                toList(info.proposalProcedures()).get(0).governanceAction());
        var redeemer = info.redeemers().values().iterator().next();
        assertEquals(2, ((PlutusData.MapData) redeemer).entries().size(), "the duplicate key survives");
    }

    @Test
    void datumsAndRedeemersKeepDuplicateMapKeys() throws Exception {
        String dupMap = "a2" + "0101" + "0102";   // {1: 1, 1: 2}
        String outputs = "0181" + "a3" + "00581d61" + "00".repeat(28) + "011a001e8480" + "028201d818" + "45" + dupMap;
        String witnesses = "a1" + "0581" + "840000" + dupMap + "820000";
        byte[] cbor = tx(3, INPUTS + outputs + FEE, witnesses);
        Utxo input = inputUtxo(null);
        input.setInlineDatum(dupMap);
        var converter = new CclTxConverter(Transaction.deserialize(cbor), cbor, Set.of(input), null, null, 10);
        TxInfo info = converter.buildTxInfo();

        var expected = new PlutusData.MapData(List.of(
                new PlutusData.Pair(new PlutusData.IntData(1), new PlutusData.IntData(1)),
                new PlutusData.Pair(new PlutusData.IntData(1), new PlutusData.IntData(2))));
        assertEquals(new OutputDatum.OutputDatumInline(expected), info.outputs().get(0).datum());
        assertEquals(new OutputDatum.OutputDatumInline(expected), info.inputs().get(0).resolved().datum());
        assertEquals(expected, info.redeemers().values().iterator().next());
    }

    @Test
    void mixedVoterKindsAndActionIdsFollowTheLedgerOrder() throws Exception {
        String aa0 = "825820" + "aa".repeat(32) + "00";
        String bb0 = "825820" + "bb".repeat(32) + "00";
        String bb1 = "825820" + "bb".repeat(32) + "01";
        String vote = "8201f6";
        // body order: SPO (three actions, out of order), DRep key, committee key, committee script
        String votes = "13a4"
                + "8204" + h28("01") + "a3" + bb1 + vote + aa0 + vote + bb0 + vote
                + "8202" + h28("02") + "a1" + aa0 + vote
                + "8200" + h28("03") + "a1" + aa0 + vote
                + "8201" + h28("04") + "a1" + aa0 + vote;
        var converter = converter(tx(4, INPUTS + NO_OUTPUTS + FEE + votes, NO_WITNESSES), 10);
        TxInfo info = converter.buildTxInfo();

        var expected = List.of(
                new Voter.CommitteeVoter(new Credential.ScriptCredential(ScriptHash.of(b("04".repeat(28))))),
                new Voter.CommitteeVoter(new Credential.PubKeyCredential(PubKeyHash.of(b("03".repeat(28))))),
                new Voter.DRepVoter(new Credential.PubKeyCredential(PubKeyHash.of(b("02".repeat(28))))),
                new Voter.StakePoolVoter(PubKeyHash.of(b("01".repeat(28)))));
        assertEquals(expected, converter.getSortedVoters());
        assertEquals(expected, toList(info.votes().keys()));
        var spoActions = toList(info.votes().get(expected.get(3)).keys());
        assertEquals(List.of(
                new GovernanceActionId(TxId.of(b("aa".repeat(32))), BigInteger.ZERO),
                new GovernanceActionId(TxId.of(b("bb".repeat(32))), BigInteger.ZERO),
                new GovernanceActionId(TxId.of(b("bb".repeat(32))), BigInteger.ONE)), spoActions);
    }

    @Test
    void committeeUpdateMembersFollowTheLedgerOrder() throws Exception {
        String action = "8504f6"
                + "d9010283" + key("00") + script("22") + key("11")
                + "a2" + key("33") + "05" + script("44") + "06"
                + "d81e820304";
        String proposals = "14d9010281841a000f4240" + account("e0", "00".repeat(28)) + action + ANCHOR;
        TxInfo info = converter(tx(4, INPUTS + NO_OUTPUTS + FEE + proposals, NO_WITNESSES), 10).buildTxInfo();
        var update = (GovernanceAction.UpdateCommittee) toList(info.proposalProcedures()).get(0).governanceAction();

        assertEquals(List.of(new Credential.ScriptCredential(ScriptHash.of(b("22".repeat(28)))),
                new Credential.PubKeyCredential(PubKeyHash.of(b("00".repeat(28)))),
                new Credential.PubKeyCredential(PubKeyHash.of(b("11".repeat(28))))), toList(update.removedMembers()));
        assertEquals(List.of(new Credential.ScriptCredential(ScriptHash.of(b("44".repeat(28)))),
                new Credential.PubKeyCredential(PubKeyHash.of(b("33".repeat(28))))),
                toList(update.addedMembers().keys()));
        assertEquals(new Rational(BigInteger.valueOf(3), BigInteger.valueOf(4)), update.newQuorum());
    }

    @Test
    void withdrawalsOrderByNetworkFirst() throws Exception {
        String mainnetKey = "00".repeat(28);
        String testnetScript = "11".repeat(28);
        String testnetKey = "22".repeat(28);
        String withdrawals = "05a3" + account("e1", mainnetKey) + "01" + account("f0", testnetScript) + "02"
                + account("e0", testnetKey) + "03";
        var converter = converter(tx(4, INPUTS + NO_OUTPUTS + FEE + withdrawals, NO_WITNESSES), 10);
        assertEquals(List.of(new Credential.ScriptCredential(ScriptHash.of(b(testnetScript))),
                new Credential.PubKeyCredential(PubKeyHash.of(b(testnetKey))),
                new Credential.PubKeyCredential(PubKeyHash.of(b(mainnetKey)))),
                converter.getSortedWithdrawalCredentials());
    }

    @Test
    void cclBuiltTransactionRoundTrips() throws Exception {
        var datum = com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData.of(0,
                com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData.of(42),
                com.bloxbean.cardano.client.plutus.spec.BytesPlutusData.of(b("cafe")));
        var output = com.bloxbean.cardano.client.transaction.spec.TransactionOutput.builder()
                .address(inputUtxo(null).getAddress())
                .value(com.bloxbean.cardano.client.transaction.spec.Value.builder()
                        .coin(BigInteger.valueOf(2_000_000)).build())
                .inlineDatum(datum)
                .build();
        var redeemer = com.bloxbean.cardano.client.plutus.spec.Redeemer.builder()
                .tag(com.bloxbean.cardano.client.plutus.spec.RedeemerTag.Spend).index(BigInteger.ZERO)
                .data(datum)
                .exUnits(com.bloxbean.cardano.client.plutus.spec.ExUnits.builder()
                        .mem(BigInteger.ONE).steps(BigInteger.ONE).build())
                .build();
        var tx = Transaction.builder()
                .body(com.bloxbean.cardano.client.transaction.spec.TransactionBody.builder()
                        .inputs(List.of(new com.bloxbean.cardano.client.transaction.spec.TransactionInput(INPUT_TX, 0)))
                        .outputs(List.of(output))
                        .fee(BigInteger.valueOf(200_000))
                        .requiredSigners(List.of(b("cc".repeat(28)), b("aa".repeat(28))))
                        .build())
                .witnessSet(com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet.builder()
                        .redeemers(List.of(redeemer)).plutusDataList(List.of(datum)).build())
                .build();
        byte[] cbor = tx.serialize();
        TxInfo info = new CclTxConverter(Transaction.deserialize(cbor), cbor, Set.of(inputUtxo(null)), null, null, 10)
                .buildTxInfo();

        assertEquals(TransactionUtil.getTxHash(cbor), HexFormat.of().formatHex(info.id().hash()));
        var expected = org.julclang.clientlib.PlutusDataAdapter.fromClientLib(datum);
        assertEquals(new OutputDatum.OutputDatumInline(expected), info.outputs().get(0).datum());
        assertEquals(expected, info.redeemers().values().iterator().next());
        assertEquals(expected, info.datums().get(DatumHash.of(b(datum.getDatumHash()))));
        assertEquals(List.of(PubKeyHash.of(b("aa".repeat(28))), PubKeyHash.of(b("cc".repeat(28)))),
                toList(info.signatories()));
    }

    // --- shared ---

    private static <T> List<T> toList(Iterable<T> items) {
        var list = new ArrayList<T>();
        items.forEach(list::add);
        return list;
    }

    private static String scriptAddress(String scriptHashHex) {
        return com.bloxbean.cardano.client.address.AddressProvider.getEntAddress(
                com.bloxbean.cardano.client.address.Credential.fromScript(b(scriptHashHex)),
                com.bloxbean.cardano.client.common.model.Networks.testnet()).toBech32();
    }

    private static JulcTransactionEvaluator evaluator(PlutusScript script, String hash) {
        UtxoSupplier noUtxos = new UtxoSupplier() {
            @Override
            public List<Utxo> getPage(String address, Integer nrOfItems, Integer page, OrderEnum order) {
                return List.of();
            }

            @Override
            public Optional<Utxo> getTxOutput(String txHash, int outputIndex) {
                return Optional.empty();
            }
        };
        ProtocolParamsSupplier params = () -> {
            var p = new ProtocolParams();
            p.setMaxTxExMem("14000000");
            p.setMaxTxExSteps("10000000000");
            return p;
        };
        return new JulcTransactionEvaluator(noUtxos, params,
                h -> h.equals(hash) ? Optional.of(script) : Optional.empty());
    }
}
