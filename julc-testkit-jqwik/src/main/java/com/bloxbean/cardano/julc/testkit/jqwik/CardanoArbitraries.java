package com.bloxbean.cardano.julc.testkit.jqwik;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.*;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;

import java.math.BigInteger;
import java.util.Optional;

/**
 * Static factory for jqwik {@link Arbitrary} generators of Cardano/Plutus types.
 * <p>
 * All generators use jqwik primitives so shrinking works automatically.
 * Hash types produce correct-length byte arrays; composite types compose
 * from smaller generators.
 * <p>
 * Usage:
 * <pre>{@code
 * @Property
 * void myProperty(@ForAll("pubKeyHash") PubKeyHash pkh) { ... }
 *
 * @Provide
 * Arbitrary<PubKeyHash> pubKeyHash() {
 *     return CardanoArbitraries.pubKeyHash();
 * }
 * }</pre>
 *
 * Or register {@link CardanoArbitraryProvider} via SPI for automatic injection.
 */
public final class CardanoArbitraries {

    private CardanoArbitraries() {}

    // --- PlutusData generators ---

    /**
     * Random BigInteger in range [-1,000,000,000, 1,000,000,000] as IntData.
     * Shrinks toward 0.
     */
    public static Arbitrary<PlutusData> intData() {
        return Arbitraries.bigIntegers()
                .between(BigInteger.valueOf(-1_000_000_000L), BigInteger.valueOf(1_000_000_000L))
                .map(PlutusData.IntData::new);
    }

    /**
     * Bounded integer as IntData.
     */
    public static Arbitrary<PlutusData> intData(BigInteger min, BigInteger max) {
        return Arbitraries.bigIntegers().between(min, max)
                .map(PlutusData.IntData::new);
    }

    /**
     * Random 0-64 byte array as BytesData. Shrinks toward empty/zero bytes.
     */
    public static Arbitrary<PlutusData> bytesData() {
        return bytesData(0, 64);
    }

    /**
     * Fixed-length byte array as BytesData.
     */
    public static Arbitrary<PlutusData> bytesData(int length) {
        return rawBytes(length).map(PlutusData.BytesData::new);
    }

    /**
     * Variable-length byte array as BytesData.
     */
    public static Arbitrary<PlutusData> bytesData(int minLen, int maxLen) {
        return Arbitraries.integers().between(minLen, maxLen)
                .flatMap(len -> rawBytes(len))
                .map(PlutusData.BytesData::new);
    }

    /**
     * Random ConstrData with tag 0-6 and 0-3 fields, depth-bounded.
     */
    public static Arbitrary<PlutusData> constrData(int maxDepth) {
        return Combinators.combine(
                Arbitraries.integers().between(0, 6),
                plutusData(maxDepth - 1).list().ofMaxSize(3)
        ).as(PlutusData.ConstrData::new);
    }

    /**
     * Random ListData with 0-5 elements, depth-bounded.
     */
    public static Arbitrary<PlutusData> listData(int maxDepth) {
        return plutusData(maxDepth - 1).list().ofMaxSize(5)
                .map(PlutusData.ListData::new);
    }

    /**
     * Random MapData with 0-3 entries, depth-bounded.
     */
    public static Arbitrary<PlutusData> mapData(int maxDepth) {
        return Combinators.combine(plutusData(maxDepth - 1), plutusData(maxDepth - 1))
                .as(PlutusData.Pair::new)
                .list().ofMaxSize(3)
                .map(PlutusData.MapData::new);
    }

    /**
     * Any PlutusData (default depth 3). Shrinks toward IntData(0).
     */
    public static Arbitrary<PlutusData> plutusData() {
        return plutusData(3);
    }

    /**
     * Depth-bounded recursive PlutusData. Shrinks toward leaf types.
     */
    public static Arbitrary<PlutusData> plutusData(int maxDepth) {
        if (maxDepth <= 0) {
            return Arbitraries.oneOf(intData(), bytesData());
        }
        return Arbitraries.oneOf(
                intData(),
                bytesData(),
                constrData(maxDepth),
                listData(maxDepth),
                mapData(maxDepth)
        );
    }

    // --- Cardano hash type generators ---

    /**
     * Random PubKeyHash (28 bytes).
     */
    public static Arbitrary<PubKeyHash> pubKeyHash() {
        return rawBytes(28).map(PubKeyHash::new);
    }

    /**
     * Random ScriptHash (28 bytes).
     */
    public static Arbitrary<ScriptHash> scriptHash() {
        return rawBytes(28).map(ScriptHash::new);
    }

    /**
     * Random ValidatorHash (28 bytes).
     */
    public static Arbitrary<ValidatorHash> validatorHash() {
        return rawBytes(28).map(ValidatorHash::new);
    }

    /**
     * Random PolicyId (28 bytes, non-ADA).
     */
    public static Arbitrary<PolicyId> policyId() {
        return rawBytes(28).map(PolicyId::new);
    }

    /**
     * Random TokenName (0-32 bytes).
     */
    public static Arbitrary<TokenName> tokenName() {
        return Arbitraries.integers().between(0, 32)
                .flatMap(len -> rawBytes(len))
                .map(TokenName::new);
    }

    /**
     * Random DatumHash (32 bytes).
     */
    public static Arbitrary<DatumHash> datumHash() {
        return rawBytes(32).map(DatumHash::new);
    }

    /**
     * Random TxId (32 bytes).
     */
    public static Arbitrary<TxId> txId() {
        return rawBytes(32).map(TxId::new);
    }

    // --- Ledger composite type generators ---

    /**
     * Random Credential: PubKeyCredential or ScriptCredential.
     */
    public static Arbitrary<Credential> credential() {
        return Arbitraries.oneOf(
                pubKeyHash().map(Credential.PubKeyCredential::new),
                scriptHash().map(Credential.ScriptCredential::new)
        );
    }

    /**
     * Random Address with credential and no staking credential (enterprise address).
     * To generate base addresses with staking credentials, compose manually.
     */
    public static Arbitrary<Address> address() {
        return credential().map(cred -> new Address(cred, Optional.empty()));
    }

    /**
     * Random TxOutRef: txId + index(0-9).
     */
    public static Arbitrary<TxOutRef> txOutRef() {
        return Combinators.combine(txId(), Arbitraries.integers().between(0, 9))
                .as((id, idx) -> new TxOutRef(id, BigInteger.valueOf(idx)));
    }

    /**
     * Random lovelace-only Value in range 1-100 ADA.
     */
    public static Arbitrary<Value> lovelaceValue() {
        return lovelaceValue(1_000_000L, 100_000_000L);
    }

    /**
     * Lovelace-only Value with bounded amount (in lovelace).
     */
    public static Arbitrary<Value> lovelaceValue(long minLovelace, long maxLovelace) {
        return Arbitraries.longs().between(minLovelace, maxLovelace)
                .map(amt -> Value.lovelace(BigInteger.valueOf(amt)));
    }

    /**
     * Multi-asset Value: ADA + 1-3 native tokens.
     */
    public static Arbitrary<Value> multiAssetValue() {
        return Combinators.combine(
                lovelaceValue(),
                Combinators.combine(policyId(), tokenName(),
                        Arbitraries.longs().between(1, 1_000_000))
                        .as((p, t, q) -> Value.singleton(p, t, BigInteger.valueOf(q)))
                        .list().ofMinSize(1).ofMaxSize(3)
        ).as((ada, tokens) -> {
            Value result = ada;
            for (Value token : tokens) {
                result = result.merge(token);
            }
            return result;
        });
    }

    /**
     * Random Value: either lovelace-only or multi-asset.
     */
    public static Arbitrary<Value> value() {
        return Arbitraries.oneOf(lovelaceValue(), multiAssetValue());
    }

    /**
     * Random OutputDatum: NoOutputDatum, OutputDatumHash, or InlineDatum.
     */
    public static Arbitrary<OutputDatum> outputDatum() {
        return Arbitraries.oneOf(
                Arbitraries.just(new OutputDatum.NoOutputDatum()),
                datumHash().map(OutputDatum.OutputDatumHash::new),
                plutusData(1).map(OutputDatum.OutputDatumInline::new)
        );
    }

    /**
     * Random TxOut: address + value + datum + no reference script.
     */
    public static Arbitrary<TxOut> txOut() {
        return Combinators.combine(address(), value(), outputDatum())
                .as((addr, val, datum) -> new TxOut(addr, val, datum, Optional.empty()));
    }

    /**
     * Random TxInInfo: txOutRef + txOut.
     */
    public static Arbitrary<TxInInfo> txInInfo() {
        return Combinators.combine(txOutRef(), txOut())
                .as(TxInInfo::new);
    }

    /**
     * Random Interval: one of always, never, after, before, or between.
     */
    public static Arbitrary<Interval> interval() {
        return Arbitraries.oneOf(
                Arbitraries.just(Interval.always()),
                Arbitraries.just(Interval.never()),
                Arbitraries.bigIntegers()
                        .between(BigInteger.ZERO, BigInteger.valueOf(1_000_000_000L))
                        .map(Interval::after),
                Arbitraries.bigIntegers()
                        .between(BigInteger.ZERO, BigInteger.valueOf(1_000_000_000L))
                        .map(Interval::before),
                Combinators.combine(
                        Arbitraries.bigIntegers().between(BigInteger.ZERO, BigInteger.valueOf(500_000_000L)),
                        Arbitraries.bigIntegers().between(BigInteger.valueOf(500_000_001L), BigInteger.valueOf(1_000_000_000L))
                ).as(Interval::between)
        );
    }

    // --- Internal helpers ---

    /**
     * Generate a raw byte array of the given length with random content.
     */
    static Arbitrary<byte[]> rawBytes(int length) {
        return Arbitraries.bytes().array(byte[].class).ofSize(length);
    }
}
