package com.bloxbean.cardano.julc.clientlib.eval;

import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.MultiAsset;
import com.bloxbean.cardano.julc.core.types.JulcAssocMap;
import com.bloxbean.cardano.julc.core.types.JulcMap;
import com.bloxbean.cardano.julc.ledger.PolicyId;
import com.bloxbean.cardano.julc.ledger.TokenName;
import com.bloxbean.cardano.julc.ledger.Value;

import java.math.BigInteger;
import java.util.HexFormat;
import java.util.List;

import static com.bloxbean.cardano.client.common.CardanoConstants.LOVELACE;

/**
 * Converts CCL value representations to JuLC {@link Value}.
 */
final class CclValueConverter {

    private CclValueConverter() {}

    /**
     * Convert CCL {@link Amount} list (from UTxOs) to JuLC {@link Value}.
     * <p>
     * Amount.unit is either "lovelace" or a concatenation of 56-char policyId hex + asset name hex.
     */
    static Value fromAmounts(List<Amount> amounts) {
        if (amounts == null || amounts.isEmpty()) {
            return Value.zero();
        }

        JulcMap<PolicyId, JulcMap<TokenName, BigInteger>> result = JulcAssocMap.empty();

        for (Amount amount : amounts) {
            String unit = amount.getUnit();
            BigInteger qty = amount.getQuantity();

            if (LOVELACE.equals(unit)) {
                result = insertToken(result, PolicyId.ADA, TokenName.EMPTY, qty);
            } else {
                // unit = policyIdHex (56 chars) + assetNameHex
                if (unit.length() < 56) {
                    throw new IllegalArgumentException("Invalid Amount unit: " + unit);
                }
                String policyHex = unit.substring(0, 56);
                String assetHex = unit.substring(56);

                PolicyId policyId = PolicyId.of(HexFormat.of().parseHex(policyHex));
                TokenName tokenName = new TokenName(
                        assetHex.isEmpty() ? new byte[0] : HexFormat.of().parseHex(assetHex));

                result = insertToken(result, policyId, tokenName, qty);
            }
        }

        return new Value(result);
    }

    /**
     * Convert CCL transaction output {@link com.bloxbean.cardano.client.transaction.spec.Value}
     * to JuLC {@link Value}.
     */
    static Value fromTransactionOutputValue(com.bloxbean.cardano.client.transaction.spec.Value cclValue) {
        if (cclValue == null) {
            return Value.zero();
        }

        JulcMap<PolicyId, JulcMap<TokenName, BigInteger>> result = JulcAssocMap.empty();

        // Add lovelace
        BigInteger coin = cclValue.getCoin();
        if (coin != null && coin.signum() != 0) {
            result = insertToken(result, PolicyId.ADA, TokenName.EMPTY, coin);
        }

        // Add multi-assets
        List<MultiAsset> multiAssets = cclValue.getMultiAssets();
        if (multiAssets != null) {
            for (MultiAsset ma : multiAssets) {
                PolicyId policyId = PolicyId.of(HexFormat.of().parseHex(ma.getPolicyId()));
                if (ma.getAssets() != null) {
                    for (Asset asset : ma.getAssets()) {
                        byte[] nameBytes = assetNameToBytes(asset.getName());
                        TokenName tokenName = new TokenName(nameBytes);
                        result = insertToken(result, policyId, tokenName, asset.getValue());
                    }
                }
            }
        }

        return new Value(result);
    }

    /**
     * Convert CCL mint field (List of MultiAsset) to JuLC {@link Value}.
     */
    static Value fromMultiAssets(List<MultiAsset> multiAssets) {
        if (multiAssets == null || multiAssets.isEmpty()) {
            return Value.zero();
        }

        JulcMap<PolicyId, JulcMap<TokenName, BigInteger>> result = JulcAssocMap.empty();

        for (MultiAsset ma : multiAssets) {
            PolicyId policyId = PolicyId.of(HexFormat.of().parseHex(ma.getPolicyId()));
            if (ma.getAssets() != null) {
                for (Asset asset : ma.getAssets()) {
                    byte[] nameBytes = assetNameToBytes(asset.getName());
                    TokenName tokenName = new TokenName(nameBytes);
                    result = insertToken(result, policyId, tokenName, asset.getValue());
                }
            }
        }

        return new Value(result);
    }

    private static JulcMap<PolicyId, JulcMap<TokenName, BigInteger>> insertToken(
            JulcMap<PolicyId, JulcMap<TokenName, BigInteger>> map,
            PolicyId policyId, TokenName tokenName, BigInteger qty) {

        JulcMap<TokenName, BigInteger> existing = map.get(policyId);
        if (existing == null) {
            existing = JulcAssocMap.<TokenName, BigInteger>empty().insert(tokenName, qty);
        } else {
            BigInteger prev = existing.get(tokenName);
            if (prev != null) {
                existing = existing.delete(tokenName).insert(tokenName, prev.add(qty));
            } else {
                existing = existing.insert(tokenName, qty);
            }
        }

        // Delete and re-insert to update the value
        if (map.get(policyId) != null) {
            map = map.delete(policyId);
        }
        return map.insert(policyId, existing);
    }

    private static byte[] assetNameToBytes(String assetName) {
        if (assetName == null || assetName.isEmpty()) {
            return new byte[0];
        }
        // CCL asset names are hex-encoded
        try {
            return HexFormat.of().parseHex(assetName);
        } catch (IllegalArgumentException e) {
            // If not valid hex, treat as UTF-8 string
            return assetName.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
