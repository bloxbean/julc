package com.bloxbean.cardano.julc.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Functional tests for the julc Gradle plugin using Gradle TestKit.
 */
class JulcPluginTest {

    @TempDir
    Path testProjectDir;
    private Path buildFile;
    private Path plutusSrcDir;

    @BeforeEach
    void setUp() throws IOException {
        buildFile = testProjectDir.resolve("build.gradle");
        plutusSrcDir = testProjectDir.resolve("src/main/plutus");
        Files.createDirectories(plutusSrcDir);

        // Write settings.gradle
        Files.writeString(testProjectDir.resolve("settings.gradle"),
                "rootProject.name = 'test-project'\n");
    }

    @Test
    void pluginAppliesSuccessfully() throws IOException {
        Files.writeString(buildFile, """
                plugins {
                    id 'com.bloxbean.cardano.julc'
                }
                """);

        // Write a dummy validator so @SkipWhenEmpty doesn't skip
        writeAlwaysTrueValidator();

        BuildResult result = createRunner("tasks", "--all").build();
        assertTrue(result.getOutput().contains("compileJulc"));
    }

    @Test
    void compilesSpendingValidator() throws IOException {
        Files.writeString(buildFile, """
                plugins {
                    id 'com.bloxbean.cardano.julc'
                }
                """);

        writeAlwaysTrueValidator();

        BuildResult result = createRunner("compileJulc").build();
        assertEquals(TaskOutcome.SUCCESS, result.task(":compileJulc").getOutcome());

        // Verify output JSON exists
        Path outputJson = testProjectDir.resolve("build/plutus/AlwaysTrue.json");
        assertTrue(Files.exists(outputJson), "Expected AlwaysTrue.json in build/plutus/");

        String json = Files.readString(outputJson);
        assertTrue(json.contains("\"type\": \"PlutusScriptV3\""));
        assertTrue(json.contains("\"description\": \"AlwaysTrue\""));
        assertTrue(json.contains("\"cborHex\":"));
        assertTrue(json.contains("\"hash\":"));

        // Verify cborHex is non-empty hex
        assertTrue(json.matches("(?s).*\"cborHex\": \"[0-9a-f]+\".*"),
                "cborHex should be a non-empty hex string");
    }

    @Test
    void compilesMintingPolicy() throws IOException {
        Files.writeString(buildFile, """
                plugins {
                    id 'com.bloxbean.cardano.julc'
                }
                """);

        Files.writeString(plutusSrcDir.resolve("AlwaysMint.java"), """
                import com.bloxbean.cardano.julc.core.PlutusData;

                @MintingPolicy
                class AlwaysMint {
                    @Entrypoint
                    static boolean validate(PlutusData redeemer, PlutusData ctx) {
                        return true;
                    }
                }
                """);

        BuildResult result = createRunner("compileJulc").build();
        assertEquals(TaskOutcome.SUCCESS, result.task(":compileJulc").getOutcome());

        Path outputJson = testProjectDir.resolve("build/plutus/AlwaysMint.json");
        assertTrue(Files.exists(outputJson));

        String json = Files.readString(outputJson);
        assertTrue(json.contains("\"type\": \"PlutusScriptV3-Minting\""));
    }

    @Test
    void skipsNonAnnotatedFiles() throws IOException {
        Files.writeString(buildFile, """
                plugins {
                    id 'com.bloxbean.cardano.julc'
                }
                """);

        // Write a validator (required for @SkipWhenEmpty)
        writeAlwaysTrueValidator();

        // Write a non-annotated helper file
        Files.writeString(plutusSrcDir.resolve("Helper.java"), """
                class Helper {
                    static int add(int a, int b) { return a + b; }
                }
                """);

        BuildResult result = createRunner("compileJulc").build();
        assertEquals(TaskOutcome.SUCCESS, result.task(":compileJulc").getOutcome());

        // AlwaysTrue should be compiled
        assertTrue(Files.exists(testProjectDir.resolve("build/plutus/AlwaysTrue.json")));
        // Helper should NOT be compiled
        assertFalse(Files.exists(testProjectDir.resolve("build/plutus/Helper.json")));
    }

    @Test
    void taskIsUpToDate() throws IOException {
        Files.writeString(buildFile, """
                plugins {
                    id 'com.bloxbean.cardano.julc'
                }
                """);

        writeAlwaysTrueValidator();

        // First build
        createRunner("compileJulc").build();

        // Second build should be UP-TO-DATE
        BuildResult result = createRunner("compileJulc").build();
        assertEquals(TaskOutcome.UP_TO_DATE, result.task(":compileJulc").getOutcome());
    }

    @Test
    void customSourceAndOutputDirs() throws IOException {
        Path customSrcDir = testProjectDir.resolve("validators");
        Files.createDirectories(customSrcDir);

        Files.writeString(buildFile, """
                plugins {
                    id 'com.bloxbean.cardano.julc'
                }

                julc {
                    sourceDir = file('validators')
                    outputDir = file("${buildDir}/scripts")
                }
                """);

        Files.writeString(customSrcDir.resolve("AlwaysTrue.java"), """
                import com.bloxbean.cardano.julc.core.PlutusData;

                @Validator
                class AlwaysTrue {
                    @Entrypoint
                    static boolean validate(PlutusData redeemer, PlutusData ctx) {
                        return true;
                    }
                }
                """);

        BuildResult result = createRunner("compileJulc").build();
        assertEquals(TaskOutcome.SUCCESS, result.task(":compileJulc").getOutcome());

        Path outputJson = testProjectDir.resolve("build/scripts/AlwaysTrue.json");
        assertTrue(Files.exists(outputJson), "Expected output in custom dir build/scripts/");
    }

    private void writeAlwaysTrueValidator() throws IOException {
        Files.writeString(plutusSrcDir.resolve("AlwaysTrue.java"), """
                import com.bloxbean.cardano.julc.core.PlutusData;

                @Validator
                class AlwaysTrue {
                    @Entrypoint
                    static boolean validate(PlutusData redeemer, PlutusData ctx) {
                        return true;
                    }
                }
                """);
    }

    private GradleRunner createRunner(String... args) {
        return GradleRunner.create()
                .withProjectDir(testProjectDir.toFile())
                .withArguments(args)
                .withPluginClasspath()
                .forwardOutput();
    }
}
