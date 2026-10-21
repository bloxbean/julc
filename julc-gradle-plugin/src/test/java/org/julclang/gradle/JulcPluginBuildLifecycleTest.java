package org.julclang.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end tests that run the full {@code gradle build} lifecycle of a consumer
 * project, the way users run it.
 * <p>
 * Unlike {@link JulcPluginTest}, the fixture does not create {@code src/main/plutus}:
 * most projects compile validators from {@code src/main/java} with the annotation
 * processor and never have that directory (issue #216).
 */
class JulcPluginBuildLifecycleTest {

    @TempDir
    Path testProjectDir;
    private Path buildFile;
    private Path plutusSrcDir;

    @BeforeEach
    void setUp() throws IOException {
        buildFile = testProjectDir.resolve("build.gradle");
        plutusSrcDir = testProjectDir.resolve("src/main/plutus");
        Files.writeString(testProjectDir.resolve("settings.gradle"),
                "rootProject.name = 'lifecycle-project'\n");
        writeBuildFile("");

        Path javaSource = testProjectDir.resolve("src/main/java/demo/Hello.java");
        Files.createDirectories(javaSource.getParent());
        Files.writeString(javaSource, """
                package demo;

                public class Hello {}
                """);
    }

    @Test
    void buildSucceedsWithoutPlutusSourceDirectory() {
        BuildResult result = createRunner("build").build();

        assertEquals(TaskOutcome.NO_SOURCE, result.task(":compileJulc").getOutcome());
        assertEquals(TaskOutcome.SUCCESS, result.task(":jar").getOutcome());
        assertFalse(Files.exists(testProjectDir.resolve("build/plutus")));
    }

    @Test
    void buildWithoutPlutusSourceDirectorySupportsConfigurationCache() {
        BuildResult first = createRunner("build", "--configuration-cache").build();
        assertEquals(TaskOutcome.NO_SOURCE, first.task(":compileJulc").getOutcome());

        BuildResult second = createRunner("build", "--configuration-cache").build();
        assertTrue(second.getOutput().contains("Reusing configuration cache."));
        assertEquals(TaskOutcome.NO_SOURCE, second.task(":compileJulc").getOutcome());
    }

    @Test
    void buildSucceedsWithEmptyPlutusSourceDirectory() throws IOException {
        Files.createDirectories(plutusSrcDir);

        BuildResult result = createRunner("build").build();

        assertEquals(TaskOutcome.NO_SOURCE, result.task(":compileJulc").getOutcome());
        assertFalse(Files.exists(testProjectDir.resolve("build/plutus")));
    }

    @Test
    void buildSucceedsWithMissingCustomSourceDirectory() throws IOException {
        writeBuildFile("""
                julc {
                    sourceDir = file('validators')
                }
                """);

        BuildResult result = createRunner("build").build();

        assertEquals(TaskOutcome.NO_SOURCE, result.task(":compileJulc").getOutcome());
    }

    @Test
    void buildCompilesValidatorsInPlutusSourceDirectory() throws IOException {
        writeAlwaysTrueValidator();

        BuildResult result = createRunner("build").build();

        assertEquals(TaskOutcome.SUCCESS, result.task(":compileJulc").getOutcome());
        assertTrue(Files.exists(testProjectDir.resolve("build/plutus/AlwaysTrue.json")));
        String blueprint = Files.readString(testProjectDir.resolve("build/plutus/plutus.json"));
        assertTrue(blueprint.contains("\"title\": \"lifecycle-project\""));
        assertTrue(blueprint.contains("\"version\": \"1.0\""));
    }

    @Test
    void deletingPlutusSourceDirectoryRemovesStaleOutputs() throws IOException {
        writeBuildFile("""
                julc {
                    sourceMap = true
                }
                """);
        writeAlwaysTrueValidator();
        createRunner("build").build();
        Path validatorJson = testProjectDir.resolve("build/plutus/AlwaysTrue.json");
        Path sourceMapJson = testProjectDir.resolve("build/plutus/AlwaysTrue.sourcemap.json");
        Path blueprintJson = testProjectDir.resolve("build/plutus/plutus.json");
        assertTrue(Files.exists(validatorJson));
        assertTrue(Files.exists(sourceMapJson));
        assertTrue(Files.exists(blueprintJson));

        deleteRecursively(plutusSrcDir);
        BuildResult result = createRunner("build").build();

        assertEquals(TaskOutcome.SUCCESS, result.task(":compileJulc").getOutcome());
        assertFalse(Files.exists(validatorJson));
        assertFalse(Files.exists(sourceMapJson));
        assertFalse(Files.exists(blueprintJson));
    }

    @Test
    void buildWithValidatorsSupportsConfigurationCache() throws IOException {
        writeAlwaysTrueValidator();

        BuildResult first = createRunner("build", "--configuration-cache").build();
        assertEquals(TaskOutcome.SUCCESS, first.task(":compileJulc").getOutcome());

        Files.delete(testProjectDir.resolve("build/plutus/plutus.json"));
        BuildResult second = createRunner("build", "--configuration-cache").build();
        assertTrue(second.getOutput().contains("Reusing configuration cache."));
        assertEquals(TaskOutcome.SUCCESS, second.task(":compileJulc").getOutcome());
        String blueprint = Files.readString(testProjectDir.resolve("build/plutus/plutus.json"));
        assertTrue(blueprint.contains("\"title\": \"lifecycle-project\""));
        assertTrue(blueprint.contains("\"version\": \"1.0\""));
    }

    @Test
    void buildWithValidatorsHasNoDeprecationWarnings() throws IOException {
        writeAlwaysTrueValidator();

        BuildResult result = createRunner("build", "--warning-mode", "fail").build();

        assertEquals(TaskOutcome.SUCCESS, result.task(":compileJulc").getOutcome());
    }

    @Test
    void projectVersionChangeRegeneratesBlueprint() throws IOException {
        writeAlwaysTrueValidator();
        createRunner("build").build();
        Path blueprintJson = testProjectDir.resolve("build/plutus/plutus.json");
        assertTrue(Files.readString(blueprintJson).contains("\"version\": \"1.0\""));

        Files.writeString(buildFile, Files.readString(buildFile)
                .replace("version = '1.0'", "version = '2.0'"));
        BuildResult result = createRunner("build").build();

        assertEquals(TaskOutcome.SUCCESS, result.task(":compileJulc").getOutcome());
        assertTrue(Files.readString(blueprintJson).contains("\"version\": \"2.0\""));
    }

    private void writeBuildFile(String julcBlock) throws IOException {
        Files.writeString(buildFile, """
                plugins {
                    id 'java'
                    id 'org.julclang.julc'
                }

                version = '1.0'
                """ + julcBlock);
    }

    private void writeAlwaysTrueValidator() throws IOException {
        Files.createDirectories(plutusSrcDir);
        Files.writeString(plutusSrcDir.resolve("AlwaysTrue.java"), """
                import org.julclang.core.PlutusData;

                @SpendingValidator
                class AlwaysTrue {
                    @Entrypoint
                    static boolean validate(PlutusData redeemer, PlutusData ctx) {
                        return true;
                    }
                }
                """);
    }

    private static void deleteRecursively(Path dir) throws IOException {
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    private GradleRunner createRunner(String... args) {
        return GradleRunner.create()
                .withProjectDir(testProjectDir.toFile())
                .withArguments(args)
                .withPluginClasspath()
                .forwardOutput();
    }
}
