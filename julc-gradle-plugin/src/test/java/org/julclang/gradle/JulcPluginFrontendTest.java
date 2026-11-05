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

/** ADR-068 M6 removed {@code julc { frontend = ... }}: naming another frontend stops the build with what to do. */
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

    /** ADR-068 M5: a rejection of javac's frontend shows its code and fix, here {@code byte[].equals}'s remedy. */
    @Test
    void aRejectionShowsItsCodeAndFix() throws IOException {
        write("byte[] a = new byte[]{1}; return a.equals(new byte[]{1});");
        configure(null);
        var output = runner().buildAndFail().getOutput();
        assertTrue(output.contains("JULC0087") && output.contains("Builtins.equalsByteString(a, b)"), output);
    }

    @Test
    void naming_javaparser_stopsTheBuildWithWhatToDo() throws IOException {
        write("return true;");
        configure("javaparser");
        var result = runner().buildAndFail();
        assertTrue(result.getOutput().contains("The Java frontend option is gone"), result.getOutput());
    }

    @Test
    void naming_javac_compiles() throws IOException {
        write("return true;");
        configure("javac");
        runner().build();
        assertTrue(script().contains("cborHex"), this::toString);
    }
}
