package com.bloxbean.cardano.julc.gradle;

import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BundleJulcSourcesTaskTest {

    @Test
    void bundleCopiesOnchainLibrarySourcesAndWritesIndex(@TempDir Path tempDir) throws Exception {
        Path sourceDir = tempDir.resolve("src/main/java");
        Path librarySource = sourceDir.resolve("com/example/Groth16BLS12381.java");
        Files.createDirectories(librarySource.getParent());
        Files.writeString(librarySource, """
                package com.example;

                import com.bloxbean.cardano.julc.stdlib.annotation.OnchainLibrary;

                @OnchainLibrary
                public final class Groth16BLS12381 {
                    private Groth16BLS12381() {}
                }
                """);

        Path regularSource = sourceDir.resolve("com/example/OffchainHelper.java");
        Files.writeString(regularSource, """
                package com.example;

                public final class OffchainHelper {}
                """);

        Project project = ProjectBuilder.builder()
                .withProjectDir(tempDir.toFile())
                .build();
        BundleJulcSourcesTask task = project.getTasks()
                .create("bundleJulcSources", BundleJulcSourcesTask.class);
        Path outputDir = tempDir.resolve("build/resources/main");
        task.getSourceDir().set(sourceDir.toFile());
        task.getOutputDir().set(outputDir.toFile());

        task.bundle();

        Path plutusSourcesDir = outputDir.resolve("META-INF/plutus-sources");
        Path copiedSource = plutusSourcesDir.resolve("com/example/Groth16BLS12381.java");
        assertTrue(Files.exists(copiedSource));
        assertEquals(Files.readString(librarySource), Files.readString(copiedSource));
        assertFalse(Files.exists(plutusSourcesDir.resolve("com/example/OffchainHelper.java")));
        assertEquals(List.of("com/example/Groth16BLS12381.java"),
                Files.readAllLines(plutusSourcesDir.resolve("index.txt")));
    }
}
