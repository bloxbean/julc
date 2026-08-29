package com.bloxbean.cardano.julc.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;

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
        assertTrue(json.contains("\"purpose\": \"spending\""));
        assertTrue(json.contains("\"description\": \"AlwaysTrue\""));
        assertTrue(json.contains("\"cborHex\":"));
        assertTrue(json.contains("\"hash\":"));

        // Verify cborHex is non-empty hex
        assertTrue(json.matches("(?s).*\"cborHex\": \"[0-9a-f]+\".*"),
                "cborHex should be a non-empty hex string");
    }

    @Test
    void targetPropertyIsExactAndReported() throws IOException {
        Files.writeString(buildFile, """
                plugins {
                    id 'com.bloxbean.cardano.julc'
                }
                julc {
                    target = 'plutus-v3-pv11-uplc-1.1.0'
                }
                """);
        writeAlwaysTrueValidator();

        var result = createRunner("compileJulc").build();

        assertTrue(result.getOutput().contains(
                "target: plutus-v3-pv11-uplc-1.1.0"));
    }

    @Test
    void unknownFutureTargetFailsClosed() throws IOException {
        Files.writeString(buildFile, """
                plugins {
                    id 'com.bloxbean.cardano.julc'
                }
                julc {
                    target = 'plutus-v3-pv12-uplc-1.1.0'
                }
                """);
        writeAlwaysTrueValidator();

        var result = createRunner("compileJulc").buildAndFail();

        assertTrue(result.getOutput().contains("JULC0031")
                || result.getOutput().contains("Compiler target"));
    }

    @Test
    void compilesMintingValidator() throws IOException {
        Files.writeString(buildFile, """
                plugins {
                    id 'com.bloxbean.cardano.julc'
                }
                """);

        Files.writeString(plutusSrcDir.resolve("AlwaysMint.java"), """
                import com.bloxbean.cardano.julc.core.PlutusData;

                @MintingValidator
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
        assertTrue(json.contains("\"type\": \"PlutusScriptV3\""));
        assertTrue(json.contains("\"purpose\": \"minting\""));
    }

    @Test
    void blueprintCanBeDisabledWithoutDisablingCompilation() throws IOException {
        Files.writeString(buildFile, """
                plugins {
                    id 'com.bloxbean.cardano.julc'
                }

                julc {
                    blueprint = false
                }
                """);
        Files.writeString(plutusSrcDir.resolve("MultiGate.java"), """
                import com.bloxbean.cardano.julc.core.PlutusData;

                @MultiValidator
                class MultiGate {
                    @Entrypoint
                    static boolean validate(PlutusData redeemer, PlutusData ctx) {
                        return true;
                    }
                }
                """);

        BuildResult result = createRunner("compileJulc").build();

        assertEquals(TaskOutcome.SUCCESS, result.task(":compileJulc").getOutcome());
        assertTrue(Files.exists(testProjectDir.resolve("build/plutus/MultiGate.json")));
        assertFalse(Files.exists(testProjectDir.resolve("build/plutus/plutus.json")));
    }

    @Test
    void blueprintEnabledPublishesExplicitMultiPurposeInterfaces() throws IOException {
        Files.writeString(buildFile, """
                plugins {
                    id 'com.bloxbean.cardano.julc'
                }
                """);
        Files.writeString(plutusSrcDir.resolve("MultiGate.java"), """
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                import java.math.BigInteger;
                @MultiValidator class MultiGate {
                    record Datum(BigInteger value) {}
                    record Spend(BigInteger value) {}
                    record Mint(byte[] tokenName) {}
                    @Entrypoint(purpose = Purpose.SPEND)
                    static boolean spend(Datum datum, Spend redeemer, ScriptContext ctx) {
                        return true;
                    }
                    @Entrypoint(purpose = Purpose.MINT)
                    static boolean mint(Mint redeemer, ScriptContext ctx) { return true; }
                }
                """);

        BuildResult result = createRunner("compileJulc").build();
        assertEquals(TaskOutcome.SUCCESS, result.task(":compileJulc").getOutcome());
        String json = Files.readString(testProjectDir.resolve("build/plutus/plutus.json"));
        assertTrue(json.contains("\"title\": \"MultiGate.mint\""));
        assertTrue(json.contains("\"title\": \"MultiGate.spend\""));
        assertTrue(json.contains("\"purpose\": \"mint\""));
        assertTrue(json.contains("\"purpose\": \"spend\""));
    }

    @Test
    void schemaFailurePreservesLastGoodGradleOutputs() throws IOException {
        Files.writeString(buildFile, """
                plugins {
                    id 'com.bloxbean.cardano.julc'
                }
                """);
        writeAlwaysTrueValidator();
        createRunner("compileJulc").build();
        Path validatorJson = testProjectDir.resolve("build/plutus/AlwaysTrue.json");
        Path blueprintJson = testProjectDir.resolve("build/plutus/plutus.json");
        String oldValidator = Files.readString(validatorJson);
        String oldBlueprint = Files.readString(blueprintJson);

        Files.writeString(plutusSrcDir.resolve("AlwaysTrue.java"), """
                import com.bloxbean.cardano.julc.core.types.JulcArray;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                import java.math.BigInteger;

                @SpendingValidator
                class AlwaysTrue {
                    record Datum(JulcArray<BigInteger> values) {}
                    record Redeemer(BigInteger value) {}
                    @Entrypoint
                    static boolean validate(Datum datum, Redeemer redeemer, ScriptContext ctx) {
                        return false;
                    }
                }
                """);

        createRunner("compileJulc").buildAndFail();

        assertEquals(oldValidator, Files.readString(validatorJson));
        assertEquals(oldBlueprint, Files.readString(blueprintJson));
    }

    @Test
    void successfulGradleBuildRemovesOutputsForDeletedValidators() throws IOException {
        Files.writeString(buildFile, """
                plugins {
                    id 'com.bloxbean.cardano.julc'
                }
                """);
        writeAlwaysTrueValidator();
        createRunner("compileJulc").build();
        Path validatorJson = testProjectDir.resolve("build/plutus/AlwaysTrue.json");
        Path blueprintJson = testProjectDir.resolve("build/plutus/plutus.json");
        assertTrue(Files.exists(validatorJson));
        assertTrue(Files.exists(blueprintJson));

        Files.delete(plutusSrcDir.resolve("AlwaysTrue.java"));
        BuildResult result = createRunner("compileJulc").build();

        assertEquals(TaskOutcome.SUCCESS, result.task(":compileJulc").getOutcome());
        assertFalse(Files.exists(validatorJson));
        assertFalse(Files.exists(blueprintJson));
    }

    @Test
    void gradleOptOutRemovesStaleAnnotationProcessorAggregate() throws IOException {
        Files.writeString(buildFile, """
                plugins {
                    id 'com.bloxbean.cardano.julc'
                }

                julc {
                    blueprint = false
                }
                """);
        Path javaSource = testProjectDir.resolve("src/main/java/PlainJava.java");
        Files.createDirectories(javaSource.getParent());
        Files.writeString(javaSource, "class PlainJava {}\n");
        Path stale = testProjectDir.resolve(
                "build/classes/java/main/META-INF/plutus/plutus.json");
        Files.createDirectories(stale.getParent());
        Files.writeString(stale, "stale");

        BuildResult result = createRunner("compileJava").build();

        assertEquals(TaskOutcome.SUCCESS, result.task(":compileJava").getOutcome());
        assertFalse(Files.exists(stale));
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
    void ignoresValidatorAnnotationTextInComments() throws IOException {
        Files.writeString(buildFile, """
                plugins {
                    id 'com.bloxbean.cardano.julc'
                }
                """);

        writeAlwaysTrueValidator();
        Files.writeString(plutusSrcDir.resolve("Helper.java"), """
                /**
                 * Mentions @SpendingValidator but is not a validator.
                 */
                class Helper {
                    static int add(int a, int b) { return a + b; }
                }
                """);

        BuildResult result = createRunner("compileJulc").build();
        assertEquals(TaskOutcome.SUCCESS, result.task(":compileJulc").getOutcome());
        assertTrue(Files.exists(testProjectDir.resolve("build/plutus/AlwaysTrue.json")));
        assertFalse(Files.exists(testProjectDir.resolve("build/plutus/Helper.json")));
    }

    @Test
    void rejectsLegacyValidatorAnnotation() throws IOException {
        Files.writeString(buildFile, """
                plugins {
                    id 'com.bloxbean.cardano.julc'
                }
                """);

        Files.writeString(plutusSrcDir.resolve("Old.java"), """
                @Validator
                class Old {
                    @Entrypoint
                    static boolean validate(PlutusData redeemer, PlutusData ctx) {
                        return true;
                    }
                }
                """);

        BuildResult result = createRunner("compileJulc").buildAndFail();
        assertTrue(result.getOutput().contains("Use @SpendingValidator instead"));
    }

    @Test
    void reportsParseErrorForValidatorCandidate() throws IOException {
        Files.writeString(buildFile, """
                plugins {
                    id 'com.bloxbean.cardano.julc'
                }
                """);

        Files.writeString(plutusSrcDir.resolve("Broken.java"), """
                // @SpendingValidator
                class Broken {
                """);

        BuildResult result = createRunner("compileJulc").buildAndFail();
        assertTrue(result.getOutput().contains("Could not parse validator candidate"));
        assertTrue(result.getOutput().contains("Broken.java"));
    }

    @Test
    void rejectsValidatorThatAlsoDeclaresOnchainLibrary() throws IOException {
        Files.writeString(buildFile, """
                plugins {
                    id 'com.bloxbean.cardano.julc'
                }
                """);

        Files.writeString(plutusSrcDir.resolve("Confused.java"), """
                import com.bloxbean.cardano.julc.core.PlutusData;

                @OnchainLibrary
                @SpendingValidator
                class Confused {
                    @Entrypoint
                    static boolean validate(PlutusData redeemer, PlutusData ctx) {
                        return true;
                    }
                }
                """);

        BuildResult result = createRunner("compileJulc").buildAndFail();
        assertTrue(result.getOutput().contains("must not combine @OnchainLibrary"));
        assertTrue(result.getOutput().contains("@SpendingValidator"));
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

                @SpendingValidator
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

    @Test
    void compileTestJavaUsesBundledSourcesThroughDeclaredTaskGraph() throws IOException {
        Files.writeString(buildFile, """
                plugins {
                    id 'com.bloxbean.cardano.julc'
                }
                """);

        writeLocalOnchainLibrarySource();
        writeSimpleTestSource();

        BuildResult result = createRunner("clean", "compileTestJava", "--warning-mode", "fail").build();
        assertEquals(TaskOutcome.SUCCESS, result.task(":bundleJulcSources").getOutcome());
        assertEquals(TaskOutcome.SUCCESS, result.task(":processResources").getOutcome());
        assertEquals(TaskOutcome.SUCCESS, result.task(":compileTestJava").getOutcome());

        Path generatedIndex = testProjectDir.resolve(
                "build/generated/julc/resources/main/META-INF/plutus-sources/index.txt");
        Path resourcesIndex = testProjectDir.resolve(
                "build/resources/main/META-INF/plutus-sources/index.txt");
        assertTrue(Files.exists(generatedIndex), "Expected bundleJulcSources to write to generated resources");
        assertTrue(Files.exists(resourcesIndex), "Expected processResources to copy bundled sources");
    }

    @Test
    void compileTestJavaSupportsConfigurationCache() throws IOException {
        Files.writeString(buildFile, """
                plugins {
                    id 'com.bloxbean.cardano.julc'
                }
                """);

        writeLocalOnchainLibrarySource();
        writeSimpleTestSource();

        BuildResult first = createRunner("clean", "compileTestJava", "--configuration-cache").build();
        assertEquals(TaskOutcome.SUCCESS, first.task(":compileTestJava").getOutcome());

        BuildResult second = createRunner("compileTestJava", "--configuration-cache").build();
        assertNotNull(second.task(":compileTestJava"));
    }

    @Test
    void jarIncludesBundledSourcesWithoutDirectJarDependency() throws IOException {
        Files.writeString(buildFile, """
                plugins {
                    id 'com.bloxbean.cardano.julc'
                }
                """);

        writeLocalOnchainLibrarySource();

        BuildResult result = createRunner("clean", "jar").build();
        assertEquals(TaskOutcome.SUCCESS, result.task(":bundleJulcSources").getOutcome());
        assertEquals(TaskOutcome.SUCCESS, result.task(":processResources").getOutcome());
        assertEquals(TaskOutcome.SUCCESS, result.task(":jar").getOutcome());

        Path jarPath = testProjectDir.resolve("build/libs/test-project.jar");
        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            assertNotNull(jarFile.getEntry("META-INF/plutus-sources/index.txt"));
            assertNotNull(jarFile.getEntry("META-INF/plutus-sources/com/example/LocalLib.java"));
        }
    }

    private void writeAlwaysTrueValidator() throws IOException {
        Files.writeString(plutusSrcDir.resolve("AlwaysTrue.java"), """
                import com.bloxbean.cardano.julc.core.PlutusData;

                @SpendingValidator
                class AlwaysTrue {
                    @Entrypoint
                    static boolean validate(PlutusData redeemer, PlutusData ctx) {
                        return true;
                    }
                }
                """);
    }

    private void writeLocalOnchainLibrarySource() throws IOException {
        Path annotationSource = testProjectDir.resolve(
                "src/main/java/com/bloxbean/cardano/julc/stdlib/annotation/OnchainLibrary.java");
        Files.createDirectories(annotationSource.getParent());
        Files.writeString(annotationSource, """
                package com.bloxbean.cardano.julc.stdlib.annotation;

                public @interface OnchainLibrary {}
                """);

        Path librarySource = testProjectDir.resolve("src/main/java/com/example/LocalLib.java");
        Files.createDirectories(librarySource.getParent());
        Files.writeString(librarySource, """
                package com.example;

                import com.bloxbean.cardano.julc.stdlib.annotation.OnchainLibrary;

                @OnchainLibrary
                public final class LocalLib {
                    private LocalLib() {}

                    public static boolean ok() {
                        return true;
                    }
                }
                """);
    }

    private void writeSimpleTestSource() throws IOException {
        Path testSource = testProjectDir.resolve("src/test/java/com/example/LocalLibTest.java");
        Files.createDirectories(testSource.getParent());
        Files.writeString(testSource, """
                package com.example;

                final class LocalLibTest {
                    void compiles() {
                        if (!LocalLib.ok()) {
                            throw new AssertionError();
                        }
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
