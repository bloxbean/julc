package com.bloxbean.cardano.julc.ledger;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.cbor.PlutusDataCborDecoder;
import com.bloxbean.cardano.julc.core.cbor.PlutusDataCborEncoder;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.core.types.JulcMap;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that ledger types survive the full encode chain:
 * Java type -> PlutusData -> CBOR bytes -> PlutusData -> Java type
 */
class PlutusDataCborConformanceTest {

    static byte[] bytes28() { var b = new byte[28]; Arrays.fill(b, (byte) 0x01); return b; }
    static byte[] bytes32() { var b = new byte[32]; Arrays.fill(b, (byte) 0x02); return b; }

    private void assertCborRoundTrip(PlutusDataConvertible value) {
        var plutusData = value.toPlutusData();
        var cborBytes = PlutusDataCborEncoder.encode(plutusData);
        var decoded = PlutusDataCborDecoder.decode(cborBytes);
        assertEquals(plutusData, decoded, "CBOR round-trip failed for: " + value);
    }

    @Nested
    class HashTypes {
        @Test void pubKeyHash() { assertCborRoundTrip(new PubKeyHash(bytes28())); }
        @Test void scriptHash() { assertCborRoundTrip(new ScriptHash(bytes28())); }
        @Test void policyIdAda() { assertCborRoundTrip(PolicyId.ADA); }
        @Test void policyId() { assertCborRoundTrip(new PolicyId(bytes28())); }
        @Test void tokenName() { assertCborRoundTrip(new TokenName("hello".getBytes())); }
        @Test void datumHash() { assertCborRoundTrip(new DatumHash(bytes32())); }
        @Test void txId() { assertCborRoundTrip(new TxId(bytes32())); }
    }

    @Nested
    class CredentialTypes {
        @Test
        void pubKeyCredential() {
            assertCborRoundTrip(new Credential.PubKeyCredential(new PubKeyHash(bytes28())));
        }

        @Test
        void scriptCredential() {
            assertCborRoundTrip(new Credential.ScriptCredential(new ScriptHash(bytes28())));
        }

        @Test
        void stakingHash() {
            assertCborRoundTrip(new StakingCredential.StakingHash(
                    new Credential.PubKeyCredential(new PubKeyHash(bytes28()))));
        }

        @Test
        void stakingPtr() {
            assertCborRoundTrip(new StakingCredential.StakingPtr(
                    BigInteger.ONE, BigInteger.TWO, BigInteger.valueOf(3)));
        }
    }

    @Nested
    class TransactionTypes {
        @Test
        void address() {
            assertCborRoundTrip(new Address(
                    new Credential.PubKeyCredential(new PubKeyHash(bytes28())),
                    Optional.empty()));
        }

        @Test
        void txOutRef() {
            assertCborRoundTrip(new TxOutRef(new TxId(bytes32()), BigInteger.ZERO));
        }

        @Test
        void valueLovelace() {
            assertCborRoundTrip(Value.lovelace(BigInteger.valueOf(2_000_000)));
        }

        @Test
        void valueMultiAsset() {
            assertCborRoundTrip(
                    Value.lovelace(BigInteger.valueOf(1_000_000))
                            .merge(Value.singleton(new PolicyId(bytes28()),
                                    new TokenName("tok".getBytes()), BigInteger.TEN)));
        }

        @Test
        void txOut() {
            assertCborRoundTrip(new TxOut(
                    new Address(new Credential.PubKeyCredential(new PubKeyHash(bytes28())), Optional.empty()),
                    Value.lovelace(BigInteger.valueOf(2_000_000)),
                    new OutputDatum.NoOutputDatum(),
                    Optional.empty()));
        }

        @Test
        void txInInfo() {
            var addr = new Address(new Credential.PubKeyCredential(new PubKeyHash(bytes28())), Optional.empty());
            var out = new TxOut(addr, Value.lovelace(BigInteger.valueOf(5_000_000)),
                    new OutputDatum.NoOutputDatum(), Optional.empty());
            assertCborRoundTrip(new TxInInfo(new TxOutRef(new TxId(bytes32()), BigInteger.ZERO), out));
        }
    }

    @Nested
    class IntervalTypes {
        @Test void always() { assertCborRoundTrip(Interval.always()); }
        @Test void after() { assertCborRoundTrip(Interval.after(BigInteger.valueOf(1000))); }
        @Test void between() { assertCborRoundTrip(Interval.between(BigInteger.ONE, BigInteger.TEN)); }
    }

    @Nested
    class GovernanceTypes {
        @Test void voteNo() { assertCborRoundTrip(new Vote.VoteNo()); }
        @Test void voteYes() { assertCborRoundTrip(new Vote.VoteYes()); }
        @Test void abstain() { assertCborRoundTrip(new Vote.Abstain()); }

        @Test void governanceActionId() {
            assertCborRoundTrip(new GovernanceActionId(new TxId(bytes32()), BigInteger.ONE));
        }

        @Test void rational() {
            assertCborRoundTrip(new Rational(BigInteger.TWO, BigInteger.valueOf(3)));
        }

        @Test void infoAction() {
            assertCborRoundTrip(new GovernanceAction.InfoAction());
        }
    }

    @Nested
    class ScriptContextCbor {
        @Test
        void minimalSpendingContext() {
            var txInfo = new TxInfo(
                    JulcList.of(), JulcList.of(), JulcList.of(),
                    BigInteger.ZERO, Value.zero(), JulcList.of(),
                    JulcMap.empty(), Interval.always(), JulcList.of(),
                    JulcMap.empty(), JulcMap.empty(), new TxId(bytes32()),
                    JulcMap.empty(), JulcList.of(), Optional.empty(), Optional.empty());
            var ctx = new ScriptContext(txInfo, PlutusData.UNIT,
                    new ScriptInfo.SpendingScript(new TxOutRef(new TxId(bytes32()), BigInteger.ZERO), Optional.empty()));
            assertCborRoundTrip(ctx);
        }

        @Test
        void fullSpendingContext() {
            var pkh = new PubKeyHash(bytes28());
            var addr = new Address(new Credential.PubKeyCredential(pkh), Optional.empty());
            var txId = new TxId(bytes32());
            var out = new TxOut(addr, Value.lovelace(BigInteger.valueOf(5_000_000)),
                    new OutputDatum.OutputDatumInline(PlutusData.integer(42)),
                    Optional.empty());
            var input = new TxInInfo(new TxOutRef(txId, BigInteger.ZERO), out);

            var txInfo = new TxInfo(
                    JulcList.of(input), JulcList.of(), JulcList.of(out),
                    BigInteger.valueOf(200_000),
                    Value.zero(),
                    JulcList.of(new TxCert.RegStaking(new Credential.PubKeyCredential(pkh), Optional.of(BigInteger.valueOf(2_000_000)))),
                    JulcMap.empty(),
                    Interval.between(BigInteger.valueOf(1000), BigInteger.valueOf(2000)),
                    JulcList.of(pkh),
                    JulcMap.empty(), JulcMap.empty(), txId,
                    JulcMap.empty(), JulcList.of(),
                    Optional.of(BigInteger.valueOf(1_000_000_000)),
                    Optional.of(BigInteger.ZERO));
            var ctx = new ScriptContext(txInfo, PlutusData.UNIT,
                    new ScriptInfo.SpendingScript(new TxOutRef(txId, BigInteger.ZERO),
                            Optional.of(PlutusData.integer(42))));
            assertCborRoundTrip(ctx);
        }
    }
}
