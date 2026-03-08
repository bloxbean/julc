package com.bloxbean.cardano.julc.stdlib.lib;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.testkit.JulcEval;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Optional;

import static com.bloxbean.cardano.julc.stdlib.lib.StdlibTestHelpers.*;
import static org.junit.jupiter.api.Assertions.*;

class OutputLibTest {

    static JulcEval eval;

    static final byte[] PKH_1 = makeBytes(28, 0x01);
    static final byte[] PKH_2 = makeBytes(28, 0x02);
    static final byte[] SCRIPT_HASH_1 = makeBytes(28, 0x10);
    static final byte[] TX_HASH_1 = makeBytes(32, 0xAA);
    static final byte[] POLICY_A = makeBytes(28, 0xAA);
    static final byte[] TOKEN_X = "TokenX".getBytes();

    @BeforeAll
    static void setUp() {
        eval = JulcEval.forClass(OutputLib.class);
    }

    // --- OutputLib-specific helpers ---

    static TxOut txOutWithDatum(Address addr, long lovelace, OutputDatum datum) {
        return new TxOut(addr, Value.lovelace(BigInteger.valueOf(lovelace)),
                datum, Optional.empty());
    }

    static TxOut txOutWithToken(Address addr, long lovelace, byte[] policy, byte[] token, long qty) {
        Value v = Value.lovelace(BigInteger.valueOf(lovelace))
                .merge(Value.singleton(PolicyId.of(policy), TokenName.of(token), BigInteger.valueOf(qty)));
        return new TxOut(addr, v, new OutputDatum.NoOutputDatum(), Optional.empty());
    }

    // =========================================================================
    // TxOut Field Accessors
    // =========================================================================

    @Nested
    class FieldAccessors {

        @Test
        void txOutAddressReturnsAddress() {
            TxOut txOut = simpleTxOut(pubKeyAddress(PKH_1), 2_000_000);
            PlutusData result = eval.call("txOutAddress", txOut).asData();
            assertNotNull(result);
            assertInstanceOf(PlutusData.ConstrData.class, result);
        }

        @Test
        void txOutValueReturnsValue() {
            TxOut txOut = simpleTxOut(pubKeyAddress(PKH_1), 2_000_000);
            PlutusData result = eval.call("txOutValue", txOut).asData();
            assertNotNull(result);
            assertInstanceOf(PlutusData.MapData.class, result);
        }

        @Test
        void txOutDatumReturnsDatum() {
            TxOut txOut = simpleTxOut(pubKeyAddress(PKH_1), 2_000_000);
            PlutusData result = eval.call("txOutDatum", txOut).asData();
            assertNotNull(result);
            // NoOutputDatum = ConstrData(0, [])
            assertInstanceOf(PlutusData.ConstrData.class, result);
            assertEquals(0, ((PlutusData.ConstrData) result).tag());
        }
    }

    // =========================================================================
    // Output Filtering
    // =========================================================================

    @Nested
    class OutputsAt {

        @Test
        void filtersMatchingOutputs() {
            Address addr1 = pubKeyAddress(PKH_1);
            Address addr2 = pubKeyAddress(PKH_2);
            PlutusData outputs = txOutList(
                    simpleTxOut(addr1, 2_000_000),
                    simpleTxOut(addr2, 3_000_000),
                    simpleTxOut(addr1, 1_000_000));
            var result = eval.call("outputsAt", outputs, addr1).asList();
            assertEquals(2, result.size());
        }

        @Test
        void noMatchReturnsEmpty() {
            Address addr1 = pubKeyAddress(PKH_1);
            Address addr2 = pubKeyAddress(PKH_2);
            PlutusData outputs = txOutList(simpleTxOut(addr2, 3_000_000));
            var result = eval.call("outputsAt", outputs, addr1).asList();
            assertEquals(0, result.size());
        }
    }

    @Nested
    class CountOutputsAt {

        @Test
        void countsMatches() {
            Address addr1 = pubKeyAddress(PKH_1);
            Address addr2 = pubKeyAddress(PKH_2);
            PlutusData outputs = txOutList(
                    simpleTxOut(addr1, 2_000_000),
                    simpleTxOut(addr2, 3_000_000),
                    simpleTxOut(addr1, 1_000_000));
            assertEquals(2, eval.call("countOutputsAt", outputs, addr1).asLong());
        }

        @Test
        void zeroForNoMatch() {
            Address addr1 = pubKeyAddress(PKH_1);
            Address addr2 = pubKeyAddress(PKH_2);
            PlutusData outputs = txOutList(simpleTxOut(addr2, 3_000_000));
            assertEquals(0, eval.call("countOutputsAt", outputs, addr1).asLong());
        }
    }

    @Nested
    class UniqueOutputAt {

        @Test
        void returnsWhenExactlyOne() {
            Address addr = pubKeyAddress(PKH_1);
            PlutusData outputs = txOutList(
                    simpleTxOut(pubKeyAddress(PKH_2), 3_000_000),
                    simpleTxOut(addr, 2_000_000));
            PlutusData result = eval.call("uniqueOutputAt", outputs, addr).asData();
            assertNotNull(result);
            assertInstanceOf(PlutusData.ConstrData.class, result);
        }
    }

    // =========================================================================
    // Token Filtering
    // =========================================================================

    @Nested
    class TokenFiltering {

        @Test
        void outputsWithTokenFindsMatches() {
            Address addr = pubKeyAddress(PKH_1);
            PlutusData outputs = txOutList(
                    simpleTxOut(addr, 2_000_000),
                    txOutWithToken(addr, 3_000_000, POLICY_A, TOKEN_X, 1));
            var result = eval.call("outputsWithToken", outputs, POLICY_A, TOKEN_X).asList();
            assertEquals(1, result.size());
        }

        @Test
        void outputsWithTokenEmptyWhenNoMatch() {
            Address addr = pubKeyAddress(PKH_1);
            PlutusData outputs = txOutList(simpleTxOut(addr, 2_000_000));
            var result = eval.call("outputsWithToken", outputs, POLICY_A, TOKEN_X).asList();
            assertEquals(0, result.size());
        }

        @Test
        void valueHasTokenTrue() {
            Value v = Value.lovelace(BigInteger.valueOf(2_000_000))
                    .merge(Value.singleton(PolicyId.of(POLICY_A), TokenName.of(TOKEN_X), BigInteger.ONE));
            assertTrue(eval.call("valueHasToken", v, POLICY_A, TOKEN_X).asBoolean());
        }

        @Test
        void valueHasTokenFalse() {
            Value v = Value.lovelace(BigInteger.valueOf(2_000_000));
            assertFalse(eval.call("valueHasToken", v, POLICY_A, TOKEN_X).asBoolean());
        }
    }

    // =========================================================================
    // Value Summation
    // =========================================================================

    @Nested
    class ValueSummation {

        @Test
        void lovelacePaidToSumsMatches() {
            Address addr1 = pubKeyAddress(PKH_1);
            Address addr2 = pubKeyAddress(PKH_2);
            PlutusData outputs = txOutList(
                    simpleTxOut(addr1, 2_000_000),
                    simpleTxOut(addr2, 3_000_000),
                    simpleTxOut(addr1, 1_000_000));
            assertEquals(BigInteger.valueOf(3_000_000),
                    eval.call("lovelacePaidTo", outputs, addr1).asInteger());
        }

        @Test
        void lovelacePaidToZeroForNoMatch() {
            Address addr1 = pubKeyAddress(PKH_1);
            Address addr2 = pubKeyAddress(PKH_2);
            PlutusData outputs = txOutList(simpleTxOut(addr2, 3_000_000));
            assertEquals(BigInteger.ZERO,
                    eval.call("lovelacePaidTo", outputs, addr1).asInteger());
        }

        @Test
        void paidAtLeastSufficient() {
            Address addr = pubKeyAddress(PKH_1);
            PlutusData outputs = txOutList(
                    simpleTxOut(addr, 3_000_000),
                    simpleTxOut(addr, 2_000_000));
            assertTrue(eval.call("paidAtLeast", outputs, addr, BigInteger.valueOf(4_000_000)).asBoolean());
        }

        @Test
        void paidAtLeastInsufficient() {
            Address addr = pubKeyAddress(PKH_1);
            PlutusData outputs = txOutList(simpleTxOut(addr, 1_000_000));
            assertFalse(eval.call("paidAtLeast", outputs, addr, BigInteger.valueOf(5_000_000)).asBoolean());
        }
    }

    // =========================================================================
    // Datum Extraction
    // =========================================================================

    @Nested
    class DatumExtraction {

        @Test
        void getInlineDatumReturnsData() {
            Address addr = pubKeyAddress(PKH_1);
            PlutusData datumValue = PlutusData.integer(42);
            TxOut txOut = txOutWithDatum(addr, 2_000_000,
                    new OutputDatum.OutputDatumInline(datumValue));
            PlutusData result = eval.call("getInlineDatum", txOut).asData();
            assertInstanceOf(PlutusData.IntData.class, result);
            assertEquals(BigInteger.valueOf(42), ((PlutusData.IntData) result).value());
        }
    }

    // =========================================================================
    // findOutputWithToken
    // =========================================================================

    @Nested
    class FindOutputWithToken {

        @Test
        void findsMatchingOutput() {
            Address scriptAddr = scriptAddress(SCRIPT_HASH_1);
            TxOut target = txOutWithToken(scriptAddr, 2_000_000, POLICY_A, TOKEN_X, 1);
            PlutusData outputs = txOutList(
                    simpleTxOut(pubKeyAddress(PKH_1), 1_000_000),
                    target);
            PlutusData result = eval.call("findOutputWithToken",
                    outputs, SCRIPT_HASH_1, POLICY_A, TOKEN_X).asData();
            assertNotNull(result);
            assertInstanceOf(PlutusData.ConstrData.class, result);
        }
    }

    // =========================================================================
    // findInputWithToken
    // =========================================================================

    @Nested
    class FindInputWithToken {

        @Test
        void findsMatchingInput() {
            Address scriptAddr = scriptAddress(SCRIPT_HASH_1);
            TxOut resolvedOut = txOutWithToken(scriptAddr, 2_000_000, POLICY_A, TOKEN_X, 1);
            TxInInfo target = new TxInInfo(
                    new TxOutRef(new TxId(TX_HASH_1), BigInteger.ZERO),
                    resolvedOut);
            TxInInfo other = new TxInInfo(
                    new TxOutRef(new TxId(TX_HASH_1), BigInteger.ONE),
                    simpleTxOut(pubKeyAddress(PKH_1), 1_000_000));
            PlutusData inputs = txInInfoList(other, target);
            PlutusData result = eval.call("findInputWithToken",
                    inputs, SCRIPT_HASH_1, POLICY_A, TOKEN_X).asData();
            assertNotNull(result);
            assertInstanceOf(PlutusData.ConstrData.class, result);
        }
    }
}
