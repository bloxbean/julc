package org.julclang.compiler;

import java.io.IOException;
import java.util.Properties;

/** Build identity used by compilation provenance. */
final class CompilerVersion {
    static final String VERSION = load();

    private CompilerVersion() {}

    private static String load() {
        try (var input = CompilerVersion.class.getResourceAsStream("version.properties")) {
            if (input == null) {
                throw new IllegalStateException("Missing compiler version resource");
            }
            var properties = new Properties();
            properties.load(input);
            var version = properties.getProperty("version");
            if (version == null || version.isBlank() || version.equals("@julcVersion@")) {
                throw new IllegalStateException("Missing compiler build version");
            }
            return version;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read compiler build version", e);
        }
    }
}
