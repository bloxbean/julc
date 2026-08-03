package com.bloxbean.cardano.julc.vm;

import com.bloxbean.cardano.julc.core.DefaultFun;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Table-driven ledger feature mapping supported through PV11. The mapping was
 * verified against Plutus 1.63.0.0
 * ({@code f92b7d7d82622a26caf456a6be33859f697e2cfc}), as shipped by
 * cardano-node 11.0.1. Protocol versions newer than PV11 are deliberately
 * rejected until the supported protocol profile is explicitly upgraded.
 */
public final class ProtocolFeatureRegistry {

    public static final int MAX_SUPPORTED_PROTOCOL_MAJOR = 11;

    private static final Set<DefaultFun> BATCH_1 = tagRange(0, 50);
    private static final Set<DefaultFun> BATCH_2 = tagRange(51, 51);
    private static final Set<DefaultFun> BATCH_3 = tagRange(52, 53);
    private static final Set<DefaultFun> BATCH_4A = tagRange(54, 72);
    private static final Set<DefaultFun> BATCH_4B = tagRange(73, 74);
    private static final Set<DefaultFun> BATCH_5 = tagRange(75, 86);
    private static final Set<DefaultFun> BATCH_6 = tagRange(87, 100);

    private ProtocolFeatureRegistry() {
    }

    public static ProtocolFeatureProfile resolve(LedgerEvaluationTarget target) {
        int protocol = target.protocolVersion().major();
        if (protocol > MAX_SUPPORTED_PROTOCOL_MAJOR) {
            throw new UnsupportedLedgerTargetException(
                    "Protocol version " + target.protocolVersion()
                            + " is newer than the supported PV11 profile");
        }

        int introduced = introducedIn(target.ledgerLanguage());
        if (protocol < introduced) {
            throw new UnsupportedLedgerTargetException(
                    target.ledgerLanguage() + " is not available at protocol version "
                            + target.protocolVersion() + " (introduced in PV" + introduced + ")");
        }

        var builtins = availableBuiltins(target.ledgerLanguage(), protocol);
        var uplcVersions = availableUplcVersions(target.ledgerLanguage(), protocol);
        var variant = semanticsVariant(target.ledgerLanguage(), protocol);
        var limits = protocol >= 11 ? DecodeLimits.PV11 : DecodeLimits.UNBOUNDED;

        return new ProtocolFeatureProfile(
                target,
                variant,
                builtins,
                uplcVersions,
                protocol >= 11,
                limits,
                costModelSchema(target.ledgerLanguage(), protocol));
    }

    private static int introducedIn(PlutusLanguage language) {
        return switch (language) {
            case PLUTUS_V1 -> 5;
            case PLUTUS_V2 -> 7;
            case PLUTUS_V3 -> 9;
        };
    }

    private static BuiltinSemanticsVariant semanticsVariant(
            PlutusLanguage language, int protocol) {
        if (protocol >= 11) {
            return language == PlutusLanguage.PLUTUS_V3
                    ? BuiltinSemanticsVariant.E
                    : BuiltinSemanticsVariant.D;
        }
        if (language == PlutusLanguage.PLUTUS_V3) {
            return BuiltinSemanticsVariant.C;
        }
        return protocol >= 9 ? BuiltinSemanticsVariant.B : BuiltinSemanticsVariant.A;
    }

    private static Set<UplcVersion> availableUplcVersions(
            PlutusLanguage language, int protocol) {
        if (language == PlutusLanguage.PLUTUS_V3 || protocol >= 11) {
            return Set.of(UplcVersion.V1_0_0, UplcVersion.V1_1_0);
        }
        return Set.of(UplcVersion.V1_0_0);
    }

    private static Set<DefaultFun> availableBuiltins(
            PlutusLanguage language, int protocol) {
        var available = EnumSet.noneOf(DefaultFun.class);
        switch (language) {
            case PLUTUS_V1 -> {
                available.addAll(BATCH_1);
                if (protocol >= 11) {
                    addAll(available, BATCH_2, BATCH_3, BATCH_4A, BATCH_4B, BATCH_5, BATCH_6);
                }
            }
            case PLUTUS_V2 -> {
                addAll(available, BATCH_1, BATCH_2);
                if (protocol >= 8) available.addAll(BATCH_3);
                if (protocol >= 10) available.addAll(BATCH_4B);
                if (protocol >= 11) addAll(available, BATCH_4A, BATCH_5, BATCH_6);
            }
            case PLUTUS_V3 -> {
                addAll(available, BATCH_1, BATCH_2, BATCH_3, BATCH_4A, BATCH_4B);
                if (protocol >= 10) available.addAll(BATCH_5);
                if (protocol >= 11) available.addAll(BATCH_6);
            }
        }
        return Collections.unmodifiableSet(available);
    }

    private static CostModelSchema costModelSchema(PlutusLanguage language, int protocol) {
        return switch (language) {
            case PLUTUS_V1 -> protocol >= 11
                    ? CostModelSchema.PLUTUS_V1_PV11
                    : CostModelSchema.PLUTUS_V1_LEGACY;
            case PLUTUS_V2 -> protocol >= 11
                    ? CostModelSchema.PLUTUS_V2_PV11
                    : protocol >= 10
                    ? CostModelSchema.PLUTUS_V2_PV10
                    : CostModelSchema.PLUTUS_V2_LEGACY;
            case PLUTUS_V3 -> protocol >= 11
                    ? CostModelSchema.PLUTUS_V3_PV11
                    : protocol >= 10
                    ? CostModelSchema.PLUTUS_V3_PV10
                    : CostModelSchema.PLUTUS_V3_PV9;
        };
    }

    @SafeVarargs
    private static void addAll(EnumSet<DefaultFun> target, Set<DefaultFun>... batches) {
        for (var batch : batches) target.addAll(batch);
    }

    private static Set<DefaultFun> tagRange(int first, int last) {
        var result = EnumSet.noneOf(DefaultFun.class);
        for (int tag = first; tag <= last; tag++) {
            result.add(DefaultFun.fromFlatCode(tag));
        }
        return Collections.unmodifiableSet(result);
    }
}
