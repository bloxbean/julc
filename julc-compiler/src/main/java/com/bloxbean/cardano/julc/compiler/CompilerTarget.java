package com.bloxbean.cardano.julc.compiler;

import com.bloxbean.cardano.julc.vm.LedgerEvaluationTarget;
import com.bloxbean.cardano.julc.vm.PlutusLanguage;
import com.bloxbean.cardano.julc.vm.UplcVersion;

import java.util.Objects;

/**
 * The complete ledger profile selected for one JuLC compilation.
 *
 * <p>A compiler target is deliberately more specific than a Plutus language:
 * protocol and UPLC versions also control which terms and builtins are legal.
 */
public record CompilerTarget(
        LedgerEvaluationTarget ledgerTarget,
        UplcVersion uplcVersion) {

    /** The sole compiler target supported by this release. */
    public static final CompilerTarget PLUTUS_V3_PV11 = new CompilerTarget(
            LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V3),
            UplcVersion.V1_1_0);

    public CompilerTarget {
        Objects.requireNonNull(ledgerTarget, "ledgerTarget");
        Objects.requireNonNull(uplcVersion, "uplcVersion");
    }

    /** Stable identifier for diagnostics and build provenance. */
    public String profileId() {
        var language = switch (ledgerTarget.ledgerLanguage()) {
            case PLUTUS_V1 -> "plutus-v1";
            case PLUTUS_V2 -> "plutus-v2";
            case PLUTUS_V3 -> "plutus-v3";
        };
        var protocol = ledgerTarget.protocolVersion();
        var protocolId = protocol.minor() == 0
                ? "pv" + protocol.major()
                : "pv" + protocol.major() + "." + protocol.minor();
        return language + "-" + protocolId + "-uplc-" + uplcVersion;
    }

    @Override
    public String toString() {
        return profileId();
    }
}
