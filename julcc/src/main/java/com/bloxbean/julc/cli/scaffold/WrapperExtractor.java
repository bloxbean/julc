package com.bloxbean.julc.cli.scaffold;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Extracts bundled Gradle/Maven wrapper resources into a project directory.
 */
public final class WrapperExtractor {

    private static final String WRAPPERS_BASE = "META-INF/julc/wrappers/";

    private WrapperExtractor() {}

    /**
     * Extracts Gradle wrapper files into the project root.
     * Creates gradle/wrapper/ directory with jar + properties, plus gradlew scripts.
     */
    public static void extractGradleWrapper(Path projectRoot) throws IOException {
        Path wrapperDir = projectRoot.resolve("gradle/wrapper");
        Files.createDirectories(wrapperDir);

        extractResource(WRAPPERS_BASE + "gradle/gradlew", projectRoot.resolve("gradlew"));
        extractResource(WRAPPERS_BASE + "gradle/gradlew.bat", projectRoot.resolve("gradlew.bat"));
        extractResource(WRAPPERS_BASE + "gradle/gradle-wrapper.jar", wrapperDir.resolve("gradle-wrapper.jar"));
        extractResource(WRAPPERS_BASE + "gradle/gradle-wrapper.properties", wrapperDir.resolve("gradle-wrapper.properties"));

        setExecutable(projectRoot.resolve("gradlew"));
    }

    /**
     * Extracts Maven wrapper files into the project root.
     * Creates .mvn/wrapper/ directory with jar + properties, plus mvnw scripts.
     */
    public static void extractMavenWrapper(Path projectRoot) throws IOException {
        Path wrapperDir = projectRoot.resolve(".mvn/wrapper");
        Files.createDirectories(wrapperDir);

        extractResource(WRAPPERS_BASE + "maven/mvnw", projectRoot.resolve("mvnw"));
        extractResource(WRAPPERS_BASE + "maven/mvnw.cmd", projectRoot.resolve("mvnw.cmd"));
        extractResource(WRAPPERS_BASE + "maven/maven-wrapper.jar", wrapperDir.resolve("maven-wrapper.jar"));
        extractResource(WRAPPERS_BASE + "maven/maven-wrapper.properties", wrapperDir.resolve("maven-wrapper.properties"));

        setExecutable(projectRoot.resolve("mvnw"));
    }

    private static void extractResource(String resourcePath, Path target) throws IOException {
        try (InputStream is = WrapperExtractor.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IOException("Bundled resource not found: " + resourcePath);
            }
            Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void setExecutable(Path file) {
        file.toFile().setExecutable(true, false);
    }
}
