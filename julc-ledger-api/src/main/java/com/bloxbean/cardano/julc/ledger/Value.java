package com.bloxbean.cardano.julc.ledger;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.types.JulcAssocMap;
import com.bloxbean.cardano.julc.core.types.JulcMap;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * A multi-asset value: JulcMap&lt;PolicyId, JulcMap&lt;TokenName, BigInteger&gt;&gt;.
 * <p>
 * Encoding: PlutusData.MapData of (BytesData(policyId) -&gt; Map of (BytesData(tokenName) -&gt; IntData(quantity)))
 */
public record Value(JulcMap<PolicyId, JulcMap<TokenName, BigInteger>> inner) implements PlutusDataConvertible {

    public static Value zero() {
        return new Value(JulcMap.empty());
    }

    public static Value lovelace(BigInteger amount) {
        return singleton(PolicyId.ADA, TokenName.EMPTY, amount);
    }

    public static Value singleton(PolicyId policyId, TokenName tokenName, BigInteger quantity) {
        return new Value(JulcMap.of(policyId, JulcMap.of(tokenName, quantity)));
    }

    public BigInteger lovelaceOf() {
        return assetOf(PolicyId.ADA, TokenName.EMPTY);
    }

    public boolean containsPolicy(PolicyId policyId) {
        return inner.containsKey(policyId);
    }

    public BigInteger assetOf(PolicyId policyId, TokenName tokenName) {
        JulcMap<TokenName, BigInteger> tokens = inner.get(policyId);
        if (tokens == null) return BigInteger.ZERO;
        BigInteger amount = tokens.get(tokenName);
        return amount != null ? amount : BigInteger.ZERO;
    }

    public boolean isEmpty() {
        return inner.isEmpty();
    }

    /**
     * Merge two values by adding all token quantities.
     * Equivalent to on-chain ValuesLib.add().
     */
    public Value merge(Value other) {
        // Start with a mutable copy of this value's entries
        JulcMap<PolicyId, JulcMap<TokenName, BigInteger>> result = inner;

        for (PolicyId policy : other.inner.keys()) {
            JulcMap<TokenName, BigInteger> otherTokens = other.inner.get(policy);
            JulcMap<TokenName, BigInteger> existing = result.get(policy);

            if (existing == null) {
                result = result.insert(policy, otherTokens);
            } else {
                // Merge token maps
                JulcMap<TokenName, BigInteger> merged = existing;
                for (TokenName tn : otherTokens.keys()) {
                    BigInteger otherAmount = otherTokens.get(tn);
                    BigInteger existingAmount = merged.get(tn);
                    if (existingAmount == null) {
                        merged = merged.insert(tn, otherAmount);
                    } else {
                        merged = merged.delete(tn).insert(tn, existingAmount.add(otherAmount));
                    }
                }
                result = result.delete(policy).insert(policy, merged);
            }
        }
        return new Value(result);
    }

    @Override
    public PlutusData.MapData toPlutusData() {
        List<PlutusData.Pair> outerEntries = new ArrayList<>();
        PlutusData.Pair adaEntry = null;

        for (PolicyId policyId : inner.keys()) {
            JulcMap<TokenName, BigInteger> tokens = inner.get(policyId);
            List<PlutusData.Pair> innerEntries = new ArrayList<>();
            for (TokenName tokenName : tokens.keys()) {
                innerEntries.add(new PlutusData.Pair(
                        tokenName.toPlutusData(),
                        new PlutusData.IntData(tokens.get(tokenName))));
            }
            var pair = new PlutusData.Pair(
                    policyId.toPlutusData(),
                    new PlutusData.MapData(innerEntries));
            if (policyId.equals(PolicyId.ADA)) {
                adaEntry = pair;
            } else {
                outerEntries.add(pair);
            }
        }

        // Ensure ADA entry is first, matching Cardano ledger invariant
        if (adaEntry != null) {
            outerEntries.addFirst(adaEntry);
        }
        return new PlutusData.MapData(outerEntries);
    }

    public static Value fromPlutusData(PlutusData data) {
        if (data instanceof PlutusData.MapData m) {
            // Build by inserting in reverse order to preserve entry order
            JulcMap<PolicyId, JulcMap<TokenName, BigInteger>> result = JulcAssocMap.empty();
            var pairs = m.entries();
            for (int i = pairs.size() - 1; i >= 0; i--) {
                var pair = pairs.get(i);
                var policyId = PolicyId.fromPlutusData(pair.key());
                if (pair.value() instanceof PlutusData.MapData innerMap) {
                    JulcMap<TokenName, BigInteger> tokens = JulcAssocMap.empty();
                    var innerPairs = innerMap.entries();
                    for (int j = innerPairs.size() - 1; j >= 0; j--) {
                        var innerPair = innerPairs.get(j);
                        tokens = tokens.insert(
                                TokenName.fromPlutusData(innerPair.key()),
                                PlutusDataHelper.decodeInteger(innerPair.value()));
                    }
                    result = result.insert(policyId, tokens);
                } else {
                    throw new IllegalArgumentException("Expected inner Map for Value, got: " + pair.value().getClass().getSimpleName());
                }
            }
            return new Value(result);
        }
        throw new IllegalArgumentException("Expected Map for Value, got: " + data.getClass().getSimpleName());
    }
}
