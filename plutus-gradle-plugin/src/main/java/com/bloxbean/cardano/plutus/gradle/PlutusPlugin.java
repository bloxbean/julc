package com.bloxbean.cardano.plutus.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.plugins.JavaPlugin;

/**
 * Gradle plugin that compiles Plutus validators from Java source files to UPLC scripts.
 * <p>
 * Usage in build.gradle:
 * <pre>
 * plugins {
 *     id 'com.bloxbean.cardano.plutus'
 * }
 *
 * plutus {
 *     sourceDir = file('src/main/plutus')     // default
 *     outputDir = file("${buildDir}/plutus")   // default
 * }
 * </pre>
 */
public class PlutusPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        project.getPluginManager().apply(JavaPlugin.class);

        // 1. Create extension for configuration DSL
        PlutusExtension extension = project.getExtensions()
                .create("plutus", PlutusExtension.class, project);

        // 2. Register compilePlutus task
        project.getTasks().register("compilePlutus", CompilePlutusTask.class, task -> {
            task.setGroup("plutus");
            task.setDescription("Compile Plutus validators to UPLC scripts");
            task.getSourceDir().set(extension.getSourceDir());
            task.getOutputDir().set(extension.getOutputDir());
        });

        // 3. Wire into build lifecycle
        project.getTasks().named("build")
                .configure(t -> t.dependsOn("compilePlutus"));
    }
}
