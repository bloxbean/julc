package com.bloxbean.cardano.plutus.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.SourceSet;

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

        // 3. Register plutus source directory so IDEs recognise the .java files.
        //    We add it to the main source set's java srcDirs, then exclude it from
        //    compileJava so javac doesn't try to compile the validator sources.
        project.afterEvaluate(p -> {
            JavaPluginExtension javaExt = p.getExtensions().getByType(JavaPluginExtension.class);
            SourceSet mainSourceSet = javaExt.getSourceSets().getByName(SourceSet.MAIN_SOURCE_SET_NAME);
            java.io.File plutusSrcDir = extension.getSourceDir().get().getAsFile();
            mainSourceSet.getJava().srcDir(plutusSrcDir);

            // Exclude the plutus dir from javac compilation
            p.getTasks().named("compileJava", org.gradle.api.tasks.compile.JavaCompile.class,
                    task -> task.exclude(fileTreeElement -> {
                        java.io.File file = fileTreeElement.getFile();
                        return isUnderDirectory(file, plutusSrcDir);
                    }));
        });

        // 4. Wire into build lifecycle
        project.getTasks().named("build")
                .configure(t -> t.dependsOn("compilePlutus"));
    }

    private static boolean isUnderDirectory(java.io.File file, java.io.File dir) {
        java.io.File parent = file.getParentFile();
        while (parent != null) {
            if (parent.equals(dir)) {
                return true;
            }
            parent = parent.getParentFile();
        }
        return false;
    }
}
