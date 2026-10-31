package org.julclang.gradle;

import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-068 D8, M1: the Gradle plugin's host adapter. The validator is the root and every other file of the source
 * directory is the pool, as the plugin compiles them; {@code -Djulc.javacGate=enforce} makes a javac error fail the
 * build (the property is set for the build only).
 */
class JulcPluginJavacGateTest {

    @TempDir
    Path project;
    Path sources;

    @BeforeEach
    void setUp() throws IOException {
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name = 'gate-project'\n");
        Files.writeString(project.resolve("build.gradle"), """
                plugins {
                    id 'org.julclang.julc'
                }
                """);
        sources = project.resolve("src/main/plutus/com/example");
        Files.createDirectories(sources);
        Files.writeString(sources.resolve("Fees.java"), """
                package com.example;

                import java.math.BigInteger;
                import org.julclang.stdlib.annotation.OnchainLibrary;

                @OnchainLibrary
                public class Fees {
                    public static BigInteger minimum() { return BigInteger.TWO; }
                }
                """);
    }

    void writeValidator(String condition) throws IOException {
        Files.writeString(sources.resolve("FeeGate.java"), """
                package com.example;

                import java.math.BigInteger;
                import org.julclang.core.PlutusData;
                import org.julclang.ledger.ScriptContext;
                import org.julclang.stdlib.annotation.Entrypoint;
                import org.julclang.stdlib.annotation.SpendingValidator;

                @SpendingValidator
                public class FeeGate {
                    @Entrypoint
                    public static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        return %s;
                    }
                }
                """.formatted(condition));
    }

    GradleRunner runner() {
        return GradleRunner.create().withProjectDir(project.toFile()).withPluginClasspath()
                .withArguments("compileJulc", "-Djulc.javacGate=enforce", "--stacktrace");
    }

    @Test
    void javaSourceWithASourceDirectoryLibraryCompiles() throws IOException {
        writeValidator("ctx.txInfo().fee().compareTo(Fees.minimum()) > 0");
        var result = runner().build();
        assertEquals(TaskOutcome.SUCCESS, result.task(":compileJulc").getOutcome());
        assertTrue(Files.exists(project.resolve("build/plutus/FeeGate.json")));
    }

    @Test
    void javacErrorFailsTheBuild() throws IOException {
        writeValidator("ctx.txInfo().fee() > Fees.minimum()");
        var result = runner().buildAndFail();
        assertTrue(result.getOutput().contains("JULC0085"), result.getOutput());
        assertTrue(result.getOutput().contains("compiler.err.operator.cant.be.applied.1"), result.getOutput());
    }
}
