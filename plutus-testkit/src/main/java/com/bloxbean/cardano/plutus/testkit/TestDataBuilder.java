package com.bloxbean.cardano.plutus.testkit;

import com.bloxbean.cardano.plutus.core.PlutusData;
import com.bloxbean.cardano.plutus.ledger.*;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Test data generators for creating random and convenience PlutusData values.
 * <p>
 * Provides factory methods for common Cardano data structures like public key hashes,
 * transaction IDs, and output references.
 * <p>
 * Usage:
 * <pre>{@code
 * PlutusData pkh = TestDataBuilder.randomPubKeyHash();
 * PlutusData txId = TestDataBuilder.randomTxId();
 * PlutusData txOutRef = TestDataBuilder.randomTxOutRef();
 * PlutusData amount = TestDataBuilder.intData(1_000_000);
 * }</pre>
 */
public final class TestDataBuilder {

    private static final SecureRandom RANDOM = new SecureRandom();

    private TestDataBuilder() {
        // utility class
    }

    /**
     * Generate a random PubKeyHash (28 bytes of random data).
     * <p>
     * On Cardano, a PubKeyHash is the Blake2b-224 hash of a public key,
     * which is 28 bytes.
     *
     * @return a BytesData containing 28 random bytes
     */
    public static PlutusData randomPubKeyHash() {
        return randomBytes(28);
    }

    /**
     * Generate a random TxId (32 bytes of random data).
     * <p>
     * On Cardano, a TxId is the Blake2b-256 hash of a transaction body,
     * which is 32 bytes.
     *
     * @return a BytesData containing 32 random bytes
     */
    public static PlutusData randomTxId() {
        return randomBytes(32);
    }

    /**
     * Generate a random TxOutRef (transaction output reference).
     * <p>
     * A TxOutRef is encoded as Constr(0, [Constr(0, [txId]), intData(index)]),
     * where txId is a random 32-byte hash and index is a random small integer.
     *
     * @return a Constr representing a random TxOutRef
     */
    public static PlutusData randomTxOutRef() {
        var txId = randomTxId();
        var index = intData(RANDOM.nextInt(10));
        // TxOutRef is Constr(0, [Constr(0, [txId]), index])
        // The inner Constr(0, [txId]) wraps TxId
        return new PlutusData.Constr(0, List.of(
                new PlutusData.Constr(0, List.of(txId)),
                index
        ));
    }

    /**
     * Create a TxOutRef with specific values.
     *
     * @param txIdBytes the 32-byte transaction ID
     * @param index     the output index
     * @return a Constr representing the TxOutRef
     */
    public static PlutusData txOutRef(byte[] txIdBytes, int index) {
        Objects.requireNonNull(txIdBytes, "txIdBytes must not be null");
        return new PlutusData.Constr(0, List.of(
                new PlutusData.Constr(0, List.of(bytesData(txIdBytes))),
                intData(index)
        ));
    }

    /**
     * Convenience method to create an IntData.
     *
     * @param value the integer value
     * @return an IntData wrapping the value
     */
    public static PlutusData intData(long value) {
        return new PlutusData.IntData(BigInteger.valueOf(value));
    }

    /**
     * Convenience method to create an IntData from a BigInteger.
     *
     * @param value the integer value
     * @return an IntData wrapping the value
     */
    public static PlutusData intData(BigInteger value) {
        Objects.requireNonNull(value, "value must not be null");
        return new PlutusData.IntData(value);
    }

    /**
     * Convenience method to create a BytesData.
     *
     * @param bytes the byte array
     * @return a BytesData wrapping the bytes
     */
    public static PlutusData bytesData(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes must not be null");
        return new PlutusData.BytesData(bytes);
    }

    /**
     * Create a PlutusData list from the given items.
     *
     * @param items the items for the list
     * @return a ListData containing the items
     */
    public static PlutusData listData(PlutusData... items) {
        return new PlutusData.ListData(List.of(items));
    }

    /**
     * Create a Constr PlutusData.
     *
     * @param tag    the constructor tag
     * @param fields the constructor fields
     * @return a Constr with the given tag and fields
     */
    public static PlutusData constrData(int tag, PlutusData... fields) {
        return new PlutusData.Constr(tag, List.of(fields));
    }

    /**
     * Create a PlutusData map (association list) from alternating key-value pairs.
     *
     * @param keysAndValues alternating keys and values (must be even length)
     * @return a Map PlutusData
     * @throws IllegalArgumentException if the number of arguments is odd
     */
    public static PlutusData mapData(PlutusData... keysAndValues) {
        if (keysAndValues.length % 2 != 0) {
            throw new IllegalArgumentException(
                    "mapData requires an even number of arguments (key-value pairs), got " + keysAndValues.length);
        }
        var entries = new java.util.ArrayList<PlutusData.Pair>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            entries.add(new PlutusData.Pair(keysAndValues[i], keysAndValues[i + 1]));
        }
        return new PlutusData.Map(entries);
    }

    /**
     * The PlutusData unit value: Constr(0, []).
     *
     * @return the unit PlutusData
     */
    public static PlutusData unitData() {
        return PlutusData.UNIT;
    }

    /**
     * Boolean true as PlutusData: Constr(1, []).
     *
     * @return PlutusData encoding of true
     */
    public static PlutusData boolData(boolean value) {
        return value ? new PlutusData.Constr(1, List.of()) : new PlutusData.Constr(0, List.of());
    }

    /**
     * Generate random bytes of the given length.
     *
     * @param length the number of bytes
     * @return a BytesData with random bytes
     */
    public static PlutusData randomBytes(int length) {
        if (length < 0) {
            throw new IllegalArgumentException("length must be non-negative: " + length);
        }
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return new PlutusData.BytesData(bytes);
    }

    /**
     * Generate a random ScriptHash (28 bytes of random data).
     *
     * @return a BytesData containing 28 random bytes
     */
    public static PlutusData randomScriptHash() {
        return randomBytes(28);
    }

    /**
     * Generate a random DatumHash (32 bytes of random data).
     *
     * @return a BytesData containing 32 random bytes
     */
    public static PlutusData randomDatumHash() {
        return randomBytes(32);
    }

    // --- Typed factory methods (ledger-api types) ---

    /**
     * Generate a random PubKeyHash as a ledger-api type.
     *
     * @return a PubKeyHash with 28 random bytes
     */
    public static PubKeyHash randomPubKeyHash_typed() {
        byte[] bytes = new byte[28];
        RANDOM.nextBytes(bytes);
        return new PubKeyHash(bytes);
    }

    /**
     * Generate a random TxOutRef as a ledger-api type.
     *
     * @return a TxOutRef with a random 32-byte TxId and random small index
     */
    public static TxOutRef randomTxOutRef_typed() {
        byte[] txIdBytes = new byte[32];
        RANDOM.nextBytes(txIdBytes);
        return new TxOutRef(new TxId(txIdBytes), BigInteger.valueOf(RANDOM.nextInt(10)));
    }

    /**
     * Create a ledger-api Address with a PubKeyCredential and no staking credential.
     *
     * @param pkh the public key hash
     * @return an Address with the given PubKeyCredential
     */
    public static Address pubKeyAddress(PubKeyHash pkh) {
        Objects.requireNonNull(pkh, "pkh must not be null");
        return new Address(new Credential.PubKeyCredential(pkh), Optional.empty());
    }

    /**
     * Create a ledger-api TxOut with no datum and no reference script.
     *
     * @param address the output address
     * @param value   the output value
     * @return a TxOut with NoOutputDatum and no reference script
     */
    public static TxOut txOut(Address address, Value value) {
        Objects.requireNonNull(address, "address must not be null");
        Objects.requireNonNull(value, "value must not be null");
        return new TxOut(address, value, new OutputDatum.NoOutputDatum(), Optional.empty());
    }

    /**
     * Create a ledger-api TxInInfo from a TxOutRef and TxOut.
     *
     * @param outRef   the output reference
     * @param resolved the resolved output
     * @return a TxInInfo
     */
    public static TxInInfo txIn(TxOutRef outRef, TxOut resolved) {
        Objects.requireNonNull(outRef, "outRef must not be null");
        Objects.requireNonNull(resolved, "resolved must not be null");
        return new TxInInfo(outRef, resolved);
    }
}
