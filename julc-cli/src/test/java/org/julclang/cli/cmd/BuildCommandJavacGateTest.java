package org.julclang.cli.cmd;

import org.julclang.cli.project.ProjectLayout;
import org.julclang.cli.scaffold.ProjectScaffolder;
import org.julclang.compiler.frontend.javac.JavacGate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-068 D8, M1: the CLI's host adapter. {@code julc build} keeps its pool (the class loader's bundled sources and
 * the project's library sources); with {@code julc.javacGate=enforce} a javac error fails the build.
 */
class BuildCommandJavacGateTest {

    private String saved;

    @BeforeEach
    void enforce() {
        saved = System.getProperty(JavacGate.MODE_PROPERTY);
        System.setProperty(JavacGate.MODE_PROPERTY, "enforce");
    }

    @AfterEach
    void restore() {
        if (saved == null) System.clearProperty(JavacGate.MODE_PROPERTY);
        else System.setProperty(JavacGate.MODE_PROPERTY, saved);
    }

    static Path project(Path dir, String condition) throws Exception {
        Path project = dir.resolve("gate-build");
        ProjectScaffolder.scaffold(project, "gate-build");
        Path src = ProjectLayout.srcDir(project);
        Files.writeString(src.resolve("Fees.java"), """
                import java.math.BigInteger;
                import org.julclang.stdlib.annotation.OnchainLibrary;

                @OnchainLibrary
                public class Fees {
                    public static BigInteger minimum() { return BigInteger.TWO; }
                }
                """);
        Files.writeString(src.resolve("AlwaysSucceeds.java"), """
                import org.julclang.stdlib.annotation.SpendingValidator;
                import org.julclang.stdlib.annotation.Entrypoint;
                import org.julclang.ledger.ScriptContext;
                import org.julclang.stdlib.lib.MathLib;

                @SpendingValidator
                public class AlwaysSucceeds {

                    record Datum() {}
                    record Redeemer() {}

                    @Entrypoint
                    public static boolean validate(Datum datum, Redeemer redeemer, ScriptContext ctx) {
                        return %s;
                    }
                }
                """.formatted(condition));
        return project;
    }

    @Test
    void javaProjectWithALibraryAndTheStdlibBuilds(@TempDir Path dir) throws Exception {
        Path project = project(dir, "MathLib.max(ctx.txInfo().fee(), Fees.minimum()).signum() > 0");
        assertEquals(0, new CommandLine(new BuildCommand()).execute(project.toString()));
        assertTrue(Files.isRegularFile(ProjectLayout.plutusDir(project).resolve("plutus.json")));
    }

    @Test
    void javacErrorFailsTheBuild(@TempDir Path dir) throws Exception {
        Path project = project(dir, "ctx.txInfo().fee() > Fees.minimum()");
        assertEquals(1, new CommandLine(new BuildCommand()).execute(project.toString()));
    }
}
