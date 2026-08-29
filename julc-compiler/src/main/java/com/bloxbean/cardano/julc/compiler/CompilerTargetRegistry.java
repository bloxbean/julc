package com.bloxbean.cardano.julc.compiler;

import com.bloxbean.cardano.julc.compiler.error.CompilerDiagnostic;
import com.bloxbean.cardano.julc.compiler.error.DiagnosticCodes;
import com.bloxbean.cardano.julc.vm.ProtocolFeatureRegistry;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Exact, fail-closed set of ledger profiles JuLC can compile. */
public final class CompilerTargetRegistry {

    private static final Set<CompilerTarget> SUPPORTED =
            Set.of(CompilerTarget.PLUTUS_V3_PV11);

    private CompilerTargetRegistry() {
    }

    /** Return the immutable set of compiler targets supported by this release. */
    public static Set<CompilerTarget> supportedTargets() {
        return SUPPORTED;
    }

    /** Whether the exact language/protocol/UPLC target is supported. */
    public static boolean isSupported(CompilerTarget target) {
        return target != null && SUPPORTED.contains(target);
    }

    /**
     * Resolve a stable profile ID to an exactly supported compiler target.
     *
     * <p>The lookup is deliberately exact and case-sensitive. Tooling must not
     * reinterpret an unknown future profile as the current default.
     */
    public static CompilerTarget targetForProfileId(String profileId) {
        Objects.requireNonNull(profileId, "profileId");
        return SUPPORTED.stream()
                .filter(target -> target.profileId().equals(profileId))
                .findFirst()
                .orElseThrow(() -> unsupported(profileId));
    }

    /** Resolve an exact compiler-supported target against the canonical feature registry. */
    public static ResolvedCompilerTarget resolve(CompilerTarget requested) {
        Objects.requireNonNull(requested, "requested");
        if (!SUPPORTED.contains(requested)) {
            throw unsupported(requested);
        }

        var profile = ProtocolFeatureRegistry.resolve(requested.ledgerTarget());
        if (!profile.isUplcVersionAvailable(requested.uplcVersion())) {
            // The exact supported-target table should make this unreachable.
            // Retain the check as an invariant against registry drift.
            throw new IllegalStateException(
                    "Supported compiler target " + requested
                            + " selects unavailable UPLC " + requested.uplcVersion());
        }
        return new ResolvedCompilerTarget(requested, profile);
    }

    private static CompilerException unsupported(CompilerTarget requested) {
        return unsupported(requested.profileId());
    }

    private static CompilerException unsupported(String requestedProfileId) {
        var info = DiagnosticCodes.UNSUPPORTED_COMPILER_TARGET;
        var supported = SUPPORTED.stream()
                .map(CompilerTarget::profileId)
                .sorted()
                .collect(Collectors.joining(", "));
        var message = info.format(requestedProfileId, supported);
        var diagnostic = new CompilerDiagnostic(
                info.level(), message, "<configuration>", 0, 0,
                info.fix(), info.code());
        return new CompilerException(message, diagnostic);
    }
}
