package com.bloxbean.cardano.plutus.testkit;

import com.bloxbean.cardano.plutus.core.PlutusData;
import com.bloxbean.cardano.plutus.onchain.ledger.*;
import com.bloxbean.cardano.plutus.onchain.stdlib.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for executable onchain stubs.
 * <p>
 * Verifies that ContextsLib, ValuesLib, IntervalLib, ListsLib, CryptoLib,
 * and factory methods work correctly as plain Java (off-chain/debugging mode).
 */
class ExecutableStubsTest {

    @BeforeAll
    static void setup() {
        CryptoLib.setProvider(new JvmCryptoProvider());
    }

    // --- ContextsLib ---

    @Nested
    class ContextsLibTests {

        private TxInfo sampleTxInfo(byte[]... signatories) {
            return new TxInfo(
                    List.of(),              // inputs
                    List.of(),              // referenceInputs
                    List.of(),              // outputs
                    BigInteger.valueOf(200000), // fee
                    Value.zero(),           // mint
                    List.of(),              // certificates
                    Map.of(),               // withdrawals
                    Interval.always(),      // validRange
                    List.of(signatories),   // signatories
                    Map.of(),               // redeemers
                    Map.of(),               // datums
                    new byte[32],           // id
                    Map.of(),               // votes
                    List.of(),              // proposalProcedures
                    Optional.empty(),       // currentTreasuryAmount
                    Optional.empty()        // treasuryDonation
            );
        }

        @Test
        void getTxInfoExtractsTxInfo() {
            var txInfo = sampleTxInfo();
            var ctx = new ScriptContext(txInfo, PlutusData.UNIT,
                    new ScriptInfo.MintingScript(new byte[28]));
            assertEquals(txInfo, ContextsLib.getTxInfo(ctx));
        }

        @Test
        void getRedeemerExtractsRedeemer() {
            var redeemer = PlutusData.integer(42);
            var ctx = new ScriptContext(sampleTxInfo(), redeemer,
                    new ScriptInfo.MintingScript(new byte[28]));
            assertEquals(redeemer, ContextsLib.getRedeemer(ctx));
        }

        @Test
        void signedByReturnsTrueWhenPresent() {
            var pkh = new byte[]{1, 2, 3, 4, 5};
            var txInfo = sampleTxInfo(pkh);
            assertTrue(ContextsLib.signedBy(txInfo, pkh));
        }

        @Test
        void signedByReturnsFalseWhenAbsent() {
            var pkh = new byte[]{1, 2, 3, 4, 5};
            var other = new byte[]{9, 8, 7, 6, 5};
            var txInfo = sampleTxInfo(other);
            assertFalse(ContextsLib.signedBy(txInfo, pkh));
        }

        @Test
        void signedByHandlesMultipleSignatories() {
            var pkh1 = new byte[]{1, 2, 3};
            var pkh2 = new byte[]{4, 5, 6};
            var pkh3 = new byte[]{7, 8, 9};
            var txInfo = sampleTxInfo(pkh1, pkh2, pkh3);
            assertTrue(ContextsLib.signedBy(txInfo, pkh2));
        }

        @Test
        void txInfoValidRangeReturnsRange() {
            var range = Interval.after(BigInteger.valueOf(1000));
            var txInfo = new TxInfo(
                    List.of(), List.of(), List.of(), BigInteger.ZERO,
                    Value.zero(), List.of(), Map.of(), range,
                    List.of(), Map.of(), Map.of(), new byte[32],
                    Map.of(), List.of(), Optional.empty(), Optional.empty());
            assertEquals(range, ContextsLib.txInfoValidRange(txInfo));
        }

        @Test
        void getSpendingDatumReturnsForSpending() {
            var datum = PlutusData.integer(99);
            var ctx = new ScriptContext(sampleTxInfo(), PlutusData.UNIT,
                    new ScriptInfo.SpendingScript(PlutusData.UNIT, datum));
            assertEquals(datum, ContextsLib.getSpendingDatum(ctx));
        }

        @Test
        void getSpendingDatumReturnsUnitForMinting() {
            var ctx = new ScriptContext(sampleTxInfo(), PlutusData.UNIT,
                    new ScriptInfo.MintingScript(new byte[28]));
            assertEquals(PlutusData.UNIT, ContextsLib.getSpendingDatum(ctx));
        }
    }

    // --- ValuesLib ---

    @Nested
    class ValuesLibTests {

        @Test
        void lovelaceOfExtractsAda() {
            var value = Value.lovelace(BigInteger.valueOf(5_000_000));
            assertEquals(BigInteger.valueOf(5_000_000), ValuesLib.lovelaceOf(value));
        }

        @Test
        void lovelaceOfReturnsZeroForEmpty() {
            var value = Value.zero();
            assertEquals(BigInteger.ZERO, ValuesLib.lovelaceOf(value));
        }

        @Test
        void assetOfExtractsNativeToken() {
            var policyId = new byte[]{1, 2, 3};
            var tokenName = new byte[]{4, 5, 6};
            var value = Value.of(policyId, tokenName, BigInteger.valueOf(100));
            assertEquals(BigInteger.valueOf(100), ValuesLib.assetOf(value, policyId, tokenName));
        }

        @Test
        void assetOfReturnsZeroForMissing() {
            var value = Value.lovelace(BigInteger.valueOf(1000));
            assertEquals(BigInteger.ZERO,
                    ValuesLib.assetOf(value, new byte[]{1, 2, 3}, new byte[]{4, 5}));
        }

        @Test
        void geqReturnsTrueWhenGreater() {
            var a = Value.lovelace(BigInteger.valueOf(5_000_000));
            var b = Value.lovelace(BigInteger.valueOf(2_000_000));
            assertTrue(ValuesLib.geq(a, b));
        }

        @Test
        void geqReturnsTrueWhenEqual() {
            var v = Value.lovelace(BigInteger.valueOf(5_000_000));
            assertTrue(ValuesLib.geq(v, v));
        }

        @Test
        void geqReturnsFalseWhenLess() {
            var a = Value.lovelace(BigInteger.valueOf(2_000_000));
            var b = Value.lovelace(BigInteger.valueOf(5_000_000));
            assertFalse(ValuesLib.geq(a, b));
        }

        @Test
        void assetOfWithMixedValue() {
            var policyId = new byte[]{10, 20, 30};
            var tokenName = new byte[]{40, 50};
            var value = Value.withLovelace(BigInteger.valueOf(2_000_000),
                    policyId, tokenName, BigInteger.valueOf(50));

            assertEquals(BigInteger.valueOf(2_000_000), ValuesLib.lovelaceOf(value));
            assertEquals(BigInteger.valueOf(50), ValuesLib.assetOf(value, policyId, tokenName));
        }
    }

    // --- IntervalLib ---

    @Nested
    class IntervalLibTests {

        @Test
        void alwaysContainsAnyTime() {
            var interval = IntervalLib.always();
            assertTrue(IntervalLib.contains(interval, BigInteger.ZERO));
            assertTrue(IntervalLib.contains(interval, BigInteger.valueOf(1_000_000)));
            assertTrue(IntervalLib.contains(interval, BigInteger.valueOf(-1_000_000)));
        }

        @Test
        void afterContainsTimeAtBound() {
            var interval = IntervalLib.after(BigInteger.valueOf(1000));
            assertTrue(IntervalLib.contains(interval, BigInteger.valueOf(1000)));
        }

        @Test
        void afterContainsTimePastBound() {
            var interval = IntervalLib.after(BigInteger.valueOf(1000));
            assertTrue(IntervalLib.contains(interval, BigInteger.valueOf(2000)));
        }

        @Test
        void afterExcludesTimeBeforeBound() {
            var interval = IntervalLib.after(BigInteger.valueOf(1000));
            assertFalse(IntervalLib.contains(interval, BigInteger.valueOf(500)));
        }

        @Test
        void beforeContainsTimeAtBound() {
            var interval = IntervalLib.before(BigInteger.valueOf(1000));
            assertTrue(IntervalLib.contains(interval, BigInteger.valueOf(1000)));
        }

        @Test
        void beforeExcludesTimePastBound() {
            var interval = IntervalLib.before(BigInteger.valueOf(1000));
            assertFalse(IntervalLib.contains(interval, BigInteger.valueOf(2000)));
        }

        @Test
        void betweenContainsTimeInRange() {
            var interval = Interval.between(BigInteger.valueOf(100), BigInteger.valueOf(200));
            assertTrue(IntervalLib.contains(interval, BigInteger.valueOf(150)));
        }

        @Test
        void betweenExcludesTimeOutOfRange() {
            var interval = Interval.between(BigInteger.valueOf(100), BigInteger.valueOf(200));
            assertFalse(IntervalLib.contains(interval, BigInteger.valueOf(300)));
            assertFalse(IntervalLib.contains(interval, BigInteger.valueOf(50)));
        }
    }

    // --- ListsLib ---

    @Nested
    class ListsLibTests {

        @Test
        void containsFindsElement() {
            var list = new PlutusData.ListData(List.of(
                    PlutusData.integer(1),
                    PlutusData.integer(2),
                    PlutusData.integer(3)));
            assertTrue(ListsLib.contains(list, PlutusData.integer(2)));
        }

        @Test
        void containsReturnsFalseForMissing() {
            var list = new PlutusData.ListData(List.of(
                    PlutusData.integer(1),
                    PlutusData.integer(2)));
            assertFalse(ListsLib.contains(list, PlutusData.integer(42)));
        }

        @Test
        void lengthReturnsCorrectCount() {
            var list = new PlutusData.ListData(List.of(
                    PlutusData.integer(1),
                    PlutusData.integer(2),
                    PlutusData.integer(3)));
            assertEquals(BigInteger.valueOf(3), ListsLib.length(list));
        }

        @Test
        void isEmptyReturnsTrueForEmpty() {
            var list = new PlutusData.ListData(List.of());
            assertTrue(ListsLib.isEmpty(list));
        }

        @Test
        void isEmptyReturnsFalseForNonEmpty() {
            var list = new PlutusData.ListData(List.of(PlutusData.integer(1)));
            assertFalse(ListsLib.isEmpty(list));
        }

        @Test
        void headReturnsFirstElement() {
            var list = new PlutusData.ListData(List.of(
                    PlutusData.integer(10),
                    PlutusData.integer(20)));
            assertEquals(PlutusData.integer(10), ListsLib.head(list));
        }
    }

    // --- CryptoLib ---

    @Nested
    class CryptoLibTests {

        @Test
        void sha2_256ProducesHash() {
            var data = new PlutusData.BytesData(new byte[]{1, 2, 3});
            var hash = CryptoLib.sha2_256(data);
            assertInstanceOf(PlutusData.BytesData.class, hash);
            assertEquals(32, ((PlutusData.BytesData) hash).value().length);
        }

        @Test
        void blake2b_256ProducesHash() {
            var data = new PlutusData.BytesData(new byte[]{1, 2, 3});
            var hash = CryptoLib.blake2b_256(data);
            assertInstanceOf(PlutusData.BytesData.class, hash);
            assertEquals(32, ((PlutusData.BytesData) hash).value().length);
        }

        @Test
        void sameInputProducesSameHash() {
            var data1 = new PlutusData.BytesData(new byte[]{1, 2, 3});
            var data2 = new PlutusData.BytesData(new byte[]{1, 2, 3});
            var hash1 = CryptoLib.sha2_256(data1);
            var hash2 = CryptoLib.sha2_256(data2);
            assertArrayEquals(
                    ((PlutusData.BytesData) hash1).value(),
                    ((PlutusData.BytesData) hash2).value());
        }

        @Test
        void differentInputProducesDifferentHash() {
            var data1 = new PlutusData.BytesData(new byte[]{1, 2, 3});
            var data2 = new PlutusData.BytesData(new byte[]{4, 5, 6});
            var hash1 = CryptoLib.sha2_256(data1);
            var hash2 = CryptoLib.sha2_256(data2);
            assertFalse(java.util.Arrays.equals(
                    ((PlutusData.BytesData) hash1).value(),
                    ((PlutusData.BytesData) hash2).value()));
        }
    }

    // --- Value factory methods ---

    @Nested
    class ValueFactoryTests {

        @Test
        void lovelaceCreatesCorrectValue() {
            var value = Value.lovelace(BigInteger.valueOf(5_000_000));
            assertEquals(BigInteger.valueOf(5_000_000), ValuesLib.lovelaceOf(value));
        }

        @Test
        void zeroCreatesEmptyValue() {
            var value = Value.zero();
            assertEquals(BigInteger.ZERO, ValuesLib.lovelaceOf(value));
        }

        @Test
        void ofCreatesNativeAssetValue() {
            var pid = new byte[]{1, 2, 3};
            var tn = new byte[]{4, 5, 6};
            var value = Value.of(pid, tn, BigInteger.valueOf(100));
            assertEquals(BigInteger.valueOf(100), ValuesLib.assetOf(value, pid, tn));
        }

        @Test
        void withLovelaceCreatesMixedValue() {
            var pid = new byte[]{10, 20};
            var tn = new byte[]{30, 40};
            var value = Value.withLovelace(
                    BigInteger.valueOf(2_000_000),
                    pid, tn, BigInteger.valueOf(50));
            assertEquals(BigInteger.valueOf(2_000_000), ValuesLib.lovelaceOf(value));
            assertEquals(BigInteger.valueOf(50), ValuesLib.assetOf(value, pid, tn));
        }
    }

    // --- Interval factory methods ---

    @Nested
    class IntervalFactoryTests {

        @Test
        void alwaysFactoryWorks() {
            var interval = Interval.always();
            assertTrue(IntervalLib.contains(interval, BigInteger.ZERO));
        }

        @Test
        void afterFactoryWorks() {
            var interval = Interval.after(BigInteger.valueOf(500));
            assertTrue(IntervalLib.contains(interval, BigInteger.valueOf(500)));
            assertFalse(IntervalLib.contains(interval, BigInteger.valueOf(499)));
        }

        @Test
        void beforeFactoryWorks() {
            var interval = Interval.before(BigInteger.valueOf(500));
            assertTrue(IntervalLib.contains(interval, BigInteger.valueOf(500)));
            assertFalse(IntervalLib.contains(interval, BigInteger.valueOf(501)));
        }

        @Test
        void betweenFactoryWorks() {
            var interval = Interval.between(BigInteger.valueOf(100), BigInteger.valueOf(200));
            assertTrue(IntervalLib.contains(interval, BigInteger.valueOf(100)));
            assertTrue(IntervalLib.contains(interval, BigInteger.valueOf(200)));
            assertFalse(IntervalLib.contains(interval, BigInteger.valueOf(99)));
            assertFalse(IntervalLib.contains(interval, BigInteger.valueOf(201)));
        }
    }
}
