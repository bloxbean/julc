package com.bloxbean.julc.cli.cmd.verify;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class ExecutableLocator {

    private ExecutableLocator() {
    }

    static Optional<Path> find(String name, Map<String, String> environment) {
        var directories = new ArrayList<String>();
        String path = environment.getOrDefault("PATH", System.getenv().getOrDefault("PATH", ""));
        if (!path.isBlank()) directories.addAll(List.of(path.split(java.io.File.pathSeparator)));
        Path elan = Path.of(System.getProperty("user.home"), ".elan", "bin");
        if (!directories.contains(elan.toString())) directories.addFirst(elan.toString());
        for (String directory : directories) {
            if (directory.isBlank()) continue;
            Path candidate = Path.of(directory).resolve(executableName(name));
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return Optional.of(candidate.toAbsolutePath().normalize());
            }
        }
        return Optional.empty();
    }

    private static String executableName(String name) {
        return System.getProperty("os.name", "").toLowerCase().contains("win")
                ? name + ".exe" : name;
    }
}
