package com.bloxbean.cardano.julc.blueprint;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/** Safely publishes a complete blueprint without exposing a partially written file. */
public final class BlueprintFileWriter {

    private BlueprintFileWriter() {}

    public static void writeAtomically(Path target, String json) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        if (parent == null) throw new IOException("Blueprint path has no parent: " + target);
        Files.createDirectories(parent);
        Set<PosixFilePermission> existingPermissions = readPosixPermissions(target);
        Path temporary = createReadableTempFile(parent);
        try {
            Files.writeString(temporary, json);
            if (existingPermissions != null) {
                Files.setPosixFilePermissions(temporary, existingPermissions);
            }
            try {
                Files.move(temporary, target,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Path createReadableTempFile(Path parent) throws IOException {
        try {
            // Explicit 0666 is filtered by the process umask, matching a normal
            // newly-created artifact instead of createTempFile's POSIX 0600 default.
            return Files.createTempFile(parent, "plutus-", ".json.tmp",
                    PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString("rw-rw-rw-")));
        } catch (UnsupportedOperationException e) {
            return Files.createTempFile(parent, "plutus-", ".json.tmp");
        }
    }

    private static Set<PosixFilePermission> readPosixPermissions(Path target)
            throws IOException {
        if (!Files.exists(target)) return null;
        try {
            return Files.getPosixFilePermissions(target);
        } catch (UnsupportedOperationException e) {
            return null;
        }
    }
}
