package org.julclang.compiler.backend;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Identifies one specialization for invocation-local deduplication (ADR-059, #181). Two
 * requests share an implementation only when every input that can change the generated PIR
 * is equal: library content and compiler version, export identity, nominal type arguments
 * (so newtypes with equal representations stay distinct), representation and API revisions,
 * and the target profile.
 */
public record SpecializationKey(
        String contentHash,
        String exportIdentity,
        List<LibraryType.Reference> typeArguments,
        int representationRevision,
        int apiRevision,
        String targetProfile) {
    public SpecializationKey {
        Objects.requireNonNull(contentHash, "contentHash");
        Objects.requireNonNull(exportIdentity, "exportIdentity");
        typeArguments = List.copyOf(typeArguments);
        Objects.requireNonNull(targetProfile, "targetProfile");
    }

    /** A stable hexadecimal digest of the key, used in binding names. */
    public String digest() {
        String canonical = String.join("\n", contentHash, exportIdentity,
                String.join(",", typeArguments.stream().map(LibraryType.Reference::canonical).toList()),
                Integer.toString(representationRevision), Integer.toString(apiRevision), targetProfile);
        try {
            var hash = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
