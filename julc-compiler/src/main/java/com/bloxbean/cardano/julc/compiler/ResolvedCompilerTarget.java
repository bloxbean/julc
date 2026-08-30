package com.bloxbean.cardano.julc.compiler;

import com.bloxbean.cardano.julc.vm.ProtocolFeatureProfile;

import java.util.Objects;

/**
 * One validated compiler target and its canonical protocol feature profile.
 *
 * <p>This is compiler pipeline state. Obtain it through
 * {@link CompilerTargetRegistry#resolve(CompilerTarget)} so the compiler support
 * policy and VM feature registry cannot be bypassed.
 */
public final class ResolvedCompilerTarget {

    private final CompilerTarget target;
    private final ProtocolFeatureProfile featureProfile;

    ResolvedCompilerTarget(
            CompilerTarget target,
            ProtocolFeatureProfile featureProfile) {
        this.target = Objects.requireNonNull(target, "target");
        this.featureProfile = Objects.requireNonNull(featureProfile, "featureProfile");
        if (!target.ledgerTarget().equals(featureProfile.target())) {
            throw new IllegalArgumentException(
                    "Compiler target and protocol feature profile do not match");
        }
    }

    public CompilerTarget target() {
        return target;
    }

    public ProtocolFeatureProfile featureProfile() {
        return featureProfile;
    }
}
