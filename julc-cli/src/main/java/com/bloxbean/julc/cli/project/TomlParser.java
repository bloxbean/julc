package com.bloxbean.julc.cli.project;

import com.bloxbean.julc.cli.JulcVersionProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal TOML parser for julc.toml files.
 * Supports [sections] and key = "value" pairs.
 */
public final class TomlParser {

    private TomlParser() {}

    public static JulcToml parse(Path tomlFile) throws IOException {
        var sections = parseToMap(Files.readString(tomlFile));
        var project = sections.getOrDefault("project", Map.of());
        return new JulcToml(
                project.getOrDefault("name", "unnamed"),
                project.getOrDefault("version", JulcVersionProvider.VERSION),
                project.getOrDefault("compiler", JulcVersionProvider.VERSION)
        );
    }

    static Map<String, Map<String, String>> parseToMap(String content) {
        var result = new LinkedHashMap<String, Map<String, String>>();
        String currentSection = "";
        for (String line : content.split("\n")) {
            line = line.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("[") && line.endsWith("]")) {
                currentSection = line.substring(1, line.length() - 1).strip();
                result.putIfAbsent(currentSection, new LinkedHashMap<>());
            } else if (line.contains("=")) {
                int eq = line.indexOf('=');
                String key = line.substring(0, eq).strip();
                String value = line.substring(eq + 1).strip();
                // Remove surrounding quotes
                if (value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                result.computeIfAbsent(currentSection, k -> new LinkedHashMap<>()).put(key, value);
            }
        }
        return result;
    }
}
