package org.julclang.gradle;

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

                import org.julclang.stdlib.annotation.OnchainLibrary;

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
    void bundleSkipsOffChainSourcesJuLCCannotReadAndKeepsEscapedLibraries(@TempDir Path tempDir) throws Exception {
        // ADR-062 T24: the off-chain sources compile with javac, but JuLC's strict reading rejects them. They name
        // no annotation, so they are skipped; a library whose annotation name is written with an escape is bundled.
        Path sourceDir = tempDir.resolve("src/main/java");
        Path pkg = sourceDir.resolve("com/example");
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("SurrogateRegex.java"), """
                package com.example;

                import java.util.regex.Pattern;

                public final class SurrogateRegex {
                    static final Pattern HIGH = Pattern.compile("[\\uD800-\\uDBFF]");
                }
                """);
        // Mentions the annotations in a Javadoc, so the text pre-filter makes it a candidate; it declares none.
        Files.writeString(pkg.resolve("Mentions.java"), """
                package com.example;

                import java.util.regex.Pattern;

                /** Helper for the @SpendingValidator and @OnchainLibrary code. */
                public final class Mentions {
                    static final Pattern HIGH = Pattern.compile("[\\uD800-\\uDBFF]");
                }
                """);
        Files.writeString(pkg.resolve("UnnamedVar.java"), """
                package com.example;

                import java.util.List;

                public final class UnnamedVar {
                    static String e = "caf\\u00e9";

                    static int count(List<String> l) {
                        int n = 0;
                        for (String _ : l) n++;
                        return n;
                    }
                }
                """);
        // No import: only the escaped annotation names the library.
        Files.writeString(pkg.resolve("Escaped.java"), """
                package com.example;

                @\\u004fnchainLibrary
                public final class Escaped {
                    private Escaped() {}
                }
                """);

        Path outputDir = tempDir.resolve("build/resources/main");
        bundleTask(tempDir, sourceDir, outputDir).bundle();

        assertEquals(List.of("com/example/Escaped.java"),
                Files.readAllLines(outputDir.resolve("META-INF/plutus-sources/index.txt")));
    }

    @Test
    void bundleFailsOnACandidateJuLCCannotRead(@TempDir Path tempDir) throws Exception {
        Path sourceDir = tempDir.resolve("src/main/java");
        Path pkg = sourceDir.resolve("com/example");
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("Bad.java"), """
                package com.example;

                @OnchainLibrary
                public final class Bad {
                    static String s = "\\uD800";
                }
                """);

        var task = bundleTask(tempDir, sourceDir, tempDir.resolve("build/resources/main"));
        var error = assertThrows(GradleException.class, task::bundle);
        assertTrue(error.getMessage().contains("Could not parse @OnchainLibrary candidate"), error.getMessage());
        assertTrue(error.getMessage().contains("unpaired surrogate U+D800"), error.getMessage());
    }

    private static BundleJulcSourcesTask bundleTask(Path projectDir, Path sourceDir, Path outputDir) {
        Project project = ProjectBuilder.builder().withProjectDir(projectDir.toFile()).build();
        BundleJulcSourcesTask task = project.getTasks().create("bundleJulcSources", BundleJulcSourcesTask.class);
        task.getSourceDir().set(sourceDir.toFile());
        task.getOutputDir().set(outputDir.toFile());
        return task;
    }

    @Test
    void bundleIgnoresOnchainLibraryTextInJavadoc(@TempDir Path tempDir) throws Exception {
        Path sourceDir = tempDir.resolve("src/main/java");
        Path validatorSource = sourceDir.resolve("com/example/MpfRegistryValidator.java");
        Files.createDirectories(validatorSource.getParent());
        Files.writeString(validatorSource, """
                package com.example;

                /**
                 * Uses helper @OnchainLibrary code.
                 */
                public final class MpfRegistryValidator {}
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

        assertFalse(Files.exists(outputDir.resolve("META-INF/plutus-sources/index.txt")));
    }

    @Test
    void bundleIgnoresOnchainLibraryTextInStringLiteral(@TempDir Path tempDir) throws Exception {
        Path sourceDir = tempDir.resolve("src/main/java");
        Path helperSource = sourceDir.resolve("com/example/Helper.java");
        Files.createDirectories(helperSource.getParent());
        Files.writeString(helperSource, """
                package com.example;

                public final class Helper {
                    static final String DOC = "@OnchainLibrary";
                }
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

        assertFalse(Files.exists(outputDir.resolve("META-INF/plutus-sources/index.txt")));
    }

    @Test
    void bundleReportsParseErrorForPrefilteredCandidate(@TempDir Path tempDir) throws Exception {
        Path sourceDir = tempDir.resolve("src/main/java");
        Path brokenSource = sourceDir.resolve("com/example/Broken.java");
        Files.createDirectories(brokenSource.getParent());
        Files.writeString(brokenSource, """
                // @OnchainLibrary
                class Broken {
                """);

        Project project = ProjectBuilder.builder()
                .withProjectDir(tempDir.toFile())
                .build();
        BundleJulcSourcesTask task = project.getTasks()
                .create("bundleJulcSources", BundleJulcSourcesTask.class);
        task.getSourceDir().set(sourceDir.toFile());
        task.getOutputDir().set(tempDir.resolve("build/resources/main").toFile());

        GradleException ex = assertThrows(GradleException.class, task::bundle);
        assertTrue(ex.getMessage().contains("Could not parse @OnchainLibrary candidate"));
        assertTrue(ex.getMessage().contains("Broken.java"));
    }

    @Test
    void bundleAllowsUnrelatedAnnotationsOnLibrary(@TempDir Path tempDir) throws Exception {
        Path sourceDir = tempDir.resolve("src/main/java");
        Path librarySource = sourceDir.resolve("com/example/DecoratedLib.java");
        Files.createDirectories(librarySource.getParent());
        Files.writeString(librarySource, """
                package com.example;

                import org.julclang.stdlib.annotation.OnchainLibrary;

                @Getter
                @OnchainLibrary
                public final class DecoratedLib {}
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

        assertEquals(List.of("com/example/DecoratedLib.java"),
                Files.readAllLines(outputDir.resolve("META-INF/plutus-sources/index.txt")));
    }

    @Test
    void bundleRejectsOnchainLibraryWithValidatorAnnotation(@TempDir Path tempDir) throws Exception {
        Path sourceDir = tempDir.resolve("src/main/java");
        Path librarySource = sourceDir.resolve("com/example/Confused.java");
        Files.createDirectories(librarySource.getParent());
        Files.writeString(librarySource, """
                package com.example;

                import org.julclang.stdlib.annotation.OnchainLibrary;

                @OnchainLibrary
                @SpendingValidator
                public final class Confused {}
                """);

        Project project = ProjectBuilder.builder()
                .withProjectDir(tempDir.toFile())
                .build();
        BundleJulcSourcesTask task = project.getTasks()
                .create("bundleJulcSources", BundleJulcSourcesTask.class);
        task.getSourceDir().set(sourceDir.toFile());
        task.getOutputDir().set(tempDir.resolve("build/resources/main").toFile());

        GradleException ex = assertThrows(GradleException.class, task::bundle);
        assertTrue(ex.getMessage().contains("must not combine @OnchainLibrary"));
        assertTrue(ex.getMessage().contains("@SpendingValidator"));
    }

    @Test
    void bundleFailsWhenDeclaredPackageDoesNotMatchPath(@TempDir Path tempDir) throws Exception {
        Path sourceDir = tempDir.resolve("src/main/java");
        Path librarySource = sourceDir.resolve("com/wrong/Groth16BLS12381.java");
        Files.createDirectories(librarySource.getParent());
        Files.writeString(librarySource, """
                package com.example;

                import org.julclang.stdlib.annotation.OnchainLibrary;

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

                import org.julclang.stdlib.annotation.OnchainLibrary;

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

                import org.julclang.stdlib.annotation.OnchainLibrary;

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
        assertTrue(ex.getMessage().contains("Nested on-chain library Inner is not supported"));
    }
}
