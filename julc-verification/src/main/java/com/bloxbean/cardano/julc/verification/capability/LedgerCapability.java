package com.bloxbean.cardano.julc.verification.capability;

import java.util.Objects;

/** One auditable entry in a pinned CardanoLedgerApi capability inventory. */
public record LedgerCapability(
        String id,
        CapabilityKind kind,
        CapabilityStatus status,
        String source,
        String signature,
        String note) {

    public LedgerCapability {
        id = requireText(id, "id");
        kind = Objects.requireNonNull(kind, "kind");
        status = Objects.requireNonNull(status, "status");
        source = source == null ? "" : source.strip();
        signature = signature == null ? "" : signature.strip();
        note = requireText(note, "note");
        if (source.isEmpty() != signature.isEmpty()) {
            throw new IllegalArgumentException(
                    "Capability source and signature must either both be present or both absent: " + id);
        }
        if (!source.isEmpty() && (source.startsWith("/") || source.contains(".."))) {
            throw new IllegalArgumentException("Unsafe capability source path: " + source);
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Capability " + name + " must not be blank");
        }
        return value.strip();
    }
}
