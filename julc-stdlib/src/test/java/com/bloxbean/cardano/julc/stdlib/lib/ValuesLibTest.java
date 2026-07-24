package com.bloxbean.cardano.julc.stdlib.lib;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.PolicyId;
import com.bloxbean.cardano.julc.ledger.TokenName;
import com.bloxbean.cardano.julc.ledger.TxId;
import com.bloxbean.cardano.julc.ledger.TxOutRef;
import com.bloxbean.cardano.julc.ledger.Value;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.testkit.JulcEval;
import com.bloxbean.cardano.julc.testkit.JvmCryptoProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static com.bloxbean.cardano.julc.stdlib.lib.StdlibTestHelpers.makeBytes;
import static org.junit.jupiter.api.Assertions.*;

class ValuesLibTest {

    static JulcEval eval;

    static final byte[] POLICY_A = makeBytes(28, 0xAA);
    static final byte[] POLICY_B = makeBytes(28, 0xBB);
    static final byte[] TOKEN_X = "TokenX".getBytes();
    static final byte[] TOKEN_Y = "TokenY".getBytes();

    @BeforeAll
    static void setUp() {
        eval = JulcEval.forClass(ValuesLib.class);
        Builtins.setCryptoProvider(new JvmCryptoProvider());
    }

    /** Lovelace-only value. */
    static Value lovelaceValue(long amount) {
        return Value.lovelace(BigInteger.valueOf(amount));
    }

    /**
     * Multi-asset value: lovelace + one native token.
     * Built manually to ensure ADA (empty policy) is the FIRST map entry,
     * matching the on-chain ledger invariant that lovelaceOf() relies on.
     */
    static Value multiAssetValue(long lovelace, byte[] policy, byte[] token, long qty) {
        var adaInner = new PlutusData.MapData(List.of(
                new PlutusData.Pair(PlutusData.bytes(new byte[0]), PlutusData.integer(lovelace))));
        var tokenInner = new PlutusData.MapData(List.of(
                new PlutusData.Pair(PlutusData.bytes(token), PlutusData.integer(qty))));
        var outer = new PlutusData.MapData(List.of(
                new PlutusData.Pair(PlutusData.bytes(new byte[0]), adaInner),
                new PlutusData.Pair(PlutusData.bytes(policy), tokenInner)));
        return Value.fromPlutusData(outer);
    }

    // =========================================================================
    // lovelaceOf
    // =========================================================================

    @Nested
    class LovelaceOf {

        @Test
        void simpleLovelace() {
            assertEquals(BigInteger.valueOf(5_000_000),
                    eval.call("lovelaceOf", lovelaceValue(5_000_000)).asInteger());
        }

        @Test
        void multiAssetStillExtractsLovelace() {
            assertEquals(BigInteger.valueOf(2_000_000),
                    eval.call("lovelaceOf", multiAssetValue(2_000_000, POLICY_A, TOKEN_X, 10)).asInteger());
        }
    }

    // =========================================================================
    // containsPolicy
    // =========================================================================

    @Nested
    class ContainsPolicy {

        @Test
        void presentPolicy() {
            assertTrue(eval.call("containsPolicy",
                    multiAssetValue(2_000_000, POLICY_A, TOKEN_X, 5), POLICY_A).asBoolean());
        }

        @Test
        void absentPolicy() {
            assertFalse(eval.call("containsPolicy",
                    lovelaceValue(2_000_000), POLICY_A).asBoolean());
        }
    }

    // =========================================================================
    // assetOf
    // =========================================================================

    @Nested
    class AssetOf {

        @Test
        void presentAsset() {
            assertEquals(BigInteger.valueOf(42),
                    eval.call("assetOf",
                            multiAssetValue(2_000_000, POLICY_A, TOKEN_X, 42),
                            POLICY_A, TOKEN_X).asInteger());
        }

        @Test
        void absentTokenReturnsZero() {
            assertEquals(BigInteger.ZERO,
                    eval.call("assetOf",
                            multiAssetValue(2_000_000, POLICY_A, TOKEN_X, 42),
                            POLICY_A, TOKEN_Y).asInteger());
        }

        @Test
        void absentPolicyReturnsZero() {
            assertEquals(BigInteger.ZERO,
                    eval.call("assetOf",
                            lovelaceValue(2_000_000),
                            POLICY_A, TOKEN_X).asInteger());
        }
    }

    // =========================================================================
    // Comparison: geq, leq, eq, geqMultiAsset
    // =========================================================================

    @Nested
    class Comparison {

        @Test
        void geqGreater() {
            assertTrue(eval.call("geq", lovelaceValue(10), lovelaceValue(5)).asBoolean());
        }

        @Test
        void geqEqual() {
            assertTrue(eval.call("geq", lovelaceValue(10), lovelaceValue(10)).asBoolean());
        }

        @Test
        void geqLess() {
            assertFalse(eval.call("geq", lovelaceValue(5), lovelaceValue(10)).asBoolean());
        }

        @Test
        void leqSmaller() {
            assertTrue(eval.call("leq", lovelaceValue(5), lovelaceValue(10)).asBoolean());
        }

        @Test
        void leqEqual() {
            assertTrue(eval.call("leq", lovelaceValue(10), lovelaceValue(10)).asBoolean());
        }

        @Test
        void eqSame() {
            assertTrue(eval.call("eq", lovelaceValue(10), lovelaceValue(10)).asBoolean());
        }

        @Test
        void eqDifferent() {
            assertFalse(eval.call("eq", lovelaceValue(10), lovelaceValue(20)).asBoolean());
        }

        @Test
        void geqMultiAssetSufficient() {
            var a = multiAssetValue(5_000_000, POLICY_A, TOKEN_X, 100);
            var b = multiAssetValue(2_000_000, POLICY_A, TOKEN_X, 50);
            assertTrue(eval.call("geqMultiAsset", a, b).asBoolean());
        }

        @Test
        void geqMultiAssetInsufficient() {
            var a = multiAssetValue(5_000_000, POLICY_A, TOKEN_X, 10);
            var b = multiAssetValue(2_000_000, POLICY_A, TOKEN_X, 50);
            assertFalse(eval.call("geqMultiAsset", a, b).asBoolean());
        }
    }

    // =========================================================================
    // isZero
    // =========================================================================

    @Nested
    class IsZero {

        @Test
        void zeroValueIsZero() {
            var zero = Value.singleton(PolicyId.ADA, TokenName.EMPTY, BigInteger.ZERO);
            assertTrue(eval.call("isZero", zero).asBoolean());
        }

        @Test
        void nonZeroValueIsNotZero() {
            assertFalse(eval.call("isZero", lovelaceValue(1)).asBoolean());
        }
    }

    // =========================================================================
    // singleton
    // =========================================================================

    @Nested
    class Singleton {

        @Test
        void createsSingleAssetValue() {
            PlutusData result = eval.call("singleton", POLICY_A, TOKEN_X, BigInteger.valueOf(100)).asData();
            assertNotNull(result);
            assertInstanceOf(PlutusData.MapData.class, result);
            var entries = ((PlutusData.MapData) result).entries();
            assertEquals(1, entries.size());
        }
    }

    // =========================================================================
    // negate
    // =========================================================================

    @Nested
    class Negate {

        @Test
        void negatesLovelace() {
            Value v = lovelaceValue(5_000_000);
            BigInteger negated = eval.call("lovelaceOf",
                    (Value) Value.fromPlutusData(eval.call("negate", v).asData())).asInteger();
            assertEquals(BigInteger.valueOf(-5_000_000), negated);
        }
    }

    // =========================================================================
    // flatten
    // =========================================================================

    @Nested
    class Flatten {

        @Test
        void flattensSinglePolicy() {
            var v = lovelaceValue(2_000_000);
            var result = eval.call("flatten", v).asList();
            assertEquals(1, result.size());
        }

        @Test
        void flattensMultiAsset() {
            var v = multiAssetValue(2_000_000, POLICY_A, TOKEN_X, 42);
            var result = eval.call("flatten", v).asList();
            assertEquals(2, result.size());
        }
    }

    @Nested
    class FlattenTyped {

        @Test
        void flattenTypedReturnsSameAsFlatten() {
            var v = multiAssetValue(2_000_000, POLICY_A, TOKEN_X, 42);
            var flatResult = eval.call("flatten", v).asList();
            var typedResult = eval.call("flattenTyped", v).asList();
            assertEquals(flatResult.size(), typedResult.size());
        }
    }

    // =========================================================================
    // add, subtract
    // =========================================================================

    @Nested
    class AddSubtract {

        @Test
        void addLovelaceOnly() {
            var a = lovelaceValue(3_000_000);
            var b = lovelaceValue(2_000_000);
            PlutusData sumData = eval.call("add", a, b).asData();
            Value sum = Value.fromPlutusData(sumData);
            assertEquals(BigInteger.valueOf(5_000_000), sum.lovelaceOf());
        }

        @Test
        void addMultiAsset() {
            var a = multiAssetValue(3_000_000, POLICY_A, TOKEN_X, 10);
            var b = multiAssetValue(2_000_000, POLICY_A, TOKEN_X, 20);
            PlutusData sumData = eval.call("add", a, b).asData();
            Value sum = Value.fromPlutusData(sumData);
            assertEquals(BigInteger.valueOf(5_000_000), sum.lovelaceOf());
            assertEquals(BigInteger.valueOf(30), sum.assetOf(PolicyId.of(POLICY_A), TokenName.of(TOKEN_X)));
        }

        @Test
        void addDisjointPolicies() {
            var a = multiAssetValue(1_000_000, POLICY_A, TOKEN_X, 10);
            var b = multiAssetValue(1_000_000, POLICY_B, TOKEN_Y, 20);
            PlutusData sumData = eval.call("add", a, b).asData();
            Value sum = Value.fromPlutusData(sumData);
            assertEquals(BigInteger.valueOf(2_000_000), sum.lovelaceOf());
            assertEquals(BigInteger.valueOf(10), sum.assetOf(PolicyId.of(POLICY_A), TokenName.of(TOKEN_X)));
            assertEquals(BigInteger.valueOf(20), sum.assetOf(PolicyId.of(POLICY_B), TokenName.of(TOKEN_Y)));
        }

        @Test
        void subtractLovelace() {
            var a = lovelaceValue(5_000_000);
            var b = lovelaceValue(2_000_000);
            PlutusData diffData = eval.call("subtract", a, b).asData();
            Value diff = Value.fromPlutusData(diffData);
            assertEquals(BigInteger.valueOf(3_000_000), diff.lovelaceOf());
        }
    }

    // =========================================================================
    // Mint helpers: countTokensWithQty, findTokenName
    // =========================================================================

    @Nested
    class MintHelpers {

        @Test
        void countTokensWithQtyFound() {
            var mint = Value.singleton(PolicyId.of(POLICY_A), TokenName.of(TOKEN_X), BigInteger.ONE);
            assertEquals(BigInteger.ONE,
                    eval.call("countTokensWithQty", mint, POLICY_A, BigInteger.ONE).asInteger());
        }

        @Test
        void countTokensWithQtyNoMatch() {
            var mint = Value.singleton(PolicyId.of(POLICY_A), TokenName.of(TOKEN_X), BigInteger.valueOf(5));
            assertEquals(BigInteger.ZERO,
                    eval.call("countTokensWithQty", mint, POLICY_A, BigInteger.ONE).asInteger());
        }

        @Test
        void findTokenNameFound() {
            var mint = Value.singleton(PolicyId.of(POLICY_A), TokenName.of(TOKEN_X), BigInteger.ONE);
            byte[] found = eval.call("findTokenName", mint, POLICY_A, BigInteger.ONE).asByteString();
            assertArrayEquals(TOKEN_X, found);
        }

        @Test
        void findTokenNameNotFound() {
            var mint = Value.singleton(PolicyId.of(POLICY_A), TokenName.of(TOKEN_X), BigInteger.valueOf(5));
            byte[] found = eval.call("findTokenName", mint, POLICY_A, BigInteger.ONE).asByteString();
            assertEquals(0, found.length);
        }
    }

    // =========================================================================
    // refBytes / uniqueTokenName
    // =========================================================================

    @Nested
    class UniqueTokenNameTests {

        final byte[] txHash = makeBytes(32, 0xCD);

        TxOutRef ref(long index) {
            return new TxOutRef(new TxId(txHash), BigInteger.valueOf(index));
        }

        byte[] expectedRefBytes(int hi, int lo) {
            byte[] expected = new byte[34];
            System.arraycopy(txHash, 0, expected, 0, 32);
            expected[32] = (byte) hi;
            expected[33] = (byte) lo;
            return expected;
        }

        @Test
        void refBytesIndexZero() {
            // Index 0 must still contribute 2 bytes — the width-0 minimal encoding
            // would drop it entirely, which is why refBytes uses fixed width 2
            byte[] result = eval.call("refBytes", ref(0)).asByteString();
            assertArrayEquals(expectedRefBytes(0, 0), result);
        }

        @Test
        void refBytesSmallIndex() {
            byte[] result = eval.call("refBytes", ref(1)).asByteString();
            assertArrayEquals(expectedRefBytes(0, 1), result);
        }

        @Test
        void refBytesTwoByteIndex() {
            byte[] result = eval.call("refBytes", ref(256)).asByteString();
            assertArrayEquals(expectedRefBytes(1, 0), result);
        }

        @Test
        void refBytesMaxIndex() {
            byte[] result = eval.call("refBytes", ref(65535)).asByteString();
            assertArrayEquals(expectedRefBytes(0xFF, 0xFF), result);
        }

        @Test
        void refBytesIndexTooLargeFails() {
            assertThrows(Exception.class, () -> eval.call("refBytes", ref(65536)));
        }

        @Test
        void refBytesJvmMatchesUplcAtWidthBoundaries() {
            for (long index : new long[]{0, 1, 256, 65535}) {
                assertArrayEquals(
                        eval.call("refBytes", ref(index)).asByteString(),
                        ValuesLib.refBytes(ref(index)),
                        "JVM/UPLC mismatch at output index " + index);
            }
        }

        @Test
        void uniqueTokenNameIsBlake2bOfRefBytes() {
            var cryptoEval = JulcEval.forClass(CryptoLib.class);
            byte[] expected = cryptoEval.call("blake2b_256", expectedRefBytes(0, 7)).asByteString();
            byte[] actual = eval.call("uniqueTokenName", ref(7)).asByteString();
            assertArrayEquals(expected, actual);
        }

        @Test
        void uniqueTokenNameIs32Bytes() {
            byte[] name = eval.call("uniqueTokenName", ref(0)).asByteString();
            assertEquals(32, name.length);
        }

        @Test
        void uniqueTokenNameJvmMatchesUplc() {
            assertArrayEquals(
                    eval.call("uniqueTokenName", ref(7)).asByteString(),
                    ValuesLib.uniqueTokenName(ref(7)));
        }

        @Test
        void uniqueTokenNameDiffersByIndex() {
            byte[] name0 = eval.call("uniqueTokenName", ref(0)).asByteString();
            byte[] name1 = eval.call("uniqueTokenName", ref(1)).asByteString();
            assertFalse(java.util.Arrays.equals(name0, name1));
        }

        @Test
        void uniqueTokenNameDiffersByTxId() {
            byte[] otherHash = makeBytes(32, 0xEE);
            var otherRef = new TxOutRef(new TxId(otherHash), BigInteger.ZERO);
            byte[] name0 = eval.call("uniqueTokenName", ref(0)).asByteString();
            byte[] nameOther = eval.call("uniqueTokenName", otherRef).asByteString();
            assertFalse(java.util.Arrays.equals(name0, nameOther));
        }
    }
}
