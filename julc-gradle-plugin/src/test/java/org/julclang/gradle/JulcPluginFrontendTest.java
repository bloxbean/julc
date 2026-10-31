package org.julclang.gradle;

import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-068 D4: {@code julc { frontend = ... }}. The script from {@code javac} must equal the script from the default, and a
 * construct not ported must stop that frontend and not the default: the second proves the option reaches the compilation.
 * A blueprint needs a contract schema, which {@code javac} does not produce yet, so these turn it off.
 */
class JulcPluginFrontendTest {

    @TempDir
    Path project;

    @BeforeEach
    void setUp() throws IOException {
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name = 'frontend-project'\n");
        Path sources = project.resolve("src/main/plutus/com/example");
        Files.createDirectories(sources);
    }

    private void write(String body) throws IOException {
        Files.writeString(project.resolve("src/main/plutus/com/example/Fee.java"), """
                package com.example;

                import java.math.BigInteger;
                import org.julclang.core.PlutusData;
                import org.julclang.ledger.ScriptContext;
                import org.julclang.stdlib.annotation.Entrypoint;
                import org.julclang.stdlib.annotation.SpendingValidator;

                @SpendingValidator
                public class Fee {
                    @Entrypoint
                    public static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        %s
                    }
                }
                """.formatted(body));
    }

    private void configure(String frontend) throws IOException {
        Files.writeString(project.resolve("build.gradle"), """
                plugins {
                    id 'org.julclang.julc'
                }
                julc {
                    blueprint = false
                    %s
                }
                """.formatted(frontend == null ? "" : "frontend = '" + frontend + "'"));
    }

    private GradleRunner runner() {
        return GradleRunner.create().withProjectDir(project.toFile()).withPluginClasspath()
                .withArguments("compileJulc", "--stacktrace", "--no-watch-fs");
    }

    private String script() throws IOException {
        return Files.readString(project.resolve("build/plutus/Fee.json"));
    }

    @Test
    void javacCompilesTheScriptTheDefaultDoes() throws IOException {
        write("return ctx.txInfo().fee().compareTo(BigInteger.TWO) > 0;");
        configure(null);
        assertEquals(TaskOutcome.SUCCESS, runner().build().task(":compileJulc").getOutcome());
        String reference = script();
        configure("javac");
        assertEquals(TaskOutcome.SUCCESS, runner().build().task(":compileJulc").getOutcome());
        assertEquals(reference, script());
    }

    @Test
    void theOptionReachesTheCompilation() throws IOException {
        write("var fee = ctx.txInfo().fee(); return fee.signum() > 0;");
        configure("javaparser");
        assertEquals(TaskOutcome.SUCCESS, runner().build().task(":compileJulc").getOutcome());
        configure("javac");
        var result = runner().buildAndFail();
        assertTrue(result.getOutput().contains("JULC0084"), result.getOutput());
    }

    @Test
    void anUnknownFrontendFailsTheBuild() throws IOException {
        write("return true;");
        configure("eclipse");
        var result = runner().buildAndFail();
        assertTrue(result.getOutput().contains("Unknown frontend 'eclipse'"), result.getOutput());
    }
}
