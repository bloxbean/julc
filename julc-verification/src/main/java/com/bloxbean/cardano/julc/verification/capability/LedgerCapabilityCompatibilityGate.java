package com.bloxbean.cardano.julc.verification.capability;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Fail-closed compatibility checks for a candidate CardanoLedgerApi checkout. */
public final class LedgerCapabilityCompatibilityGate {
    private LedgerCapabilityCompatibilityGate() {
    }

    public static void requireRevision(
            LedgerCapabilityInventory inventory, String observedRevision) {
        if (!inventory.revision().equals(observedRevision)) {
            throw new IllegalArgumentException("CardanoLedgerApi revision " + observedRevision
                    + " is not classified; expected " + inventory.revision());
        }
    }

    public static void verifyLeanSources(
            LedgerCapabilityInventory inventory, Path v3SourceDirectory) throws IOException {
        Path root = v3SourceDirectory.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Not a CardanoLedgerApi V3 source directory: " + root);
        }
        List<String> mismatches = new ArrayList<>();
        for (LedgerCapability capability : inventory.capabilities()) {
            if (capability.source().isEmpty()) {
                continue;
            }
            Path source = root.resolve(capability.source()).normalize();
            if (!source.startsWith(root) || !Files.isRegularFile(source)) {
                mismatches.add(capability.id() + " missing source " + capability.source());
                continue;
            }
            String observed = normalize(Files.readString(source));
            if (!observed.contains(normalize(capability.signature()))) {
                mismatches.add(capability.id() + " changed or missing signature '"
                        + capability.signature() + "'");
            }
        }
        if (!mismatches.isEmpty()) {
            throw new IllegalStateException("Unclassified CardanoLedgerApi V3 surface changes:\n- "
                    + String.join("\n- ", mismatches));
        }
    }

    private static String normalize(String value) {
        return value.replaceAll("--[^\\r\\n]*", " ")
                .replaceAll("\\s+", " ")
                .strip();
    }
}
