package org.julclang.gradle;

import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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

    /** A subset rule reaches the task at the default mode, with no property on the build. */
    @Test
    void aSubsetRuleFailsTheBuild() throws IOException {
        writeValidator("(boolean) (Object) ctx.txInfo().fee()");
        var result = GradleRunner.create().withProjectDir(project.toFile()).withPluginClasspath()
                .withArguments("compileJulc", "--stacktrace").buildAndFail();
        assertTrue(result.getOutput().contains("JULC0098"), result.getOutput());
    }

    @Test
    void aConstructorsStatementFailsTheBuild() throws IOException {
        for (String member : List.of(
                "record R(java.math.BigInteger a) { R { a = a.abs(); } }",
                "record R(java.math.BigInteger a) { R { a = java.math.BigInteger.ONE; } }",
                "record R(java.math.BigInteger a) { R() { this(java.math.BigInteger.ONE); } }",
                "static boolean OPEN = true; record R(java.math.BigInteger a) { R { OPEN = false; } }",
                "record R(java.math.BigInteger a) { R { a = a.max(java.math.BigInteger.ONE); } }")) {
            writeValidator("true");
            Path validator = sources.resolve("FeeGate.java");
            Files.writeString(validator, Files.readString(validator).replace("    @Entrypoint", "    " + member + "\n    @Entrypoint"));
            var result = GradleRunner.create().withProjectDir(project.toFile()).withPluginClasspath()
                    .withArguments("compileJulc", "--stacktrace").buildAndFail();
            assertTrue(result.getOutput().contains("JULC0099"), result.getOutput());
        }
    }

    private void assertBuildFails(String code) {
        var result = GradleRunner.create().withProjectDir(project.toFile()).withPluginClasspath()
                .withArguments("compileJulc", "--stacktrace").buildAndFail();
        assertTrue(result.getOutput().contains(code), result.getOutput());
    }

    @Test
    void theCastAndNameRulesFailTheBuild() throws IOException {
        writeValidator("PlutusData.cast(ctx.txInfo().fee(), Boolean.class)");
        assertBuildFails("JULC0098");
        for (String[] rule : List.of(new String[]{"JULC0100", "record Boolean(java.math.BigInteger a) { }"},
                new String[]{"JULC0101", "record R(long a) { static boolean fromPlutusData(long x) { return false; } }"})) {
            writeValidator("true");
            Path validator = sources.resolve("FeeGate.java");
            Files.writeString(validator, Files.readString(validator).replace("    @Entrypoint", "    " + rule[1] + "\n    @Entrypoint"));
            assertBuildFails(rule[0]);
        }
    }

    @Test
    void javacErrorFailsTheBuild() throws IOException {
        writeValidator("ctx.txInfo().fee() > Fees.minimum()");
        var result = runner().buildAndFail();
        assertTrue(result.getOutput().contains("JULC0085"), result.getOutput());
        assertTrue(result.getOutput().contains("compiler.err.operator.cant.be.applied.1"), result.getOutput());
    }
}
