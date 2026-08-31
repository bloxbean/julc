package com.bloxbean.cardano.julc.vm.scalus;

import com.bloxbean.cardano.julc.vm.LedgerEvaluationTarget;
import com.bloxbean.cardano.julc.vm.ProtocolFeatureProfile;
import scalus.cardano.ledger.Language;
import scalus.cardano.ledger.MajorProtocolVersion;
import scalus.uplc.eval.MachineParams;

import java.util.Objects;

/** One immutable, atomically published Scalus cost/semantics configuration. */
sealed interface ScalusConfiguration
        permits ReadyScalusConfiguration, UnsupportedScalusConfiguration {

    LedgerEvaluationTarget target();

    ProtocolFeatureProfile profile();
}

/** A target-bound configuration constructed from the caller-supplied cost array. */
record ReadyScalusConfiguration(
        LedgerEvaluationTarget target,
        ProtocolFeatureProfile profile,
        Language scalusLanguage,
        MajorProtocolVersion scalusProtocol,
        MachineParams machineParams) implements ScalusConfiguration {

    ReadyScalusConfiguration {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(scalusLanguage, "scalusLanguage");
        Objects.requireNonNull(scalusProtocol, "scalusProtocol");
        Objects.requireNonNull(machineParams, "machineParams");
        if (!profile.target().equals(target)) {
            throw new IllegalArgumentException("Profile target does not match " + target);
        }
        if (scalusProtocol.version() != target.protocolVersion().major()) {
            throw new IllegalArgumentException("Scalus protocol does not match " + target);
        }
    }
}

/** A resolved JuLC target that this Scalus adapter cannot yet honor faithfully. */
record UnsupportedScalusConfiguration(
        LedgerEvaluationTarget target,
        ProtocolFeatureProfile profile,
        String reason) implements ScalusConfiguration {

    UnsupportedScalusConfiguration {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(reason, "reason");
        if (!profile.target().equals(target)) {
            throw new IllegalArgumentException("Profile target does not match " + target);
        }
    }
}
