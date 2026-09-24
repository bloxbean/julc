package org.julclang.compiler.backend;

import org.julclang.compiler.CompilerTarget;
import org.julclang.compiler.error.DiagnosticCodes;

import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * What this backend provides for one resolved compiler target (ADR-059).
 *
 * @param revision        the newest contract revision implemented
 * @param minimumRevision the oldest contract revision still accepted
 * @param target          the resolved compiler target the capabilities apply to
 * @param capabilities    the capabilities available for that target, sorted by identifier
 */
public record BackendCapabilities(
        int revision, int minimumRevision, CompilerTarget target,
        SortedSet<BackendCapability> capabilities) {
    public BackendCapabilities {
        Objects.requireNonNull(target, "target");
        capabilities = Collections.unmodifiableSortedSet(new TreeSet<>(capabilities));
    }

    public boolean provides(BackendCapability capability) {
        return capabilities.contains(capability);
    }

    /** Reject the first required capability this backend does not provide. */
    public void require(Collection<BackendCapability> required, String subject) {
        for (var capability : new TreeSet<>(required))
            if (!provides(capability))
                throw new BackendException(DiagnosticCodes.BACKEND_CAPABILITY_UNAVAILABLE,
                        subject, subject, capability, target.profileId());
    }

    /** Reject a descriptor or provider revision outside the supported range. */
    public void requireRevision(int requested, String subject) {
        requireRevision(requested, minimumRevision, subject);
    }

    /** Reject a revision outside {@code [minimum, revision]}, for descriptors added later. */
    public void requireRevision(int requested, int minimum, String subject) {
        int lowest = Math.max(minimum, minimumRevision);
        if (requested < lowest || requested > revision)
            throw new BackendException(DiagnosticCodes.BACKEND_UNSUPPORTED_REVISION,
                    subject, subject, requested, lowest + ".." + revision);
    }
}
