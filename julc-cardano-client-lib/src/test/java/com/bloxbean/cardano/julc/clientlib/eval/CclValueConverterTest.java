package com.bloxbean.cardano.julc.clientlib.eval;

import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.MultiAsset;
import com.bloxbean.cardano.julc.ledger.PolicyId;
import com.bloxbean.cardano.julc.ledger.TokenName;
import com.bloxbean.cardano.julc.ledger.Value;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CclValueConverterTest {

    @Test
    void fromAmounts_lovelaceOnly() {
        var amounts = List.of(Amount.lovelace(BigInteger.valueOf(5_000_000)));
        Value value = CclValueConverter.fromAmounts(amounts);

        assertEquals(BigInteger.valueOf(5_000_000), value.lovelaceOf());
    }

    @Test
    void fromAmounts_emptyList() {
        Value value = CclValueConverter.fromAmounts(List.of());
        assertTrue(value.isEmpty());
    }

    @Test
    void fromAmounts_nullList() {
        Value value = CclValueConverter.fromAmounts(null);
        assertTrue(value.isEmpty());
    }

    @Test
    void fromAmounts_multiAsset() {
        String policyHex = "ab".repeat(28);
        String assetHex = "cd".repeat(4);
        String unit = policyHex + assetHex;

        var amounts = List.of(
                Amount.lovelace(BigInteger.valueOf(2_000_000)),
                Amount.asset(unit, BigInteger.valueOf(100))
        );

        Value value = CclValueConverter.fromAmounts(amounts);

        assertEquals(BigInteger.valueOf(2_000_000), value.lovelaceOf());

        PolicyId policyId = PolicyId.of(HexFormat.of().parseHex(policyHex));
        TokenName tokenName = new TokenName(HexFormat.of().parseHex(assetHex));
        assertEquals(BigInteger.valueOf(100), value.assetOf(policyId, tokenName));
    }

    @Test
    void fromAmounts_multipleTokensSamePolicy() {
        String policyHex = "ab".repeat(28);
        String asset1Hex = "aa".repeat(4);
        String asset2Hex = "bb".repeat(4);

        var amounts = List.of(
                Amount.lovelace(BigInteger.valueOf(1_000_000)),
                Amount.asset(policyHex + asset1Hex, BigInteger.valueOf(50)),
                Amount.asset(policyHex + asset2Hex, BigInteger.valueOf(75))
        );

        Value value = CclValueConverter.fromAmounts(amounts);

        PolicyId policyId = PolicyId.of(HexFormat.of().parseHex(policyHex));
        assertEquals(BigInteger.valueOf(50),
                value.assetOf(policyId, new TokenName(HexFormat.of().parseHex(asset1Hex))));
        assertEquals(BigInteger.valueOf(75),
                value.assetOf(policyId, new TokenName(HexFormat.of().parseHex(asset2Hex))));
    }

    @Test
    void fromTransactionOutputValue_coinOnly() {
        var cclValue = new com.bloxbean.cardano.client.transaction.spec.Value(
                BigInteger.valueOf(3_000_000), null);
        Value value = CclValueConverter.fromTransactionOutputValue(cclValue);

        assertEquals(BigInteger.valueOf(3_000_000), value.lovelaceOf());
    }

    @Test
    void fromTransactionOutputValue_withMultiAsset() {
        String policyHex = "cd".repeat(28);
        String assetHex = "ef".repeat(4);

        var asset = new Asset(assetHex, BigInteger.valueOf(200));
        var multiAsset = new MultiAsset();
        multiAsset.setPolicyId(policyHex);
        multiAsset.setAssets(List.of(asset));

        var cclValue = new com.bloxbean.cardano.client.transaction.spec.Value(
                BigInteger.valueOf(1_500_000), List.of(multiAsset));
        Value value = CclValueConverter.fromTransactionOutputValue(cclValue);

        assertEquals(BigInteger.valueOf(1_500_000), value.lovelaceOf());
        assertEquals(BigInteger.valueOf(200),
                value.assetOf(PolicyId.of(HexFormat.of().parseHex(policyHex)),
                        new TokenName(HexFormat.of().parseHex(assetHex))));
    }

    @Test
    void fromTransactionOutputValue_null() {
        Value value = CclValueConverter.fromTransactionOutputValue(null);
        assertTrue(value.isEmpty());
    }

    @Test
    void fromMultiAssets_empty() {
        Value value = CclValueConverter.fromMultiAssets(List.of());
        assertTrue(value.isEmpty());
    }

    @Test
    void fromMultiAssets_null() {
        Value value = CclValueConverter.fromMultiAssets(null);
        assertTrue(value.isEmpty());
    }

    @Test
    void fromMultiAssets_singlePolicy() {
        String policyHex = "ab".repeat(28);
        String assetHex = "cd".repeat(4);

        var asset = new Asset(assetHex, BigInteger.valueOf(42));
        var multiAsset = new MultiAsset();
        multiAsset.setPolicyId(policyHex);
        multiAsset.setAssets(List.of(asset));

        Value value = CclValueConverter.fromMultiAssets(List.of(multiAsset));

        assertEquals(BigInteger.valueOf(42),
                value.assetOf(PolicyId.of(HexFormat.of().parseHex(policyHex)),
                        new TokenName(HexFormat.of().parseHex(assetHex))));
    }
}
