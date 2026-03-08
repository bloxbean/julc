package com.bloxbean.cardano.julc.stdlib.lib;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.testkit.JulcEval;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

import static com.bloxbean.cardano.julc.stdlib.lib.StdlibTestHelpers.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ContextsLib using JulcEval — compiles actual Java source to UPLC
 * and evaluates through Scalus VM for real on-chain behavior testing.
 */
class ContextsLibTest {

    static JulcEval eval;

    // --- Constants ---
    static final byte[] PKH_1 = makeBytes(28, 0x01);
    static final byte[] PKH_2 = makeBytes(28, 0x02);
    static final byte[] PKH_3 = makeBytes(28, 0x03);
    static final byte[] SCRIPT_HASH_1 = makeBytes(28, 0x10);
    static final byte[] SCRIPT_HASH_2 = makeBytes(28, 0x20);
    static final byte[] TX_HASH_1 = makeBytes(32, 0xAA);
    static final byte[] TX_HASH_2 = makeBytes(32, 0xBB);
    static final byte[] DATUM_HASH_1 = makeBytes(32, 0xDD);
    static final byte[] DATUM_HASH_2 = makeBytes(32, 0xEE);
    static final byte[] POLICY_ID_1 = makeBytes(28, 0x50);

    @BeforeAll
    static void setUp() {
        eval = JulcEval.forClass(ContextsLib.class);
    }

    // --- Helper methods (shared helpers via StdlibTestHelpers) ---

    static TxOutRef txOutRef(byte[] txHash, int idx) {
        return new TxOutRef(new TxId(txHash), BigInteger.valueOf(idx));
    }

    static TxInInfo simpleTxInInfo(byte[] txHash, int idx, Address addr, long lovelace) {
        return new TxInInfo(txOutRef(txHash, idx), simpleTxOut(addr, lovelace));
    }

    // =========================================================================
    // Group 1: ScriptContext Accessors
    // =========================================================================

    @Nested
    class GetTxInfo {

        @Test
        void returnsTxInfoData() {
            var ctx = ScriptContextBuilder.spending(txOutRef(TX_HASH_1, 0))
                    .fee(BigInteger.valueOf(200_000))
                    .build();
            PlutusData result = eval.call("getTxInfo", ctx).asData();
            assertNotNull(result);
            assertInstanceOf(PlutusData.ConstrData.class, result);
        }

        @Test
        void worksWithMintingContext() {
            var ctx = ScriptContextBuilder.minting(PolicyId.of(POLICY_ID_1))
                    .fee(BigInteger.valueOf(100_000))
                    .build();
            PlutusData result = eval.call("getTxInfo", ctx).asData();
            assertNotNull(result);
            assertInstanceOf(PlutusData.ConstrData.class, result);
        }
    }

    @Nested
    class GetRedeemer {

        @Test
        void returnsExactRedeemer() {
            var redeemer = PlutusData.integer(42);
            var ctx = ScriptContextBuilder.spending(txOutRef(TX_HASH_1, 0))
                    .redeemer(redeemer)
                    .build();
            PlutusData result = eval.call("getRedeemer", ctx).asData();
            assertEquals(PlutusData.integer(42), result);
        }

        @Test
        void complexRedeemerRoundTrips() {
            var redeemer = new PlutusData.ConstrData(1, List.of(
                    PlutusData.integer(100),
                    PlutusData.bytes(new byte[]{1, 2, 3})));
            var ctx = ScriptContextBuilder.spending(txOutRef(TX_HASH_1, 0))
                    .redeemer(redeemer)
                    .build();
            PlutusData result = eval.call("getRedeemer", ctx).asData();
            assertEquals(redeemer, result);
        }
    }

    // =========================================================================
    // Group 2: TxInfo Field Accessors
    // =========================================================================

    @Nested
    class TxInfoFieldAccessors {

        @Test
        void txInfoInputsReturnsList() {
            var in1 = simpleTxInInfo(TX_HASH_1, 0, pubKeyAddress(PKH_1), 5_000_000);
            var in2 = simpleTxInInfo(TX_HASH_2, 1, pubKeyAddress(PKH_2), 3_000_000);
            var ctx = ScriptContextBuilder.spending(txOutRef(TX_HASH_1, 0))
                    .input(in1).input(in2)
                    .build();
            List<PlutusData> result = eval.call("txInfoInputs", ctx.txInfo()).asList();
            assertEquals(2, result.size());
        }

        @Test
        void txInfoInputsEmptyList() {
            var ctx = ScriptContextBuilder.spending(txOutRef(TX_HASH_1, 0)).build();
            List<PlutusData> result = eval.call("txInfoInputs", ctx.txInfo()).asList();
            assertTrue(result.isEmpty());
        }

        @Test
        void txInfoOutputsReturnsList() {
            var out1 = simpleTxOut(pubKeyAddress(PKH_1), 2_000_000);
            var out2 = simpleTxOut(pubKeyAddress(PKH_2), 3_000_000);
            var out3 = simpleTxOut(scriptAddress(SCRIPT_HASH_1), 1_000_000);
            var ctx = ScriptContextBuilder.spending(txOutRef(TX_HASH_1, 0))
                    .output(out1).output(out2).output(out3)
                    .build();
            List<PlutusData> result = eval.call("txInfoOutputs", ctx.txInfo()).asList();
            assertEquals(3, result.size());
        }

        @Test
        void txInfoOutputsEmptyList() {
            var ctx = ScriptContextBuilder.spending(txOutRef(TX_HASH_1, 0)).build();
            List<PlutusData> result = eval.call("txInfoOutputs", ctx.txInfo()).asList();
            assertTrue(result.isEmpty());
        }

        @Test
        void txInfoSignatoriesReturnsList() {
            var ctx = ScriptContextBuilder.spending(txOutRef(TX_HASH_1, 0))
                    .signer(new PubKeyHash(PKH_1))
                    .signer(new PubKeyHash(PKH_2))
                    .build();
            List<PlutusData> result = eval.call("txInfoSignatories", ctx.txInfo()).asList();
            assertEquals(2, result.size());
        }

        @Test
        void txInfoSignatoriesEmptyList() {
            var ctx = ScriptContextBuilder.spending(txOutRef(TX_HASH_1, 0)).build();
            List<PlutusData> result = eval.call("txInfoSignatories", ctx.txInfo()).asList();
            assertTrue(result.isEmpty());
        }

        @Test
        void txInfoValidRangeAlwaysInterval() {
            var ctx = ScriptContextBuilder.spending(txOutRef(TX_HASH_1, 0)).build();
            PlutusData result = eval.call("txInfoValidRange", ctx.txInfo()).asData();
            assertNotNull(result);
            assertInstanceOf(PlutusData.ConstrData.class, result);
        }

        @Test
        void txInfoValidRangeFiniteInterval() {
            var ctx = ScriptContextBuilder.spending(txOutRef(TX_HASH_1, 0))
                    .validRange(Interval.between(BigInteger.valueOf(100), BigInteger.valueOf(200)))
                    .build();
            PlutusData result = eval.call("txInfoValidRange", ctx.txInfo()).asData();
            assertNotNull(result);
            assertInstanceOf(PlutusData.ConstrData.class, result);
        }

        @Test
        void txInfoMintEmpty() {
            var ctx = ScriptContextBuilder.spending(txOutRef(TX_HASH_1, 0)).build();
            // Empty mint → empty pair list → empty UPLC list
            var rawTerm = eval.call("txInfoMint", ctx.txInfo()).rawTerm();
            assertNotNull(rawTerm);
        }

        @Test
        void txInfoMintNonEmpty() {
            var mint = Value.singleton(PolicyId.of(POLICY_ID_1),
                    TokenName.of(new byte[]{0x01}), BigInteger.TEN);
            var ctx = ScriptContextBuilder.spending(txOutRef(TX_HASH_1, 0))
                    .mint(mint)
                    .build();
            var rawTerm = eval.call("txInfoMint", ctx.txInfo()).rawTerm();
            assertNotNull(rawTerm);
        }

        @Test
        void txInfoFeeReturnsCorrectAmount() {
            var ctx = ScriptContextBuilder.spending(txOutRef(TX_HASH_1, 0))
                    .fee(BigInteger.valueOf(200_000))
                    .build();
            BigInteger fee = eval.call("txInfoFee", ctx.txInfo()).asInteger();
            assertEquals(BigInteger.valueOf(200_000), fee);
        }

        @Test
        void txInfoFeeZero() {
            var ctx = ScriptContextBuilder.spending(txOutRef(TX_HASH_1, 0))
                    .fee(BigInteger.ZERO)
                    .build();
            BigInteger fee = eval.call("txInfoFee", ctx.txInfo()).asInteger();
            assertEquals(BigInteger.ZERO, fee);
        }

        @Test
        void txInfoIdReturnsCorrectHash() {
            var ctx = ScriptContextBuilder.spending(txOutRef(TX_HASH_1, 0))
                    .txId(new TxId(TX_HASH_1))
                    .build();
            byte[] txId = eval.call("txInfoId", ctx.txInfo()).asByteString();
            assertArrayEquals(TX_HASH_1, txId);
        }

        @Test
        void txInfoRefInputsReturnsList() {
            var refIn = simpleTxInInfo(TX_HASH_2, 0, pubKeyAddress(PKH_3), 1_000_000);
            var ctx = ScriptContextBuilder.spending(txOutRef(TX_HASH_1, 0))
                    .referenceInput(refIn)
                    .build();
            List<PlutusData> result = eval.call("txInfoRefInputs", ctx.txInfo()).asList();
            assertEquals(1, result.size());
        }

        @Test
        void txInfoRefInputsEmpty() {
            var ctx = ScriptContextBuilder.spending(txOutRef(TX_HASH_1, 0)).build();
            List<PlutusData> result = eval.call("txInfoRefInputs", ctx.txInfo()).asList();
            assertTrue(result.isEmpty());
        }

        @Test
        void txInfoWithdrawalsEmpty() {
            var ctx = ScriptContextBuilder.spending(txOutRef(TX_HASH_1, 0)).build();
            var rawTerm = eval.call("txInfoWithdrawals", ctx.txInfo()).rawTerm();
            assertNotNull(rawTerm);
        }

        @Test
        void txInfoWithdrawalsNonEmpty() {
            var cred = new Credential.ScriptCredential(new ScriptHash(SCRIPT_HASH_1));
            var ctx = ScriptContextBuilder.spending(txOutRef(TX_HASH_1, 0))
                    .withdrawal(cred, BigInteger.valueOf(1_000_000))
                    .build();
            var rawTerm = eval.call("txInfoWithdrawals", ctx.txInfo()).rawTerm();
            assertNotNull(rawTerm);
        }

        @Test
        void txInfoRedeemersEmpty() {
            var ctx = ScriptContextBuilder.spending(txOutRef(TX_HASH_1, 0)).build();
            var rawTerm = eval.call("txInfoRedeemers", ctx.txInfo()).rawTerm();
            assertNotNull(rawTerm);
        }
    }

    // =========================================================================
    // Group 3: signedBy
    // =========================================================================

    @Nested
    class SignedBy {

        @Test
        void trueWhenPresent() {
            var ctx = ScriptContextBuilder.spending(txOutRef(TX_HASH_1, 0))
                    .signer(new PubKeyHash(PKH_1))
                    .signer(new PubKeyHash(PKH_2))
                    .build();
            assertTrue(eval.call("signedBy", ctx.txInfo(), PKH_1).asBoolean());
        }

        @Test
        void falseWhenAbsent() {
            var ctx = ScriptContextBuilder.spending(txOutRef(TX_HASH_1, 0))
                    .signer(new PubKeyHash(PKH_1))
                    .signer(new PubKeyHash(PKH_2))
                    .build();
            assertFalse(eval.call("signedBy", ctx.txInfo(), PKH_3).asBoolean());
        }

        @Test
        void emptySignatories() {
            var ctx = ScriptContextBuilder.spending(txOutRef(TX_HASH_1, 0)).build();
            assertFalse(eval.call("signedBy", ctx.txInfo(), PKH_1).asBoolean());
        }

        @Test
        void singleSignatoryMatch() {
            var ctx = ScriptContextBuilder.spending(txOutRef(TX_HASH_1, 0))
                    .signer(new PubKeyHash(PKH_1))
                    .build();
            assertTrue(eval.call("signedBy", ctx.txInfo(), PKH_1).asBoolean());
            assertFalse(eval.call("signedBy", ctx.txInfo(), PKH_2).asBoolean());
        }
    }

    // =========================================================================
    // Group 4: Optional Returns
    // =========================================================================

    @Nested
    class GetSpendingDatum {

        @Test
        void presentWithDatum() {
            var datum = PlutusData.integer(999);
            var ref = txOutRef(TX_HASH_1, 0);
            var ctx = ScriptContextBuilder.spending(ref, datum).build();
            Optional<PlutusData> result = eval.call("getSpendingDatum", ctx).asOptional();
            assertTrue(result.isPresent());
            assertEquals(PlutusData.integer(999), result.get());
        }

        @Test
        void emptyWithoutDatum() {
            var ref = txOutRef(TX_HASH_1, 0);
            var ctx = ScriptContextBuilder.spending(ref).build();
            Optional<PlutusData> result = eval.call("getSpendingDatum", ctx).asOptional();
            assertTrue(result.isEmpty());
        }

        @Test
        void emptyForMinting() {
            var ctx = ScriptContextBuilder.minting(PolicyId.of(POLICY_ID_1)).build();
            Optional<PlutusData> result = eval.call("getSpendingDatum", ctx).asOptional();
            assertTrue(result.isEmpty());
        }
    }

    @Nested
    class FindOwnInput {

        @Test
        void foundWhenMatchingInput() {
            var ref = txOutRef(TX_HASH_1, 0);
            var ownInput = new TxInInfo(ref, simpleTxOut(scriptAddress(SCRIPT_HASH_1), 5_000_000));
            var otherInput = simpleTxInInfo(TX_HASH_2, 1, pubKeyAddress(PKH_1), 3_000_000);
            var ctx = ScriptContextBuilder.spending(ref)
                    .input(ownInput)
                    .input(otherInput)
                    .build();
            Optional<PlutusData> result = eval.call("findOwnInput", ctx).asOptional();
            assertTrue(result.isPresent());
        }

        @Test
        void emptyForNoMatch() {
            var ref = txOutRef(TX_HASH_1, 0);
            // Add an input with a different outRef
            var otherInput = simpleTxInInfo(TX_HASH_2, 1, pubKeyAddress(PKH_1), 3_000_000);
            var ctx = ScriptContextBuilder.spending(ref)
                    .input(otherInput)
                    .build();
            Optional<PlutusData> result = eval.call("findOwnInput", ctx).asOptional();
            assertTrue(result.isEmpty());
        }

        @Test
        void emptyForMinting() {
            var ctx = ScriptContextBuilder.minting(PolicyId.of(POLICY_ID_1)).build();
            Optional<PlutusData> result = eval.call("findOwnInput", ctx).asOptional();
            assertTrue(result.isEmpty());
        }
    }

    @Nested
    class FindDatum {

        @Test
        void foundWhenHashMatches() {
            var datumHash = DatumHash.of(DATUM_HASH_1);
            var datumValue = PlutusData.integer(42);
            var ctx = ScriptContextBuilder.spending(txOutRef(TX_HASH_1, 0))
                    .datum(datumHash, datumValue)
                    .build();
            // findDatum takes TxInfo and the hash as PlutusData (BytesData)
            Optional<PlutusData> result = eval.call("findDatum",
                    ctx.txInfo(), PlutusData.bytes(DATUM_HASH_1)).asOptional();
            assertTrue(result.isPresent());
            assertEquals(PlutusData.integer(42), result.get());
        }

        @Test
        void emptyForWrongHash() {
            var datumHash = DatumHash.of(DATUM_HASH_1);
            var datumValue = PlutusData.integer(42);
            var ctx = ScriptContextBuilder.spending(txOutRef(TX_HASH_1, 0))
                    .datum(datumHash, datumValue)
                    .build();
            Optional<PlutusData> result = eval.call("findDatum",
                    ctx.txInfo(), PlutusData.bytes(DATUM_HASH_2)).asOptional();
            assertTrue(result.isEmpty());
        }

        @Test
        void emptyForEmptyDatumsMap() {
            var ctx = ScriptContextBuilder.spending(txOutRef(TX_HASH_1, 0)).build();
            Optional<PlutusData> result = eval.call("findDatum",
                    ctx.txInfo(), PlutusData.bytes(DATUM_HASH_1)).asOptional();
            assertTrue(result.isEmpty());
        }
    }

    // =========================================================================
    // Group 5: byte[] Returns
    // =========================================================================

    @Nested
    class OwnHash {

        @Test
        void returnsPolicyIdForMinting() {
            var ctx = ScriptContextBuilder.minting(PolicyId.of(POLICY_ID_1))
                    .build();
            byte[] result = eval.call("ownHash", ctx).asByteString();
            assertArrayEquals(POLICY_ID_1, result);
        }

        @Test
        void returnsScriptHashForSpending() {
            var ref = txOutRef(TX_HASH_1, 0);
            var ownInput = new TxInInfo(ref, simpleTxOut(scriptAddress(SCRIPT_HASH_1), 5_000_000));
            var ctx = ScriptContextBuilder.spending(ref)
                    .input(ownInput)
                    .build();
            byte[] result = eval.call("ownHash", ctx).asByteString();
            assertArrayEquals(SCRIPT_HASH_1, result);
        }
    }

    @Nested
    class OwnInputScriptHash {

        @Test
        void extractsScriptHash() {
            var ref = txOutRef(TX_HASH_1, 0);
            var ownInput = new TxInInfo(ref, simpleTxOut(scriptAddress(SCRIPT_HASH_1), 5_000_000));
            var ctx = ScriptContextBuilder.spending(ref)
                    .input(ownInput)
                    .build();
            byte[] result = eval.call("ownInputScriptHash", ctx).asByteString();
            assertArrayEquals(SCRIPT_HASH_1, result);
        }

        @Test
        void worksWithPubKeyAddress() {
            var ref = txOutRef(TX_HASH_1, 0);
            var ownInput = new TxInInfo(ref, simpleTxOut(pubKeyAddress(PKH_1), 5_000_000));
            var ctx = ScriptContextBuilder.spending(ref)
                    .input(ownInput)
                    .build();
            // credentialHash extracts the hash regardless of credential type
            byte[] result = eval.call("ownInputScriptHash", ctx).asByteString();
            assertArrayEquals(PKH_1, result);
        }
    }

    // =========================================================================
    // Group 6: JulcList Returns
    // =========================================================================

    @Nested
    class GetContinuingOutputs {

        @Test
        void returnsMatchingOutputs() {
            var ownAddr = scriptAddress(SCRIPT_HASH_1);
            var otherAddr = pubKeyAddress(PKH_1);
            var ref = txOutRef(TX_HASH_1, 0);
            var ownInput = new TxInInfo(ref, simpleTxOut(ownAddr, 10_000_000));
            var ctx = ScriptContextBuilder.spending(ref)
                    .input(ownInput)
                    .output(simpleTxOut(ownAddr, 5_000_000))   // matching
                    .output(simpleTxOut(otherAddr, 3_000_000)) // non-matching
                    .output(simpleTxOut(ownAddr, 2_000_000))   // matching
                    .build();
            List<PlutusData> result = eval.call("getContinuingOutputs", ctx).asList();
            assertEquals(2, result.size());
        }

        @Test
        void noMatchingOutputs() {
            var ownAddr = scriptAddress(SCRIPT_HASH_1);
            var otherAddr = pubKeyAddress(PKH_1);
            var ref = txOutRef(TX_HASH_1, 0);
            var ownInput = new TxInInfo(ref, simpleTxOut(ownAddr, 10_000_000));
            var ctx = ScriptContextBuilder.spending(ref)
                    .input(ownInput)
                    .output(simpleTxOut(otherAddr, 5_000_000))
                    .build();
            List<PlutusData> result = eval.call("getContinuingOutputs", ctx).asList();
            assertTrue(result.isEmpty());
        }

        @Test
        void allOutputsMatch() {
            var ownAddr = scriptAddress(SCRIPT_HASH_1);
            var ref = txOutRef(TX_HASH_1, 0);
            var ownInput = new TxInInfo(ref, simpleTxOut(ownAddr, 10_000_000));
            var ctx = ScriptContextBuilder.spending(ref)
                    .input(ownInput)
                    .output(simpleTxOut(ownAddr, 5_000_000))
                    .output(simpleTxOut(ownAddr, 3_000_000))
                    .build();
            List<PlutusData> result = eval.call("getContinuingOutputs", ctx).asList();
            assertEquals(2, result.size());
        }
    }

    @Nested
    class ValueSpent {

        @Test
        void collectsAllInputValues() {
            var in1 = simpleTxInInfo(TX_HASH_1, 0, pubKeyAddress(PKH_1), 5_000_000);
            var in2 = simpleTxInInfo(TX_HASH_1, 1, pubKeyAddress(PKH_2), 3_000_000);
            var in3 = simpleTxInInfo(TX_HASH_2, 0, pubKeyAddress(PKH_3), 1_000_000);
            var ctx = ScriptContextBuilder.minting(PolicyId.of(POLICY_ID_1))
                    .input(in1).input(in2).input(in3)
                    .build();
            List<PlutusData> result = eval.call("valueSpent", ctx.txInfo()).asList();
            assertEquals(3, result.size());
        }

        @Test
        void emptyInputsReturnsEmptyList() {
            var ctx = ScriptContextBuilder.minting(PolicyId.of(POLICY_ID_1)).build();
            List<PlutusData> result = eval.call("valueSpent", ctx.txInfo()).asList();
            assertTrue(result.isEmpty());
        }
    }

    @Nested
    class ValuePaid {

        @Test
        void filtersByAddress() {
            var addr1 = pubKeyAddress(PKH_1);
            var addr2 = pubKeyAddress(PKH_2);
            var ctx = ScriptContextBuilder.minting(PolicyId.of(POLICY_ID_1))
                    .output(simpleTxOut(addr1, 5_000_000))
                    .output(simpleTxOut(addr2, 3_000_000))
                    .output(simpleTxOut(addr1, 2_000_000))
                    .output(simpleTxOut(addr2, 1_000_000))
                    .build();
            List<PlutusData> result = eval.call("valuePaid", ctx.txInfo(), addr1).asList();
            assertEquals(2, result.size());
        }

        @Test
        void noMatchesReturnsEmpty() {
            var addr1 = pubKeyAddress(PKH_1);
            var addr2 = pubKeyAddress(PKH_2);
            var ctx = ScriptContextBuilder.minting(PolicyId.of(POLICY_ID_1))
                    .output(simpleTxOut(addr1, 5_000_000))
                    .build();
            List<PlutusData> result = eval.call("valuePaid", ctx.txInfo(), addr2).asList();
            assertTrue(result.isEmpty());
        }

        @Test
        void emptyOutputsReturnsEmpty() {
            var addr1 = pubKeyAddress(PKH_1);
            var ctx = ScriptContextBuilder.minting(PolicyId.of(POLICY_ID_1)).build();
            List<PlutusData> result = eval.call("valuePaid", ctx.txInfo(), addr1).asList();
            assertTrue(result.isEmpty());
        }
    }

    @Nested
    class ScriptOutputsAt {

        @Test
        void filtersScriptAddresses() {
            var scriptAddr = scriptAddress(SCRIPT_HASH_1);
            var pkAddr = pubKeyAddress(PKH_1);
            var otherScriptAddr = scriptAddress(SCRIPT_HASH_2);
            var ctx = ScriptContextBuilder.minting(PolicyId.of(POLICY_ID_1))
                    .output(simpleTxOut(scriptAddr, 5_000_000))
                    .output(simpleTxOut(pkAddr, 3_000_000))
                    .output(simpleTxOut(scriptAddr, 2_000_000))
                    .output(simpleTxOut(otherScriptAddr, 1_000_000))
                    .build();
            List<PlutusData> result = eval.call("scriptOutputsAt",
                    ctx.txInfo(), SCRIPT_HASH_1).asList();
            assertEquals(2, result.size());
        }

        @Test
        void noScriptOutputsReturnsEmpty() {
            var pkAddr = pubKeyAddress(PKH_1);
            var ctx = ScriptContextBuilder.minting(PolicyId.of(POLICY_ID_1))
                    .output(simpleTxOut(pkAddr, 5_000_000))
                    .build();
            List<PlutusData> result = eval.call("scriptOutputsAt",
                    ctx.txInfo(), SCRIPT_HASH_1).asList();
            assertTrue(result.isEmpty());
        }

        @Test
        void wrongHashReturnsEmpty() {
            var scriptAddr = scriptAddress(SCRIPT_HASH_1);
            var ctx = ScriptContextBuilder.minting(PolicyId.of(POLICY_ID_1))
                    .output(simpleTxOut(scriptAddr, 5_000_000))
                    .build();
            List<PlutusData> result = eval.call("scriptOutputsAt",
                    ctx.txInfo(), SCRIPT_HASH_2).asList();
            assertTrue(result.isEmpty());
        }
    }

    // listIndex is not tested directly — its PlutusData.ListData parameter type
    // causes a known compiler limitation when used as compileMethod entry point
    // (ListData subtype triggers wrap instead of unwrap). It is tested indirectly
    // through methods that call it internally.
}
