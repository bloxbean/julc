package com.bloxbean.cardano.julc.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.SourceSet;

/**
 * Gradle plugin that compiles julc validators from Java source files to UPLC scripts.
 * <p>
 * Usage in build.gradle:
 * <pre>
 * plugins {
 *     id 'com.bloxbean.cardano.julc'
 * }
 *
 * julc {
 *     sourceDir = file('src/main/plutus')     // default
 *     outputDir = file("${buildDir}/plutus")   // default
 * }
 * </pre>
 * <p>
 * The plugin also registers a {@code bundleJulcSources} task that copies
 * {@code @OnchainLibrary}-annotated source files into {@code META-INF/plutus-sources/}
 * in the resources output, so they are included in the published JAR for downstream
 * consumption by annotation processors.
 */
public class JulcPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        project.getPluginManager().apply(JavaPlugin.class);

        // 1. Create extension for configuration DSL
        JulcExtension extension = project.getExtensions()
                .create("julc", JulcExtension.class, project);

        // 2. Register compileJulc task
        project.getTasks().register("compileJulc", CompileJulcTask.class, task -> {
            task.setGroup("julc");
            task.setDescription("Compile julc validators to UPLC scripts");
            task.getSourceDir().set(extension.getSourceDir());
            task.getOutputDir().set(extension.getOutputDir());
            task.getSourceMap().set(extension.getSourceMap());
        });

        // 3. Register bundleJulcSources task
        project.getTasks().register("bundleJulcSources", BundleJulcSourcesTask.class, task -> {
            task.setGroup("julc");
            task.setDescription("Bundle @OnchainLibrary sources into META-INF/plutus-sources/");
        });

        // 4. Register plutus source directory so IDEs recognise the .java files.
        //    We add it to the main source set's java srcDirs, then exclude it from
        //    compileJava so javac doesn't try to compile the validator sources.
        project.afterEvaluate(p -> {
            JavaPluginExtension javaExt = p.getExtensions().getByType(JavaPluginExtension.class);
            SourceSet mainSourceSet = javaExt.getSourceSets().getByName(SourceSet.MAIN_SOURCE_SET_NAME);
            java.io.File plutusSrcDir = extension.getSourceDir().get().getAsFile();
            mainSourceSet.getJava().srcDir(plutusSrcDir);

            // Exclude the plutus dir from javac compilation + propagate sourceMap setting
            p.getTasks().named("compileJava", org.gradle.api.tasks.compile.JavaCompile.class,
                    task -> {
                        task.exclude(fileTreeElement -> {
                            java.io.File file = fileTreeElement.getFile();
                            return isUnderDirectory(file, plutusSrcDir);
                        });
                        if (Boolean.TRUE.equals(extension.getSourceMap().getOrElse(false))) {
                            task.getOptions().getCompilerArgs().add("-Ajulc.sourceMap=true");
                        }
                    });

            // Configure bundleJulcSources: source from main java srcDirs, output to resources
            p.getTasks().named("bundleJulcSources", BundleJulcSourcesTask.class, task -> {
                // Default: scan src/main/java for @OnchainLibrary files
                task.getSourceDir().convention(
                        p.getLayout().getProjectDirectory().dir("src/main/java"));
                task.getOutputDir().convention(
                        p.getLayout().getBuildDirectory().dir("resources/main"));
            });

            // Wire bundleJulcSources to run before jar
            p.getTasks().named("jar").configure(t -> t.dependsOn("bundleJulcSources"));
        });

        // 5. Wire compileJulc into build lifecycle
        project.getTasks().named("build")
                .configure(t -> t.dependsOn("compileJulc"));
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
