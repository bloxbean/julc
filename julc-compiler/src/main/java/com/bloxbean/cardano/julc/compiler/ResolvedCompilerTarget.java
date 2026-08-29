package com.bloxbean.cardano.julc.compiler;

import com.bloxbean.cardano.julc.vm.ProtocolFeatureProfile;

import java.util.Objects;

/** One validated compiler target and its canonical protocol feature profile. */
record ResolvedCompilerTarget(
        CompilerTarget target,
        ProtocolFeatureProfile featureProfile) {

    ResolvedCompilerTarget {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(featureProfile, "featureProfile");
        if (!target.ledgerTarget().equals(featureProfile.target())) {
            throw new IllegalArgumentException(
                    "Compiler target and protocol feature profile do not match");
        }
    }
}
