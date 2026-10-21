package org.julclang.clientlib.eval;

import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.MultiAsset;
import org.julclang.core.types.JulcMap;
import org.julclang.ledger.PolicyId;
import org.julclang.ledger.TokenName;
import org.julclang.ledger.Value;

import java.math.BigInteger;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.TreeMap;

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

        var assets = withLovelace(BigInteger.ZERO);
        for (Amount amount : amounts) {
            String unit = amount.getUnit();
            BigInteger qty = amount.getQuantity();

            if (LOVELACE.equals(unit)) {
                add(assets, new byte[0], new byte[0], qty);
            } else {
                // unit = policyIdHex (56 chars) + assetNameHex
                if (unit.length() < 56) {
                    throw new IllegalArgumentException("Invalid Amount unit: " + unit);
                }
                add(assets, HexFormat.of().parseHex(unit.substring(0, 56)),
                        HexFormat.of().parseHex(unit.substring(56)), qty);
            }
        }
        return toValue(assets);
    }

    /**
     * Convert CCL transaction output {@link com.bloxbean.cardano.client.transaction.spec.Value}
     * to JuLC {@link Value}. The lovelace entry is always present, as in the ledger's {@code transValue}.
     */
    static Value fromTransactionOutputValue(com.bloxbean.cardano.client.transaction.spec.Value cclValue) {
        if (cclValue == null) {
            return Value.zero();
        }

        var assets = withLovelace(cclValue.getCoin() != null ? cclValue.getCoin() : BigInteger.ZERO);
        addMultiAssets(assets, cclValue.getMultiAssets());
        return toValue(assets);
    }

    /**
     * Convert CCL mint field (List of MultiAsset) to JuLC {@link Value}.
     */
    static Value fromMultiAssets(List<MultiAsset> multiAssets) {
        if (multiAssets == null || multiAssets.isEmpty()) {
            return Value.zero();
        }

        var assets = new TreeMap<byte[], TreeMap<byte[], BigInteger>>(LedgerOrder.BYTES);
        addMultiAssets(assets, multiAssets);
        return toValue(assets);
    }

    private static TreeMap<byte[], TreeMap<byte[], BigInteger>> withLovelace(BigInteger coin) {
        var assets = new TreeMap<byte[], TreeMap<byte[], BigInteger>>(LedgerOrder.BYTES);
        add(assets, new byte[0], new byte[0], coin);
        return assets;
    }

    private static void addMultiAssets(TreeMap<byte[], TreeMap<byte[], BigInteger>> assets,
                                       List<MultiAsset> multiAssets) {
        if (multiAssets == null) {
            return;
        }
        for (MultiAsset ma : multiAssets) {
            if (ma.getAssets() != null) {
                byte[] policy = HexFormat.of().parseHex(ma.getPolicyId());
                for (Asset asset : ma.getAssets()) {
                    add(assets, policy, assetNameToBytes(asset.getName()), asset.getValue());
                }
            }
        }
    }

    private static void add(TreeMap<byte[], TreeMap<byte[], BigInteger>> assets,
                            byte[] policy, byte[] name, BigInteger qty) {
        assets.computeIfAbsent(policy, p -> new TreeMap<>(LedgerOrder.BYTES)).merge(name, qty, BigInteger::add);
    }

    /** The ledger's {@code transValue}/{@code transMultiAsset}: policies, then names, in byte order. */
    private static Value toValue(TreeMap<byte[], TreeMap<byte[], BigInteger>> assets) {
        var policies = new LinkedHashMap<PolicyId, JulcMap<TokenName, BigInteger>>();
        assets.forEach((policy, names) -> {
            var tokens = new LinkedHashMap<TokenName, BigInteger>();
            names.forEach((name, qty) -> tokens.put(new TokenName(name), qty));
            policies.put(PolicyId.of(policy), LedgerOrder.assocMap(tokens));
        });
        return new Value(LedgerOrder.assocMap(policies));
    }

    static byte[] assetNameToBytes(String assetName) {
        if (assetName == null || assetName.isEmpty()) {
            return new byte[0];
        }
        // Strip 0x/0X prefix — its presence means the string IS hex
        String hex = assetName;
        if (hex.startsWith("0x") || hex.startsWith("0X")) {
            hex = hex.substring(2);
        }
        // CCL asset names are hex-encoded; no UTF-8 fallback (garbage in should not be silently accepted)
        return HexFormat.of().parseHex(hex);
    }
}
