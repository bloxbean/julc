package com.bloxbean.cardano.julc.gradle;

import com.bloxbean.cardano.julc.compiler.LibrarySourceResolver;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.tasks.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
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
public abstract class BundleJulcSourcesTask extends DefaultTask {

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

        List<File> javaFiles = SourceScanner.findJavaFiles(srcDir);
        List<String> bundledEntries = new ArrayList<>();

        for (File javaFile : javaFiles) {
            String source = Files.readString(javaFile.toPath());
            if (!source.contains("@OnchainLibrary")) {
                continue;
            }

            // Compute relative path to preserve package structure
            Path relativePath = srcDir.toPath().relativize(javaFile.toPath());
            Path targetFile = metaInfDir.resolve(relativePath);
            String indexEntry = relativePath.toString().replace(File.separatorChar, '/');
            validatePackageMatchesPath(javaFile, source, indexEntry);

            Files.createDirectories(targetFile.getParent());
            Files.writeString(targetFile, source, StandardCharsets.UTF_8);
            bundledEntries.add(indexEntry);

            getLogger().lifecycle("Bundled {} → META-INF/plutus-sources/{}",
                    javaFile.getName(), relativePath);
        }

        if (bundledEntries.isEmpty()) {
            getLogger().lifecycle("No @OnchainLibrary sources found to bundle in {}", srcDir);
        } else {
            Collections.sort(bundledEntries);
            Files.createDirectories(metaInfDir);
            Files.writeString(metaInfDir.resolve("index.txt"),
                    String.join("\n", bundledEntries) + "\n",
                    StandardCharsets.UTF_8);
        }
    }

    private static void validatePackageMatchesPath(File javaFile, String source, String indexEntry) {
        String packageName = LibrarySourceResolver.extractPackageName(source);
        String fileClassName = javaFile.getName().replace(".java", "");
        String sourceClassName = LibrarySourceResolver.extractTopLevelTypeName(source)
                .orElseThrow(() -> new GradleException("Could not determine @OnchainLibrary class name in " + javaFile));
        if (!LibrarySourceResolver.hasTopLevelOnchainLibraryAnnotation(source, sourceClassName)) {
            throw new GradleException("@OnchainLibrary must be declared on the top-level class in " + javaFile
                    + ". Nested on-chain libraries are not supported.");
        }
        if (!fileClassName.equals(sourceClassName)) {
            throw new GradleException("@OnchainLibrary class/path mismatch for " + javaFile
                    + ": source declares " + sourceClassName
                    + " but source path is " + indexEntry);
        }

        String expectedEntry = packageName.isBlank()
                ? javaFile.getName()
                : packageName.replace('.', '/') + "/" + javaFile.getName();
        if (!expectedEntry.equals(indexEntry)) {
            throw new GradleException("@OnchainLibrary package/path mismatch for " + javaFile
                    + ": declared package expects " + expectedEntry
                    + " but source path is " + indexEntry);
        }
    }

}
