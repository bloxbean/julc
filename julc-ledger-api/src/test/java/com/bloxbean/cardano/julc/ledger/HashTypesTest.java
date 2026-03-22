package com.bloxbean.cardano.julc.ledger;

import com.bloxbean.cardano.julc.core.PlutusData;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class HashTypesTest {

    private static byte[] bytes28() {
        var b = new byte[28];
        Arrays.fill(b, (byte) 0xAB);
        return b;
    }

    private static byte[] bytes32() {
        var b = new byte[32];
        Arrays.fill(b, (byte) 0xCD);
        return b;
    }

    // --- PubKeyHash ---

    @Nested
    class PubKeyHashTests {
        @Test
        void construction() { assertNotNull(new PubKeyHash(bytes28())); }

        @Test
        void arbitraryLength() {
            // No length validation — any byte array is accepted (ledger enforces lengths, not the type)
            assertNotNull(new PubKeyHash(new byte[27]));
            assertNotNull(new PubKeyHash(new byte[0]));
            assertNotNull(new PubKeyHash(new byte[64]));
        }

        @Test
        void nullHash() { assertThrows(NullPointerException.class, () -> new PubKeyHash(null)); }

        @Test
        void defensiveCopy() {
            var b = bytes28();
            var pkh = new PubKeyHash(b);
            b[0] = 0;
            assertNotEquals(b[0], pkh.hash()[0]);
        }

        @Test
        void toPlutusData() {
            var pkh = new PubKeyHash(bytes28());
            assertEquals(new PlutusData.BytesData(bytes28()), pkh.toPlutusData());
        }

        @Test
        void fromPlutusData() {
            var data = new PlutusData.BytesData(bytes28());
            assertEquals(new PubKeyHash(bytes28()), PubKeyHash.fromPlutusData(data));
        }

        @Test
        void roundTrip() {
            var original = new PubKeyHash(bytes28());
            assertEquals(original, PubKeyHash.fromPlutusData(original.toPlutusData()));
        }

        @Test
        void equality() {
            assertEquals(new PubKeyHash(bytes28()), new PubKeyHash(bytes28()));
        }

        @Test
        void toStringHex() {
            assertTrue(new PubKeyHash(bytes28()).toString().contains("abab"));
        }
    }

    // --- ScriptHash ---

    @Nested
    class ScriptHashTests {
        @Test
        void construction() { assertNotNull(new ScriptHash(bytes28())); }

        @Test
        void arbitraryLength() {
            assertNotNull(new ScriptHash(new byte[29]));
            assertNotNull(new ScriptHash(new byte[0]));
            assertNotNull(new ScriptHash(new byte[64]));
        }

        @Test
        void toPlutusDataRoundTrip() {
            var sh = new ScriptHash(bytes28());
            assertEquals(sh, ScriptHash.fromPlutusData(sh.toPlutusData()));
        }

        @Test
        void equality() { assertEquals(new ScriptHash(bytes28()), new ScriptHash(bytes28())); }
    }

    // --- ValidatorHash ---

    @Nested
    class ValidatorHashTests {
        @Test
        void construction() { assertNotNull(new ValidatorHash(bytes28())); }

        @Test
        void arbitraryLength() {
            assertNotNull(new ValidatorHash(new byte[32]));
            assertNotNull(new ValidatorHash(new byte[0]));
            assertNotNull(new ValidatorHash(new byte[64]));
        }

        @Test
        void roundTrip() {
            var vh = new ValidatorHash(bytes28());
            assertEquals(vh, ValidatorHash.fromPlutusData(vh.toPlutusData()));
        }
    }

    // --- PolicyId ---

    @Nested
    class PolicyIdTests {
        @Test
        void construction28() { assertNotNull(new PolicyId(bytes28())); }

        @Test
        void constructionAda() { assertNotNull(new PolicyId(new byte[0])); }

        @Test
        void adaConstant() { assertEquals(new PolicyId(new byte[0]), PolicyId.ADA); }

        @Test
        void arbitraryLength() {
            assertNotNull(new PolicyId(new byte[10]));
            assertNotNull(new PolicyId(new byte[64]));
        }

        @Test
        void roundTrip() {
            var pid = new PolicyId(bytes28());
            assertEquals(pid, PolicyId.fromPlutusData(pid.toPlutusData()));
        }

        @Test
        void roundTripAda() {
            assertEquals(PolicyId.ADA, PolicyId.fromPlutusData(PolicyId.ADA.toPlutusData()));
        }
    }

    // --- TokenName ---

    @Nested
    class TokenNameTests {
        @Test
        void constructionEmpty() { assertNotNull(new TokenName(new byte[0])); }

        @Test
        void construction32() { assertNotNull(new TokenName(new byte[32])); }

        @Test
        void arbitraryLength() {
            // No length validation — matches Haskell/Scalus (simple bytestring wrapper)
            assertNotNull(new TokenName(new byte[33]));
            assertNotNull(new TokenName(new byte[66]));
            assertNotNull(new TokenName(new byte[128]));
        }

        @Test
        void emptyConstant() { assertEquals(new TokenName(new byte[0]), TokenName.EMPTY); }

        @Test
        void roundTrip() {
            var tn = new TokenName("hello".getBytes());
            assertEquals(tn, TokenName.fromPlutusData(tn.toPlutusData()));
        }
    }

    // --- DatumHash ---

    @Nested
    class DatumHashTests {
        @Test
        void construction() { assertNotNull(new DatumHash(bytes32())); }

        @Test
        void arbitraryLength() {
            assertNotNull(new DatumHash(new byte[28]));
            assertNotNull(new DatumHash(new byte[0]));
            assertNotNull(new DatumHash(new byte[64]));
        }

        @Test
        void roundTrip() {
            var dh = new DatumHash(bytes32());
            assertEquals(dh, DatumHash.fromPlutusData(dh.toPlutusData()));
        }

        @Test
        void equality() { assertEquals(new DatumHash(bytes32()), new DatumHash(bytes32())); }
    }

    // --- TxId ---

    @Nested
    class TxIdTests {
        @Test
        void construction() { assertNotNull(new TxId(bytes32())); }

        @Test
        void arbitraryLength() {
            assertNotNull(new TxId(new byte[31]));
            assertNotNull(new TxId(new byte[0]));
            assertNotNull(new TxId(new byte[64]));
        }

        @Test
        void roundTrip() {
            var txId = new TxId(bytes32());
            assertEquals(txId, TxId.fromPlutusData(txId.toPlutusData()));
        }

        @Test
        void defensiveCopy() {
            var b = bytes32();
            var txId = new TxId(b);
            b[0] = 0;
            assertNotEquals(b[0], txId.hash()[0]);
        }

        @Test
        void toStringHex() {
            assertTrue(new TxId(bytes32()).toString().contains("cdcd"));
        }
    }
}
