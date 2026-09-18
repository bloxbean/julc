package org.julclang.compiler;

import java.io.IOException;
import java.util.Properties;

/** Build identity used by compilation provenance; raw IDE resources identify a dev build. */
final class CompilerVersion {
    static final String VERSION = load();

    private CompilerVersion() {}

    private static String load() {
        try (var input = CompilerVersion.class.getResourceAsStream("version.properties")) {
            if (input == null) {
                return "dev";
            }
            var properties = new Properties();
            properties.load(input);
            var version = properties.getProperty("version");
            if (version == null || version.isBlank() || version.equals("@julcVersion@")) {
                return "dev";
            }
            return version;
        } catch (IOException | IllegalArgumentException e) {
            // Version metadata must not prevent compilation. Properties.load can also
            // reject malformed Unicode escapes in an IDE-supplied resource.
            return "dev";
        }
    }
}
