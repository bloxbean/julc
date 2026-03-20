package com.bloxbean.julc.cli.scaffold;

import com.bloxbean.julc.cli.JulccVersionProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Generates a complete Gradle project with julc dependencies,
 * annotation processor, starter validator, and Gradle wrapper.
 */
public final class GradleProjectScaffolder {

    private GradleProjectScaffolder() {}

    public static void scaffold(Path root, String projectName, String group,
                                String artifact, String pkg) throws IOException {
        String pkgPath = PackageNameUtils.toPath(pkg);

        // Create directory structure
        Path mainJava = root.resolve("src/main/java").resolve(pkgPath);
        Path testJava = root.resolve("src/test/java").resolve(pkgPath);
        Files.createDirectories(mainJava);
        Files.createDirectories(testJava);

        String julcVersion = JulccVersionProvider.VERSION;
        String cclVersion = JulccVersionProvider.CARDANO_CLIENT_LIB_VERSION;

        // build.gradle
        Files.writeString(root.resolve("build.gradle"), buildGradle(group, julcVersion, cclVersion));

        // settings.gradle
        Files.writeString(root.resolve("settings.gradle"), settingsGradle(projectName));

        // .gitignore
        Files.writeString(root.resolve(".gitignore"), gitignore());

        // Starter validator
        Files.writeString(mainJava.resolve("AlwaysSucceeds.java"), validatorTemplate(pkg));

        // Starter test
        Files.writeString(testJava.resolve("AlwaysSucceedsTest.java"), testTemplate(pkg));

        // Extract Gradle wrapper
        WrapperExtractor.extractGradleWrapper(root);
    }

    private static String buildGradle(String group, String julcVersion, String cclVersion) {
        return """
                plugins {
                    id 'java'
                }

                group = '%s'
                version = '1.0-SNAPSHOT'

                ext {
                    julcVersion = '%s'
                    cardanoClientLibVersion = '%s'
                }

                repositories {
                    mavenLocal()
                    mavenCentral()
                    maven {
                        url = uri("https://central.sonatype.com/repository/maven-snapshots")
                        content { snapshotsOnly() }
                    }
                }

                java {
                    sourceCompatibility = JavaVersion.VERSION_24
                    targetCompatibility = JavaVersion.VERSION_24
                    toolchain {
                        languageVersion = JavaLanguageVersion.of(24)
                    }
                }

                dependencies {
                    // Core: stdlib + ledger types + annotations
                    implementation "com.bloxbean.cardano:julc-stdlib:${julcVersion}"

                    // Annotation processor -- compiles validators during javac
                    annotationProcessor "com.bloxbean.cardano:julc-annotation-processor:${julcVersion}"

                    // Offchain library
                    implementation "com.bloxbean.cardano:julc-cardano-client-lib:${julcVersion}"
                    implementation "com.bloxbean.cardano:cardano-client-lib:${cardanoClientLibVersion}"

                    // Test: VM for local evaluation
                    testImplementation "com.bloxbean.cardano:julc-testkit:${julcVersion}"
                    testImplementation "com.bloxbean.cardano:julc-compiler:${julcVersion}"
                    testRuntimeOnly "com.bloxbean.cardano:julc-vm-java:${julcVersion}"
                    testImplementation platform('org.junit:junit-bom:5.11.4')
                    testImplementation 'org.junit.jupiter:junit-jupiter'
                    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
                }

                tasks.withType(JavaCompile).configureEach {
                    options.compilerArgs += [
                        "-Ajulc.projectName=${project.name}",
                        "-Ajulc.projectVersion=${project.version}"
                    ]
                }

                test {
                    useJUnitPlatform()
                }
                """.formatted(group, julcVersion, cclVersion);
    }

    private static String settingsGradle(String projectName) {
        return """
                pluginManagement {
                    repositories {
                        mavenLocal()
                        maven {
                            url = uri("https://central.sonatype.com/repository/maven-snapshots")
                            content { snapshotsOnly() }
                        }
                        gradlePluginPortal()
                    }
                }

                rootProject.name = '%s'
                """.formatted(projectName);
    }

    private static String gitignore() {
        return """
                .gradle/
                build/
                *.class
                .idea/
                *.iml
                out/
                .DS_Store
                """;
    }

    private static String validatorTemplate(String pkg) {
        return """
                package %s;

                import com.bloxbean.cardano.julc.stdlib.annotation.Validator;
                import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                import com.bloxbean.cardano.julc.core.PlutusData;

                @Validator
                public class AlwaysSucceeds {

                    @Entrypoint
                    public static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        return true;
                    }
                }
                """.formatted(pkg);
    }

    private static String testTemplate(String pkg) {
        return """
                package %s;

                import com.bloxbean.cardano.julc.core.PlutusData;
                import com.bloxbean.cardano.julc.testkit.ContractTest;
                import com.bloxbean.cardano.julc.testkit.TestDataBuilder;
                import org.junit.jupiter.api.Test;

                import static org.junit.jupiter.api.Assertions.*;

                class AlwaysSucceedsTest extends ContractTest {

                    @Test
                    void testAlwaysSucceeds() {
                        var program = compileValidatorWithSourceMap(AlwaysSucceeds.class);

                        var ref = TestDataBuilder.randomTxOutRef_typed();
                        var ctx = spendingContext(ref, PlutusData.UNIT)
                                .redeemer(PlutusData.UNIT)
                                .buildPlutusData();

                        var result = evaluateWithTrace(program, ctx);

                        //Assert if successful
                        assertTrue(result.isSuccess());

                        System.out.print(formatExecutionTrace());
                        System.out.print(formatBudgetSummary());
                    }
                }
                """.formatted(pkg);
    }
}
