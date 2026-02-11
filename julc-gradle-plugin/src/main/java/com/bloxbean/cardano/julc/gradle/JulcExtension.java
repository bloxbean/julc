package com.bloxbean.cardano.julc.gradle;

import org.gradle.api.Project;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.model.ObjectFactory;

/**
 * Configuration extension for the julc Gradle plugin.
 * <p>
 * Provides two configurable properties:
 * <ul>
 *   <li>{@code sourceDir} — directory containing validator Java source files (default: {@code src/main/plutus})</li>
 *   <li>{@code outputDir} — directory for compiled JSON output (default: {@code build/plutus})</li>
 * </ul>
 */
public class JulcExtension {

    private final DirectoryProperty sourceDir;
    private final DirectoryProperty outputDir;

    public JulcExtension(Project project) {
        ObjectFactory objects = project.getObjects();
        sourceDir = objects.directoryProperty()
                .convention(project.getLayout().getProjectDirectory().dir("src/main/plutus"));
        outputDir = objects.directoryProperty()
                .convention(project.getLayout().getBuildDirectory().dir("plutus"));
    }

    public DirectoryProperty getSourceDir() {
        return sourceDir;
    }

    public DirectoryProperty getOutputDir() {
        return outputDir;
    }
}
