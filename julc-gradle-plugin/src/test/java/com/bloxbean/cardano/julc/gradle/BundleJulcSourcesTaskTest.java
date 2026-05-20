package com.bloxbean.cardano.julc.gradle;

import org.gradle.api.Project;
import org.gradle.api.GradleException;
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

    @Test
    void bundleFailsWhenDeclaredPackageDoesNotMatchPath(@TempDir Path tempDir) throws Exception {
        Path sourceDir = tempDir.resolve("src/main/java");
        Path librarySource = sourceDir.resolve("com/wrong/Groth16BLS12381.java");
        Files.createDirectories(librarySource.getParent());
        Files.writeString(librarySource, """
                package com.example;

                import com.bloxbean.cardano.julc.stdlib.annotation.OnchainLibrary;

                @OnchainLibrary
                public final class Groth16BLS12381 {
                    private Groth16BLS12381() {}
                }
                """);

        Project project = ProjectBuilder.builder()
                .withProjectDir(tempDir.toFile())
                .build();
        BundleJulcSourcesTask task = project.getTasks()
                .create("bundleJulcSources", BundleJulcSourcesTask.class);
        task.getSourceDir().set(sourceDir.toFile());
        task.getOutputDir().set(tempDir.resolve("build/resources/main").toFile());

        GradleException ex = assertThrows(GradleException.class, task::bundle);
        assertTrue(ex.getMessage().contains("package/path mismatch"));
        assertTrue(ex.getMessage().contains("com/example/Groth16BLS12381.java"));
    }

    @Test
    void bundleFailsWhenDeclaredClassDoesNotMatchPath(@TempDir Path tempDir) throws Exception {
        Path sourceDir = tempDir.resolve("src/main/java");
        Path librarySource = sourceDir.resolve("com/example/Groth16BLS12381.java");
        Files.createDirectories(librarySource.getParent());
        Files.writeString(librarySource, """
                package com.example;

                import com.bloxbean.cardano.julc.stdlib.annotation.OnchainLibrary;

                @OnchainLibrary
                final class DifferentName {
                    private DifferentName() {}
                }
                """);

        Project project = ProjectBuilder.builder()
                .withProjectDir(tempDir.toFile())
                .build();
        BundleJulcSourcesTask task = project.getTasks()
                .create("bundleJulcSources", BundleJulcSourcesTask.class);
        task.getSourceDir().set(sourceDir.toFile());
        task.getOutputDir().set(tempDir.resolve("build/resources/main").toFile());

        GradleException ex = assertThrows(GradleException.class, task::bundle);
        assertTrue(ex.getMessage().contains("class/path mismatch"));
        assertTrue(ex.getMessage().contains("DifferentName"));
    }

    @Test
    void bundleFailsWhenOnchainLibraryIsNested(@TempDir Path tempDir) throws Exception {
        Path sourceDir = tempDir.resolve("src/main/java");
        Path librarySource = sourceDir.resolve("com/example/Outer.java");
        Files.createDirectories(librarySource.getParent());
        Files.writeString(librarySource, """
                package com.example;

                import com.bloxbean.cardano.julc.stdlib.annotation.OnchainLibrary;

                public final class Outer {
                    @OnchainLibrary
                    static final class Inner {
                        static boolean check() { return true; }
                    }
                }
                """);

        Project project = ProjectBuilder.builder()
                .withProjectDir(tempDir.toFile())
                .build();
        BundleJulcSourcesTask task = project.getTasks()
                .create("bundleJulcSources", BundleJulcSourcesTask.class);
        task.getSourceDir().set(sourceDir.toFile());
        task.getOutputDir().set(tempDir.resolve("build/resources/main").toFile());

        GradleException ex = assertThrows(GradleException.class, task::bundle);
        assertTrue(ex.getMessage().contains("top-level class"));
        assertTrue(ex.getMessage().contains("Nested on-chain libraries are not supported"));
    }
}
