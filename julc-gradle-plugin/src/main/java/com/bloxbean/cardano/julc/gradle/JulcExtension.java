package com.bloxbean.cardano.julc.gradle;

import org.gradle.api.Project;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;

/**
 * Configuration extension for the julc Gradle plugin.
 * <p>
 * Configurable properties:
 * <ul>
 *   <li>{@code sourceDir} — directory containing validator Java source files (default: {@code src/main/plutus})</li>
 *   <li>{@code outputDir} — directory for compiled JSON output (default: {@code build/plutus})</li>
 *   <li>{@code sourceMap} — enable source map generation for debugging (default: {@code false})</li>
 *   <li>{@code blueprint} — generate strict CIP-57 metadata (default: {@code true})</li>
 *   <li>{@code target} — exact compiler target profile ID (default: PV11)</li>
 * </ul>
 * <p>
 * Example:
 * <pre>{@code
 * julc {
 *     sourceMap = true  // enable source maps for error location reporting
 * }
 * }</pre>
 */
public class JulcExtension {

    private final DirectoryProperty sourceDir;
    private final DirectoryProperty outputDir;
    private final Property<Boolean> sourceMap;
    private final Property<Boolean> blueprint;
    private final Property<String> target;
    private final Property<String> optimization;
    private final Property<String> costProfile;

    public JulcExtension(Project project) {
        ObjectFactory objects = project.getObjects();
        sourceDir = objects.directoryProperty()
                .convention(project.getLayout().getProjectDirectory().dir("src/main/plutus"));
        outputDir = objects.directoryProperty()
                .convention(project.getLayout().getBuildDirectory().dir("plutus"));
        sourceMap = objects.property(Boolean.class).convention(false);
        blueprint = objects.property(Boolean.class).convention(true);
        target = objects.property(String.class)
                .convention("plutus-v3-pv11-uplc-1.1.0");
        optimization = objects.property(String.class).convention("baseline");
        costProfile = objects.property(String.class);
    }

    public DirectoryProperty getSourceDir() {
        return sourceDir;
    }

    public DirectoryProperty getOutputDir() {
        return outputDir;
    }

    /**
     * Whether to generate source maps during compilation.
     * When enabled, UPLC terms can be mapped back to Java source lines for error reporting.
     * Optimization is skipped when source maps are enabled.
     */
    public Property<Boolean> getSourceMap() {
        return sourceMap;
    }

    /** Whether compilation must also produce a complete CIP-57 blueprint. */
    public Property<Boolean> getBlueprint() {
        return blueprint;
    }

    /** Exact, fail-closed compiler target profile ID. */
    public Property<String> getTarget() {
        return target;
    }

    /** Exact optimizer rollout ID. Defaults to byte-compatible {@code baseline}. */
    public Property<String> getOptimization() {
        return optimization;
    }

    /** Exact pinned cost-profile ID. Required only by cost-directed levels. */
    public Property<String> getCostProfile() {
        return costProfile;
    }
}
