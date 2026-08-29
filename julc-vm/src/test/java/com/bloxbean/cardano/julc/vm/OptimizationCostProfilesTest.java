package com.bloxbean.cardano.julc.vm;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OptimizationCostProfilesTest {

    @Test
    void pinnedPv11ProfileHasExactProvenanceAndParameters() {
        var profile = OptimizationCostProfiles.CARDANO_NODE_11_0_1_PLUTUS_V3_PV11;

        assertEquals("cardano-node-11.0.1-plutus-v3-pv11", profile.profileId());
        assertEquals(LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V3), profile.target());
        assertEquals(
                "40ea9e0b7df77a7bd2cb7d4e4d9da040f8bee7ff0324a7cdb7e51702330e43a8",
                profile.parameterHash());
        assertEquals(350, profile.parameterCount());
        assertTrue(profile.source().contains("f92b7d7d82622a26caf456a6be33859f697e2cfc"));
        assertEquals(profile,
                OptimizationCostProfiles.forId(
                        "cardano-node-11.0.1-plutus-v3-pv11"));
    }

    @Test
    void parameterArrayIsDefensivelyCopied() {
        var profile = OptimizationCostProfiles.CARDANO_NODE_11_0_1_PLUTUS_V3_PV11;
        var first = profile.costModelParameters();
        var original = first[0];
        first[0] = original + 1;

        assertEquals(original, profile.costModelParameters()[0]);
        assertNotEquals(Arrays.hashCode(first),
                Arrays.hashCode(profile.costModelParameters()));
    }

    @Test
    void lookupIsExactAndFailClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> OptimizationCostProfiles.forId(
                        "CARDANO-NODE-11.0.1-PLUTUS-V3-PV11"));
        assertThrows(IllegalArgumentException.class,
                () -> OptimizationCostProfiles.forId(
                        "cardano-node-12-plutus-v3-pv12"));
    }
}
