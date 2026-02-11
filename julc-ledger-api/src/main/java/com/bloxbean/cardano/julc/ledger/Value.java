package com.bloxbean.cardano.julc.ledger;

import com.bloxbean.cardano.julc.core.PlutusData;

import java.math.BigInteger;
import java.util.*;

/**
 * A multi-asset value: Map<PolicyId, Map<TokenName, BigInteger>>.
 * <p>
 * Encoding: PlutusData.Map of (BytesData(policyId) -> Map of (BytesData(tokenName) -> IntData(quantity)))
 */
public record Value(Map<PolicyId, Map<TokenName, BigInteger>> inner) implements PlutusDataConvertible {

    public Value {
        inner = Collections.unmodifiableMap(new LinkedHashMap<>(inner));
    }

    public static Value zero() {
        return new Value(Map.of());
    }

    public static Value lovelace(BigInteger amount) {
        return singleton(PolicyId.ADA, TokenName.EMPTY, amount);
    }

    public static Value singleton(PolicyId policyId, TokenName tokenName, BigInteger quantity) {
        return new Value(Map.of(policyId, Map.of(tokenName, quantity)));
    }

    public BigInteger getLovelace() {
        return getAsset(PolicyId.ADA, TokenName.EMPTY);
    }

    public BigInteger getAsset(PolicyId policyId, TokenName tokenName) {
        var tokens = inner.getOrDefault(policyId, Map.of());
        return tokens.getOrDefault(tokenName, BigInteger.ZERO);
    }

    public Value merge(Value other) {
        var result = new LinkedHashMap<PolicyId, Map<TokenName, BigInteger>>();
        // Copy this
        for (var entry : inner.entrySet()) {
            result.put(entry.getKey(), new LinkedHashMap<>(entry.getValue()));
        }
        // Merge other
        for (var entry : other.inner.entrySet()) {
            var existing = result.computeIfAbsent(entry.getKey(), _ -> new LinkedHashMap<>());
            for (var tokenEntry : entry.getValue().entrySet()) {
                existing.merge(tokenEntry.getKey(), tokenEntry.getValue(), BigInteger::add);
            }
        }
        return new Value(result);
    }

    @Override
    public PlutusData toPlutusData() {
        List<PlutusData.Pair> outerEntries = new ArrayList<>();
        for (var entry : inner.entrySet()) {
            List<PlutusData.Pair> innerEntries = new ArrayList<>();
            for (var tokenEntry : entry.getValue().entrySet()) {
                innerEntries.add(new PlutusData.Pair(
                        tokenEntry.getKey().toPlutusData(),
                        new PlutusData.IntData(tokenEntry.getValue())));
            }
            outerEntries.add(new PlutusData.Pair(
                    entry.getKey().toPlutusData(),
                    new PlutusData.Map(innerEntries)));
        }
        return new PlutusData.Map(outerEntries);
    }

    public static Value fromPlutusData(PlutusData data) {
        if (data instanceof PlutusData.Map m) {
            var result = new LinkedHashMap<PolicyId, Map<TokenName, BigInteger>>();
            for (var pair : m.entries()) {
                var policyId = PolicyId.fromPlutusData(pair.key());
                if (pair.value() instanceof PlutusData.Map innerMap) {
                    var tokens = new LinkedHashMap<TokenName, BigInteger>();
                    for (var innerPair : innerMap.entries()) {
                        tokens.put(TokenName.fromPlutusData(innerPair.key()),
                                PlutusDataHelper.decodeInteger(innerPair.value()));
                    }
                    result.put(policyId, tokens);
                } else {
                    throw new IllegalArgumentException("Expected inner Map for Value, got: " + pair.value().getClass().getSimpleName());
                }
            }
            return new Value(result);
        }
        throw new IllegalArgumentException("Expected Map for Value, got: " + data.getClass().getSimpleName());
    }
}
