package org.julclang.vm;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OptimizationCostProfilesTest {

    @Test
    void pinnedPv11ProfileHasExactProvenanceAndParameters() {
        var profile = OptimizationCostProfiles.PLUTUS_V3_PV11_COSTS_V1;

        assertEquals("plutus-v3-pv11-costs-v1", profile.profileId());
        assertEquals(LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V3), profile.target());
        assertEquals(
                "40ea9e0b7df77a7bd2cb7d4e4d9da040f8bee7ff0324a7cdb7e51702330e43a8",
                profile.parameterHash());
        assertEquals(350, profile.parameterCount());
        assertEquals("bundled:plutus-v3-pv11-costs-v1", profile.source());
        assertSame(profile, OptimizationCostProfiles.forId("plutus-v3-pv11-costs-v1"));
        assertSame(profile,
                OptimizationCostProfiles.forId(
                        "cardano-node-11.0.1-plutus-v3-pv11"));
    }

    @Test
    @SuppressWarnings("deprecation")
    void legacyConstantsAliasTheSameImmutableSnapshot() {
        assertSame(OptimizationCostProfiles.PLUTUS_V3_PV11_COSTS_V1,
                OptimizationCostProfiles.CARDANO_NODE_11_0_1_PLUTUS_V3_PV11);
        assertSame(OptimizationCostProfiles.PLUTUS_V3_PV11_COSTS_V1,
                OptimizationCostProfiles.forId(OptimizationCostProfiles.CARDANO_NODE_11_0_1_PLUTUS_V3_PV11_ID));
        assertEquals(OptimizationCostProfiles.PLUTUS_V3_PV11_COSTS_V1_PARAMETER_HASH,
                OptimizationCostProfiles.CARDANO_NODE_11_0_1_PLUTUS_V3_PV11_PARAMETER_HASH);
        assertTrue(OptimizationCostProfiles.supportedProfileIds().contains("plutus-v3-pv11-costs-v1"));
    }

    @Test
    void parameterArrayIsDefensivelyCopied() {
        var profile = OptimizationCostProfiles.PLUTUS_V3_PV11_COSTS_V1;
        var first = profile.costModelParameters();
        var original = first[0];
        first[0] = original + 1;

        assertEquals(original, profile.costModelParameters()[0]);
        assertNotEquals(Arrays.hashCode(first),
                Arrays.hashCode(profile.costModelParameters()));
    }

    @Test
    void lookupIsExactAndFailClosed() {
        for (var id : new String[] { "latest", "plutus-v3-pv11", "plutus-v3-pv11-costs-v2",
                "PLUTUS-V3-PV11-COSTS-V1", " plutus-v3-pv11-costs-v1" }) {
            assertThrows(IllegalArgumentException.class, () -> OptimizationCostProfiles.forId(id));
        }
        assertThrows(IllegalArgumentException.class,
                () -> OptimizationCostProfiles.forId(
                        "CARDANO-NODE-11.0.1-PLUTUS-V3-PV11"));
        assertThrows(IllegalArgumentException.class,
                () -> OptimizationCostProfiles.forId(
                        "cardano-node-12-plutus-v3-pv12"));
    }
}
