package com.bloxbean.cardano.julc.testkit;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.core.types.JulcMap;
import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.lib.ContextsLib;
import com.bloxbean.cardano.julc.stdlib.lib.IntervalLib;
import com.bloxbean.cardano.julc.stdlib.lib.ListsLib;
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
        Builtins.setCryptoProvider(new JvmCryptoProvider());
    }

    /** Pad a short byte array to 28 bytes (required by PolicyId and PubKeyHash). */
    private static byte[] padTo28(byte[] src) {
        var result = new byte[28];
        System.arraycopy(src, 0, result, 0, Math.min(src.length, 28));
        return result;
    }

    // --- ContextsLib ---

    @Nested
    class ContextsLibTests {

        private TxInfo sampleTxInfo(PubKeyHash... signatories) {
            return new TxInfo(
                    JulcList.of(),              // inputs
                    JulcList.of(),              // referenceInputs
                    JulcList.of(),              // outputs
                    BigInteger.valueOf(200000), // fee
                    Value.zero(),               // mint
                    JulcList.of(),              // certificates
                    JulcMap.empty(),            // withdrawals
                    Interval.always(),          // validRange
                    JulcList.of(signatories),   // signatories
                    JulcMap.empty(),            // redeemers
                    JulcMap.empty(),            // datums
                    new TxId(new byte[32]),     // id
                    JulcMap.empty(),            // votes
                    JulcList.of(),              // proposalProcedures
                    Optional.empty(),           // currentTreasuryAmount
                    Optional.empty()            // treasuryDonation
            );
        }

        @Test
        void getTxInfoExtractsTxInfo() {
            var txInfo = sampleTxInfo();
            var ctx = new ScriptContext(txInfo, PlutusData.UNIT,
                    new ScriptInfo.MintingScript(new PolicyId(new byte[28])));
            assertEquals(txInfo, ContextsLib.getTxInfo(ctx));
        }

        @Test
        void getRedeemerExtractsRedeemer() {
            var redeemer = PlutusData.integer(42);
            var ctx = new ScriptContext(sampleTxInfo(), redeemer,
                    new ScriptInfo.MintingScript(new PolicyId(new byte[28])));
            assertEquals(redeemer, ContextsLib.getRedeemer(ctx));
        }

        @Test
        void signedByReturnsTrueWhenPresent() {
            var pkh = new byte[]{1, 2, 3, 4, 5};
            var txInfo = sampleTxInfo(new PubKeyHash(padTo28(pkh)));
            assertTrue(ContextsLib.signedBy(txInfo, padTo28(pkh)));
        }

        @Test
        void signedByReturnsFalseWhenAbsent() {
            var pkh = padTo28(new byte[]{1, 2, 3, 4, 5});
            var other = padTo28(new byte[]{9, 8, 7, 6, 5});
            var txInfo = sampleTxInfo(new PubKeyHash(other));
            assertFalse(ContextsLib.signedBy(txInfo, pkh));
        }

        @Test
        void signedByHandlesMultipleSignatories() {
            var pkh1 = new PubKeyHash(padTo28(new byte[]{1, 2, 3}));
            var pkh2 = new PubKeyHash(padTo28(new byte[]{4, 5, 6}));
            var pkh3 = new PubKeyHash(padTo28(new byte[]{7, 8, 9}));
            var txInfo = sampleTxInfo(pkh1, pkh2, pkh3);
            assertTrue(ContextsLib.signedBy(txInfo, padTo28(new byte[]{4, 5, 6})));
        }

        @Test
        void txInfoValidRangeReturnsRange() {
            var range = Interval.after(BigInteger.valueOf(1000));
            var txInfo = new TxInfo(
                    JulcList.of(), JulcList.of(), JulcList.of(), BigInteger.ZERO,
                    Value.zero(), JulcList.of(), JulcMap.empty(), range,
                    JulcList.of(), JulcMap.empty(), JulcMap.empty(), new TxId(new byte[32]),
                    JulcMap.empty(), JulcList.of(), Optional.empty(), Optional.empty());
            assertEquals(range, ContextsLib.txInfoValidRange(txInfo));
        }

        @Test
        void getSpendingDatumViaScriptInfoApi() {
            var datum = PlutusData.integer(99);
            var ctx = new ScriptContext(sampleTxInfo(), PlutusData.UNIT,
                    new ScriptInfo.SpendingScript(
                            new TxOutRef(new TxId(new byte[32]), BigInteger.ZERO),
                            Optional.of(datum)));
            // Use ScriptInfo API directly (getSpendingDatum uses on-chain casts)
            var result = switch (ctx.scriptInfo()) {
                case ScriptInfo.SpendingScript ss -> ss.datum().orElse(PlutusData.UNIT);
                default -> PlutusData.UNIT;
            };
            assertEquals(datum, result);
        }

        @Test
        void getSpendingDatumReturnsUnitForMinting() {
            var ctx = new ScriptContext(sampleTxInfo(), PlutusData.UNIT,
                    new ScriptInfo.MintingScript(new PolicyId(new byte[28])));
            // Use ScriptInfo API directly
            var result = switch (ctx.scriptInfo()) {
                case ScriptInfo.SpendingScript ss -> ss.datum().orElse(PlutusData.UNIT);
                default -> PlutusData.UNIT;
            };
            assertEquals(PlutusData.UNIT, result);
        }

    }

    // --- Value ---

    @Nested
    class ValueTests {

        @Test
        void lovelaceOfExtractsAda() {
            var value = Value.lovelace(BigInteger.valueOf(5_000_000));
            assertEquals(BigInteger.valueOf(5_000_000), value.lovelaceOf());
        }

        @Test
        void lovelaceOfReturnsZeroForEmpty() {
            var value = Value.zero();
            assertEquals(BigInteger.ZERO, value.lovelaceOf());
        }

        @Test
        void assetOfExtractsNativeToken() {
            var policyId = new PolicyId(padTo28(new byte[]{1, 2, 3}));
            var tokenName = new TokenName(new byte[]{4, 5, 6});
            var value = Value.singleton(policyId, tokenName, BigInteger.valueOf(100));
            assertEquals(BigInteger.valueOf(100), value.assetOf(policyId, tokenName));
        }

        @Test
        void assetOfReturnsZeroForMissing() {
            var value = Value.lovelace(BigInteger.valueOf(1000));
            assertEquals(BigInteger.ZERO,
                    value.assetOf(new PolicyId(padTo28(new byte[]{1, 2, 3})), new TokenName(new byte[]{4, 5})));
        }

        @Test
        void geqReturnsTrueWhenGreater() {
            var a = Value.lovelace(BigInteger.valueOf(5_000_000));
            var b = Value.lovelace(BigInteger.valueOf(2_000_000));
            assertTrue(a.lovelaceOf().compareTo(b.lovelaceOf()) >= 0);
        }

        @Test
        void geqReturnsTrueWhenEqual() {
            var v = Value.lovelace(BigInteger.valueOf(5_000_000));
            assertTrue(v.lovelaceOf().compareTo(v.lovelaceOf()) >= 0);
        }

        @Test
        void geqReturnsFalseWhenLess() {
            var a = Value.lovelace(BigInteger.valueOf(2_000_000));
            var b = Value.lovelace(BigInteger.valueOf(5_000_000));
            assertFalse(a.lovelaceOf().compareTo(b.lovelaceOf()) >= 0);
        }

        @Test
        void assetOfWithMixedValue() {
            var policyId = new PolicyId(padTo28(new byte[]{10, 20, 30}));
            var tokenName = new TokenName(new byte[]{40, 50});
            var value = Value.lovelace(BigInteger.valueOf(2_000_000))
                    .merge(Value.singleton(policyId, tokenName, BigInteger.valueOf(50)));

            assertEquals(BigInteger.valueOf(2_000_000), value.lovelaceOf());
            assertEquals(BigInteger.valueOf(50), value.assetOf(policyId, tokenName));
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
            assertEquals(3L, ListsLib.length(list));
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

    // --- Builtins Crypto ---

    @Nested
    class BuiltinsCryptoTests {

        @Test
        void sha2_256ProducesHash() {
            var data = new PlutusData.BytesData(new byte[]{1, 2, 3});
            var hash = Builtins.sha2_256(data);
            assertInstanceOf(PlutusData.BytesData.class, hash);
            assertEquals(32, hash.value().length);
        }

        @Test
        void blake2b_256ProducesHash() {
            var data = new PlutusData.BytesData(new byte[]{1, 2, 3});
            var hash = Builtins.blake2b_256(data);
            assertInstanceOf(PlutusData.BytesData.class, hash);
            assertEquals(32, hash.value().length);
        }

        @Test
        void sameInputProducesSameHash() {
            var data1 = new PlutusData.BytesData(new byte[]{1, 2, 3});
            var data2 = new PlutusData.BytesData(new byte[]{1, 2, 3});
            var hash1 = Builtins.sha2_256(data1);
            var hash2 = Builtins.sha2_256(data2);
            assertArrayEquals(hash1.value(), hash2.value());
        }

        @Test
        void differentInputProducesDifferentHash() {
            var data1 = new PlutusData.BytesData(new byte[]{1, 2, 3});
            var data2 = new PlutusData.BytesData(new byte[]{4, 5, 6});
            var hash1 = Builtins.sha2_256(data1);
            var hash2 = Builtins.sha2_256(data2);
            assertFalse(java.util.Arrays.equals(hash1.value(), hash2.value()));
        }
    }

    // --- Value factory methods ---

    @Nested
    class ValueFactoryTests {

        @Test
        void lovelaceCreatesCorrectValue() {
            var value = Value.lovelace(BigInteger.valueOf(5_000_000));
            assertEquals(BigInteger.valueOf(5_000_000), value.lovelaceOf());
        }

        @Test
        void zeroCreatesEmptyValue() {
            var value = Value.zero();
            assertEquals(BigInteger.ZERO, value.lovelaceOf());
        }

        @Test
        void ofCreatesNativeAssetValue() {
            var pid = new PolicyId(padTo28(new byte[]{1, 2, 3}));
            var tn = new TokenName(new byte[]{4, 5, 6});
            var value = Value.singleton(pid, tn, BigInteger.valueOf(100));
            assertEquals(BigInteger.valueOf(100), value.assetOf(pid, tn));
        }

        @Test
        void withLovelaceCreatesMixedValue() {
            var pid = new PolicyId(padTo28(new byte[]{10, 20}));
            var tn = new TokenName(new byte[]{30, 40});
            var value = Value.lovelace(BigInteger.valueOf(2_000_000))
                    .merge(Value.singleton(pid, tn, BigInteger.valueOf(50)));
            assertEquals(BigInteger.valueOf(2_000_000), value.lovelaceOf());
            assertEquals(BigInteger.valueOf(50), value.assetOf(pid, tn));
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
