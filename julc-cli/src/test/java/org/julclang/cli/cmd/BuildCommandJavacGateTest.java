package org.julclang.cli.cmd;

import org.julclang.cli.project.ProjectLayout;
import org.julclang.cli.scaffold.ProjectScaffolder;
import org.julclang.compiler.frontend.javac.JavacGate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-068 D8, M1: the CLI's host adapter. {@code julc build} keeps its pool (the class loader's bundled sources and
 * the project's library sources); with {@code julc.javacGate=enforce} a javac error fails the build. The gate runs before
 * the JavaParser frontend, selected explicitly ({@code --frontend javaparser}) from ADR-068 M5.
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
        assertEquals(0, new CommandLine(new BuildCommand()).execute(project.toString(), "--frontend", "javaparser"));
        assertTrue(Files.isRegularFile(ProjectLayout.plutusDir(project).resolve("plutus.json")));
    }

    /** A subset rule reaches the build at the default mode, with nothing set. */
    @Test
    void aSubsetRuleFailsTheBuild(@TempDir Path dir) throws Exception {
        System.clearProperty(JavacGate.MODE_PROPERTY);
        assertBuildFails("JULC0098", project(dir, "(boolean) (Object) ctx.txInfo().fee()"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "record R(java.math.BigInteger a) { R { a = a.abs(); } }",
            "record R(java.math.BigInteger a) { R { a = java.math.BigInteger.ONE; } }",
            "record R(java.math.BigInteger a) { R() { this(java.math.BigInteger.ONE); } }",
            "static boolean OPEN = true; record R(java.math.BigInteger a) { R { OPEN = false; } }",
            "record R(java.math.BigInteger a) { R { a = a.max(java.math.BigInteger.ONE); } }"})
    void aConstructorsStatementFailsTheBuild(String member, @TempDir Path dir) throws Exception {
        System.clearProperty(JavacGate.MODE_PROPERTY);
        assertBuildFails("JULC0099", withMember(project(dir, "true"), member));
    }

    @Test
    void theCastAndNameRulesFailTheBuild(@TempDir Path dir) throws Exception {
        System.clearProperty(JavacGate.MODE_PROPERTY);
        assertBuildFails("JULC0098", project(dir.resolve("cast"), "org.julclang.core.PlutusData.cast(ctx.txInfo().fee(), Boolean.class)"));
        assertBuildFails("JULC0100", withMember(project(dir.resolve("type"), "true"), "record Boolean(java.math.BigInteger a) { }"));
        assertBuildFails("JULC0101", withMember(project(dir.resolve("method"), "true"),
                "record R(long a) { static boolean fromPlutusData(long x) { return false; } }"));
    }

    @Test
    void anEmptyConstructorBuilds(@TempDir Path dir) throws Exception {
        System.clearProperty(JavacGate.MODE_PROPERTY);
        Path project = withMember(project(dir, "true"), "record R(java.math.BigInteger a) { R { } }");
        assertEquals(0, new CommandLine(new BuildCommand()).execute(project.toString(), "--frontend", "javaparser"));
    }

    private static Path withMember(Path project, String member) throws Exception {
        Path validator = ProjectLayout.srcDir(project).resolve("AlwaysSucceeds.java");
        Files.writeString(validator, Files.readString(validator).replace("    @Entrypoint", "    " + member + "\n    @Entrypoint"));
        return project;
    }

    private static void assertBuildFails(String code, Path project) {
        var err = System.err;
        var written = new ByteArrayOutputStream();
        System.setErr(new PrintStream(written, true));
        try {
            assertEquals(1, new CommandLine(new BuildCommand()).execute(project.toString(), "--frontend", "javaparser"));
        } finally {
            System.setErr(err);
        }
        assertTrue(written.toString().contains(code), written.toString());
    }

    @Test
    void javacErrorFailsTheBuild(@TempDir Path dir) throws Exception {
        Path project = project(dir, "ctx.txInfo().fee() > Fees.minimum()");
        assertEquals(1, new CommandLine(new BuildCommand()).execute(project.toString(), "--frontend", "javaparser"));
    }
}
