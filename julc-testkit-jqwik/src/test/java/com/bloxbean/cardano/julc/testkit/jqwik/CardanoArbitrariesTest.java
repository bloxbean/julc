package com.bloxbean.cardano.julc.testkit.jqwik;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.*;
import net.jqwik.api.*;

import java.math.BigInteger;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for all {@link CardanoArbitraries} generators.
 * <p>
 * Hash and composite type tests use bare {@code @ForAll} (SPI auto-detection via
 * {@link CardanoArbitraryProvider}). Parameterized generator tests use explicit
 * {@code @Provide} methods.
 */
class CardanoArbitrariesTest {

    // --- PlutusData generators (parameterized — need explicit @Provide) ---

    @Property(tries = 100)
    void intDataProducesIntData(@ForAll("intDataArb") PlutusData data) {
        assertInstanceOf(PlutusData.IntData.class, data);
    }

    @Provide
    Arbitrary<PlutusData> intDataArb() {
        return CardanoArbitraries.intData();
    }

    @Property(tries = 100)
    void boundedIntDataRespectsRange(@ForAll("boundedIntDataArb") PlutusData data) {
        var intData = (PlutusData.IntData) data;
        assertTrue(intData.value().compareTo(BigInteger.ZERO) >= 0);
        assertTrue(intData.value().compareTo(BigInteger.valueOf(100)) <= 0);
    }

    @Provide
    Arbitrary<PlutusData> boundedIntDataArb() {
        return CardanoArbitraries.intData(BigInteger.ZERO, BigInteger.valueOf(100));
    }

    @Property(tries = 100)
    void bytesDataProducesBytesData(@ForAll("bytesDataArb") PlutusData data) {
        assertInstanceOf(PlutusData.BytesData.class, data);
        var bytes = (PlutusData.BytesData) data;
        assertTrue(bytes.value().length <= 64);
    }

    @Provide
    Arbitrary<PlutusData> bytesDataArb() {
        return CardanoArbitraries.bytesData();
    }

    @Property(tries = 100)
    void fixedLengthBytesData(@ForAll("fixedBytesArb") PlutusData data) {
        var bytes = (PlutusData.BytesData) data;
        assertEquals(16, bytes.value().length);
    }

    @Provide
    Arbitrary<PlutusData> fixedBytesArb() {
        return CardanoArbitraries.bytesData(16);
    }

    @Property(tries = 100)
    void variableLengthBytesData(@ForAll("varBytesArb") PlutusData data) {
        var bytes = (PlutusData.BytesData) data;
        assertTrue(bytes.value().length >= 5);
        assertTrue(bytes.value().length <= 10);
    }

    @Provide
    Arbitrary<PlutusData> varBytesArb() {
        return CardanoArbitraries.bytesData(5, 10);
    }

    @Property(tries = 100)
    void plutusDataProducesValidData(@ForAll PlutusData data) {
        assertNotNull(data);
        assertTrue(data instanceof PlutusData.IntData
                || data instanceof PlutusData.BytesData
                || data instanceof PlutusData.ConstrData
                || data instanceof PlutusData.ListData
                || data instanceof PlutusData.MapData);
    }

    @Property(tries = 50)
    void depthZeroProducesOnlyLeaves(@ForAll("depth0Arb") PlutusData data) {
        assertTrue(data instanceof PlutusData.IntData
                || data instanceof PlutusData.BytesData,
                "Depth 0 should only produce leaf types, got: " + data.getClass().getSimpleName());
    }

    @Provide
    Arbitrary<PlutusData> depth0Arb() {
        return CardanoArbitraries.plutusData(0);
    }

    // --- Hash type generators (SPI auto-detected) ---

    @Property(tries = 100)
    void pubKeyHashIs28Bytes(@ForAll PubKeyHash pkh) {
        assertNotNull(pkh);
        assertEquals(28, pkh.hash().length);
    }

    @Property(tries = 100)
    void scriptHashIs28Bytes(@ForAll ScriptHash sh) {
        assertEquals(28, sh.hash().length);
    }

    @Property(tries = 100)
    void validatorHashIs28Bytes(@ForAll ValidatorHash vh) {
        assertEquals(28, vh.hash().length);
    }

    @Property(tries = 100)
    void policyIdIs28Bytes(@ForAll PolicyId pid) {
        assertEquals(28, pid.hash().length);
    }

    @Property(tries = 100)
    void tokenNameIs0To32Bytes(@ForAll TokenName tn) {
        assertTrue(tn.name().length >= 0);
        assertTrue(tn.name().length <= 32);
    }

    @Property(tries = 100)
    void datumHashIs32Bytes(@ForAll DatumHash dh) {
        assertEquals(32, dh.hash().length);
    }

    @Property(tries = 100)
    void txIdIs32Bytes(@ForAll TxId txId) {
        assertEquals(32, txId.hash().length);
    }

    // --- Diversity check ---

    @Example
    void pubKeyHashDiversity() {
        var samples = new HashSet<PubKeyHash>();
        CardanoArbitraries.pubKeyHash().sampleStream().limit(100).forEach(samples::add);
        assertTrue(samples.size() >= 90,
                "Expected at least 90 distinct PubKeyHash values from 100 samples, got " + samples.size());
    }

    @Example
    void txIdDiversity() {
        var samples = new HashSet<TxId>();
        CardanoArbitraries.txId().sampleStream().limit(100).forEach(samples::add);
        assertTrue(samples.size() >= 90,
                "Expected at least 90 distinct TxId values from 100 samples, got " + samples.size());
    }

    // --- Composite type generators (SPI auto-detected) ---

    @Property(tries = 50)
    void credentialIsValid(@ForAll Credential cred) {
        assertNotNull(cred);
        assertTrue(cred instanceof Credential.PubKeyCredential
                || cred instanceof Credential.ScriptCredential);
    }

    @Property(tries = 50)
    void addressHasCredential(@ForAll Address addr) {
        assertNotNull(addr.credential());
    }

    @Property(tries = 50)
    void txOutRefHasValidIndex(@ForAll TxOutRef ref) {
        assertTrue(ref.index().intValue() >= 0);
        assertTrue(ref.index().intValue() <= 9);
        assertEquals(32, ref.txId().hash().length);
    }

    @Property(tries = 50)
    void lovelaceValueIsPositive(@ForAll("lovelaceArb") Value val) {
        assertTrue(val.lovelaceOf().compareTo(BigInteger.ZERO) > 0,
                "Lovelace value should be positive: " + val.lovelaceOf());
    }

    @Provide
    Arbitrary<Value> lovelaceArb() {
        return CardanoArbitraries.lovelaceValue();
    }

    @Property(tries = 50)
    void multiAssetValueHasAda(@ForAll("multiAssetArb") Value val) {
        assertTrue(val.lovelaceOf().compareTo(BigInteger.ZERO) > 0,
                "Multi-asset value should have ADA: " + val.lovelaceOf());
    }

    @Provide
    Arbitrary<Value> multiAssetArb() {
        return CardanoArbitraries.multiAssetValue();
    }

    @Property(tries = 50)
    void outputDatumIsValid(@ForAll OutputDatum od) {
        assertNotNull(od);
        assertTrue(od instanceof OutputDatum.NoOutputDatum
                || od instanceof OutputDatum.OutputDatumHash
                || od instanceof OutputDatum.OutputDatumInline);
    }

    @Property(tries = 50)
    void txOutHasComponents(@ForAll TxOut txOut) {
        assertNotNull(txOut.address());
        assertNotNull(txOut.value());
        assertNotNull(txOut.datum());
    }

    @Property(tries = 50)
    void txInInfoHasComponents(@ForAll TxInInfo txIn) {
        assertNotNull(txIn.outRef());
        assertNotNull(txIn.resolved());
    }

    @Property(tries = 50)
    void intervalIsValid(@ForAll Interval interval) {
        assertNotNull(interval);
        assertNotNull(interval.from());
        assertNotNull(interval.to());
    }

    // --- PlutusData conversion ---

    @Property(tries = 50)
    void allLedgerTypesConvertToPlutusData(@ForAll TxOutRef ref) {
        PlutusData pd = ref.toPlutusData();
        assertNotNull(pd);
        assertInstanceOf(PlutusData.ConstrData.class, pd);
    }

    @Property(tries = 50)
    void valueConvertibleToPlutusData(@ForAll("lovelaceArb") Value val) {
        PlutusData pd = val.toPlutusData();
        assertNotNull(pd);
        assertInstanceOf(PlutusData.MapData.class, pd);
    }

    // --- Bounded lovelace value ---

    @Property(tries = 100)
    void boundedLovelaceValueRespectsRange(@ForAll("boundedLovelaceArb") Value val) {
        var lovelace = val.lovelaceOf();
        assertTrue(lovelace.compareTo(BigInteger.valueOf(2_000_000)) >= 0,
                "Lovelace should be >= 2_000_000, got: " + lovelace);
        assertTrue(lovelace.compareTo(BigInteger.valueOf(5_000_000)) <= 0,
                "Lovelace should be <= 5_000_000, got: " + lovelace);
    }

    @Provide
    Arbitrary<Value> boundedLovelaceArb() {
        return CardanoArbitraries.lovelaceValue(2_000_000, 5_000_000);
    }

    // --- CardanoArbitraryProvider direct tests ---

    @Example
    void providerCanProvideForAllRegisteredTypes() {
        var provider = new CardanoArbitraryProvider();
        var supportedTypes = java.util.List.of(
                PlutusData.class, PubKeyHash.class, ScriptHash.class, ValidatorHash.class,
                PolicyId.class, TokenName.class, DatumHash.class, TxId.class,
                Credential.class, Address.class, TxOutRef.class, Value.class,
                TxOut.class, TxInInfo.class, Interval.class, OutputDatum.class);
        for (var type : supportedTypes) {
            var typeUsage = net.jqwik.api.providers.TypeUsage.of(type);
            assertTrue(provider.canProvideFor(typeUsage),
                    "Provider should support " + type.getSimpleName());
            var arbitraries = provider.provideFor(typeUsage, null);
            assertFalse(arbitraries.isEmpty(),
                    "Provider should return non-empty set for " + type.getSimpleName());
        }
    }

    @Example
    void providerRejectsUnregisteredTypes() {
        var provider = new CardanoArbitraryProvider();
        var typeUsage = net.jqwik.api.providers.TypeUsage.of(String.class);
        assertFalse(provider.canProvideFor(typeUsage));
        assertTrue(provider.provideFor(typeUsage, null).isEmpty());
    }

    @Example
    void providerPriorityIsAboveDefault() {
        var provider = new CardanoArbitraryProvider();
        assertTrue(provider.priority() > 0,
                "Priority should be above default (0)");
    }
}
