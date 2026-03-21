package com.bloxbean.julc.cli.project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Model for .julc/lock.toml — tracks installed stdlib version and hash.
 */
public record LockToml(String version, String sha256) {

    public String toToml() {
        return "[stdlib]\n" +
                "version = \"" + version + "\"\n" +
                "sha256 = \"" + sha256 + "\"\n";
    }

    public static LockToml parse(Path lockFile) throws IOException {
        var sections = TomlParser.parseToMap(Files.readString(lockFile));
        var stdlib = sections.getOrDefault("stdlib", java.util.Map.of());
        return new LockToml(
                stdlib.getOrDefault("version", ""),
                stdlib.getOrDefault("sha256", "")
        );
    }
}
