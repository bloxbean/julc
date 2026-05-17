package com.bloxbean.julc.cli.cmd;

import com.bloxbean.julc.cli.project.JulcToml;
import com.bloxbean.julc.cli.project.ProjectLayout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CheckCommandTest {

    @Test
    void lintErrorsFailByDefaultEvenWhenNoTests(@TempDir Path root) throws IOException {
        writeProject(root);
        writeBannedParamSource(root);

        var out = new ByteArrayOutputStream();
        int exit = CheckCommand.runCheck(root, false, false, new PrintStream(out), System.err);

        assertEquals(1, exit);
        assertTrue(out.toString().contains("JULC-LINT-BANNED-PARAM-TYPE"),
                "lint finding should be printed: " + out);
        assertTrue(out.toString().contains("Check failed: lint produced error-level finding"),
                "blocking lint should explain the failing exit code: " + out);
    }

    @Test
    void noStrictLintMakesLintInformationalWhenNoTests(@TempDir Path root) throws IOException {
        writeProject(root);
        writeBannedParamSource(root);

        var out = new ByteArrayOutputStream();
        int exit = CheckCommand.runCheck(root, false, true, new PrintStream(out), System.err);

        assertEquals(0, exit);
        assertTrue(out.toString().contains("JULC-LINT-BANNED-PARAM-TYPE"),
                "--no-strict-lint should still run lint and print findings: " + out);
        assertTrue(out.toString().contains("No tests found in test/"));
    }

    @Test
    void noLintSkipsBlockingLint(@TempDir Path root) throws IOException {
        writeProject(root);
        writeBannedParamSource(root);

        var out = new ByteArrayOutputStream();
        int exit = CheckCommand.runCheck(root, true, false, new PrintStream(out), System.err);

        assertEquals(0, exit);
        assertTrue(out.toString().contains("No tests found in test/"));
    }

    @Test
    void lintErrorsFailByDefaultEvenWhenTestsPass(@TempDir Path root) throws IOException {
        writeProject(root);
        writeBannedParamSource(root);
        writePassingTest(root);

        var out = new ByteArrayOutputStream();
        int exit = CheckCommand.runCheck(root, false, false, new PrintStream(out), System.err);

        assertEquals(1, exit);
        assertTrue(out.toString().contains("JULC-LINT-BANNED-PARAM-TYPE"),
                "lint finding should be printed: " + out);
        assertTrue(out.toString().contains("test_ok"),
                "passing test should still run and be reported: " + out);
        assertTrue(out.toString().contains("Check failed: lint produced error-level finding"),
                "blocking lint should explain the failing exit code after test summary: " + out);
    }

    @Test
    void lintReportsNestedSourcePath(@TempDir Path root) throws IOException {
        writeProject(root);
        Files.createDirectories(ProjectLayout.srcDir(root).resolve("myorg"));
        Files.writeString(ProjectLayout.srcDir(root).resolve("myorg/Params.java"), """
                package myorg;

                import com.bloxbean.cardano.julc.core.PlutusData;
                import com.bloxbean.cardano.julc.stdlib.annotation.Param;

                public class Params {
                    @Param static PlutusData.ListData xs;
                }
                """);

        var out = new ByteArrayOutputStream();
        int exit = CheckCommand.runCheck(root, false, true, new PrintStream(out), System.err);

        assertEquals(0, exit);
        assertTrue(out.toString().contains("src/myorg/Params.java"),
                "lint output should preserve nested source paths: " + out);
    }

    private static void writeProject(Path root) throws IOException {
        Files.createDirectories(ProjectLayout.srcDir(root));
        Files.createDirectories(ProjectLayout.testDir(root));
        Files.writeString(ProjectLayout.tomlFile(root),
                JulcToml.defaultProject("check-command-test").toToml());
    }

    private static void writeBannedParamSource(Path root) throws IOException {
        Files.writeString(ProjectLayout.srcDir(root).resolve("Params.java"), """
                import com.bloxbean.cardano.julc.core.PlutusData;
                import com.bloxbean.cardano.julc.stdlib.annotation.Param;

                public class Params {
                    @Param static PlutusData.ListData xs;
                }
                """);
    }

    private static void writePassingTest(Path root) throws IOException {
        Files.writeString(ProjectLayout.testDir(root).resolve("SanityTest.java"), """
                import com.bloxbean.cardano.julc.stdlib.test.Test;

                public class SanityTest {
                    @Test
                    public static boolean test_ok() {
                        return true;
                    }
                }
                """);
    }
}
