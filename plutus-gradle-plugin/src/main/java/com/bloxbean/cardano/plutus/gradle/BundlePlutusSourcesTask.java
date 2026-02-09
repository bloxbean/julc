package com.bloxbean.cardano.plutus.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.tasks.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Gradle task that bundles {@code @OnchainLibrary}-annotated Java source files
 * into {@code META-INF/plutus-sources/} in the resources output directory.
 * <p>
 * The bundled sources preserve their package directory structure so that consuming
 * projects can discover them on the classpath during annotation processing.
 * <p>
 * For example:
 * <pre>
 * src/main/java/com/example/MathUtils.java
 *   → build/resources/main/META-INF/plutus-sources/com/example/MathUtils.java
 * </pre>
 */
public abstract class BundlePlutusSourcesTask extends DefaultTask {

    @InputDirectory
    @SkipWhenEmpty
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getSourceDir();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDir();

    @TaskAction
    public void bundle() throws IOException {
        File srcDir = getSourceDir().get().getAsFile();
        File outDir = getOutputDir().get().getAsFile();

        Path metaInfDir = outDir.toPath().resolve("META-INF/plutus-sources");

        List<File> javaFiles = findJavaFiles(srcDir);
        int bundled = 0;

        for (File javaFile : javaFiles) {
            String source = Files.readString(javaFile.toPath());
            if (!source.contains("@OnchainLibrary")) {
                continue;
            }

            // Compute relative path to preserve package structure
            Path relativePath = srcDir.toPath().relativize(javaFile.toPath());
            Path targetFile = metaInfDir.resolve(relativePath);

            Files.createDirectories(targetFile.getParent());
            Files.writeString(targetFile, source);

            getLogger().lifecycle("Bundled {} → META-INF/plutus-sources/{}",
                    javaFile.getName(), relativePath);
            bundled++;
        }

        if (bundled == 0) {
            getLogger().lifecycle("No @OnchainLibrary sources found to bundle in {}", srcDir);
        }
    }

    private List<File> findJavaFiles(File dir) {
        List<File> files = new ArrayList<>();
        if (!dir.exists()) return files;
        collectJavaFiles(dir, files);
        return files;
    }

    private void collectJavaFiles(File dir, List<File> result) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) {
                collectJavaFiles(child, result);
            } else if (child.getName().endsWith(".java")) {
                result.add(child);
            }
        }
    }
}
