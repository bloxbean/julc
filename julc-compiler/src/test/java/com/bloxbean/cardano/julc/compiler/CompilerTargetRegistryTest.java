package com.bloxbean.cardano.julc.compiler;

import com.bloxbean.cardano.julc.vm.LedgerEvaluationTarget;
import com.bloxbean.cardano.julc.vm.PlutusLanguage;
import com.bloxbean.cardano.julc.vm.ProtocolVersion;
import com.bloxbean.cardano.julc.vm.UplcVersion;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompilerTargetRegistryTest {

    @Test
    void pv11TargetHasStableIdentity() {
        var target = CompilerTarget.PLUTUS_V3_PV11;

        assertEquals(PlutusLanguage.PLUTUS_V3, target.ledgerTarget().ledgerLanguage());
        assertEquals(ProtocolVersion.PV11, target.ledgerTarget().protocolVersion());
        assertEquals(UplcVersion.V1_1_0, target.uplcVersion());
        assertEquals("plutus-v3-pv11-uplc-1.1.0", target.profileId());
        assertEquals(target.profileId(), target.toString());
    }

    @Test
    void optionsDefaultToNamedPv11Target() {
        assertSame(CompilerTarget.PLUTUS_V3_PV11, new CompilerOptions().getTarget());
    }

    @Test
    void optionsRetainExplicitTargetWithoutResolvingOrFallingBack() {
        var unsupported = new CompilerTarget(
                LedgerEvaluationTarget.pv10(PlutusLanguage.PLUTUS_V3),
                UplcVersion.V1_1_0);
        var options = new CompilerOptions().setTarget(unsupported);

        assertSame(unsupported, options.getTarget());
        assertThrows(NullPointerException.class, () -> options.setTarget(null));
    }

    @Test
    void registryResolvesOneCanonicalProfile() {
        var resolved = CompilerTargetRegistry.resolve(CompilerTarget.PLUTUS_V3_PV11);

        assertSame(CompilerTarget.PLUTUS_V3_PV11, resolved.target());
        assertEquals(resolved.target().ledgerTarget(), resolved.featureProfile().target());
        assertTrue(resolved.featureProfile().isUplcVersionAvailable(UplcVersion.V1_1_0));
        assertEquals(Set.of(CompilerTarget.PLUTUS_V3_PV11),
                CompilerTargetRegistry.supportedTargets());
        assertTrue(CompilerTargetRegistry.isSupported(CompilerTarget.PLUTUS_V3_PV11));
        assertFalse(CompilerTargetRegistry.isSupported(null));
        assertThrows(UnsupportedOperationException.class,
                () -> CompilerTargetRegistry.supportedTargets().clear());
        assertSame(CompilerTarget.PLUTUS_V3_PV11,
                CompilerTargetRegistry.targetForProfileId(
                        "plutus-v3-pv11-uplc-1.1.0"));
    }

    @Test
    void profileIdLookupIsExactAndFailsClosed() {
        for (var profileId : Set.of(
                "PLUTUS-V3-PV11-UPLC-1.1.0",
                "plutus-v3-pv12-uplc-1.1.0",
                "latest")) {
            var error = assertThrows(CompilerException.class,
                    () -> CompilerTargetRegistry.targetForProfileId(profileId));
            assertEquals("JULC0031", error.diagnostics().getFirst().code());
            assertTrue(error.getMessage().contains(profileId));
        }
    }

    @Test
    void unsupportedTargetsFailWithStableDiagnosticAndNoFallback() {
        var targets = Set.of(
                new CompilerTarget(
                        LedgerEvaluationTarget.pv10(PlutusLanguage.PLUTUS_V3),
                        UplcVersion.V1_1_0),
                new CompilerTarget(
                        LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V1),
                        UplcVersion.V1_1_0),
                new CompilerTarget(
                        LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V2),
                        UplcVersion.V1_1_0),
                new CompilerTarget(
                        LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V3),
                        UplcVersion.V1_0_0),
                new CompilerTarget(
                        new LedgerEvaluationTarget(
                                PlutusLanguage.PLUTUS_V3, new ProtocolVersion(12, 0)),
                        UplcVersion.V1_1_0));

        for (var target : targets) {
            var error = assertThrows(CompilerException.class,
                    () -> CompilerTargetRegistry.resolve(target));

            assertTrue(error.getMessage().contains(target.profileId()));
            assertTrue(error.getMessage().contains(
                    CompilerTarget.PLUTUS_V3_PV11.profileId()));
            assertEquals(1, error.diagnostics().size());
            assertEquals("JULC0031", error.diagnostics().getFirst().code());
        }
    }

    @Test
    void targetComponentsAreRequired() {
        assertThrows(NullPointerException.class,
                () -> new CompilerTarget(null, UplcVersion.V1_1_0));
        assertThrows(NullPointerException.class,
                () -> new CompilerTarget(
                        LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V3), null));
        assertNotNull(CompilerTarget.PLUTUS_V3_PV11);
    }
}
