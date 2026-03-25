package com.bloxbean.cardano.julc.testkit.jqwik;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.*;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.providers.ArbitraryProvider;
import net.jqwik.api.providers.TypeUsage;

import java.util.Map;
import java.util.Set;

/**
 * SPI auto-provider for Cardano types in jqwik.
 * <p>
 * Registered via {@code META-INF/services/net.jqwik.api.providers.ArbitraryProvider},
 * this allows writing {@code @ForAll PubKeyHash pkh} without explicit {@code @Provide} methods.
 */
public class CardanoArbitraryProvider implements ArbitraryProvider {

    // Arbitrary instances are immutable and stateless — safe to cache.
    private static final Map<Class<?>, Arbitrary<?>> PROVIDERS = Map.ofEntries(
            Map.entry(PlutusData.class, CardanoArbitraries.plutusData()),
            Map.entry(PubKeyHash.class, CardanoArbitraries.pubKeyHash()),
            Map.entry(ScriptHash.class, CardanoArbitraries.scriptHash()),
            Map.entry(ValidatorHash.class, CardanoArbitraries.validatorHash()),
            Map.entry(PolicyId.class, CardanoArbitraries.policyId()),
            Map.entry(TokenName.class, CardanoArbitraries.tokenName()),
            Map.entry(DatumHash.class, CardanoArbitraries.datumHash()),
            Map.entry(TxId.class, CardanoArbitraries.txId()),
            Map.entry(Credential.class, CardanoArbitraries.credential()),
            Map.entry(Address.class, CardanoArbitraries.address()),
            Map.entry(TxOutRef.class, CardanoArbitraries.txOutRef()),
            Map.entry(Value.class, CardanoArbitraries.value()),
            Map.entry(TxOut.class, CardanoArbitraries.txOut()),
            Map.entry(TxInInfo.class, CardanoArbitraries.txInInfo()),
            Map.entry(Interval.class, CardanoArbitraries.interval()),
            Map.entry(OutputDatum.class, CardanoArbitraries.outputDatum())
    );

    @Override
    public boolean canProvideFor(TypeUsage targetType) {
        return PROVIDERS.containsKey(targetType.getRawType());
    }

    @Override
    public Set<Arbitrary<?>> provideFor(TypeUsage targetType, SubtypeProvider subtypeProvider) {
        var arb = PROVIDERS.get(targetType.getRawType());
        if (arb != null) {
            return Set.of(arb);
        }
        return Set.of();
    }

    @Override
    public int priority() {
        return 1; // above default (0) so Cardano types are resolved before generic fallbacks
    }
}
