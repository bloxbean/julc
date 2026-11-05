package org.julclang.cli.cmd;

import org.julclang.cli.project.ProjectLayout;
import org.julclang.cli.scaffold.ProjectScaffolder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code julc build} on a validator with a syntax error reports javac's coded diagnostic and fails, without a stack trace. */
class BuildCommandUnreadableSourceTest {

    @Test
    void aSyntaxErrorIsAJavacDiagnostic(@TempDir Path dir) throws Exception {
        Path project = dir.resolve("broken");
        ProjectScaffolder.scaffold(project, "broken");
        Files.writeString(ProjectLayout.srcDir(project).resolve("AlwaysSucceeds.java"), """
                import org.julclang.core.PlutusData;
                import org.julclang.ledger.ScriptContext;
                import org.julclang.stdlib.annotation.Entrypoint;
                import org.julclang.stdlib.annotation.SpendingValidator;

                @SpendingValidator
                public class AlwaysSucceeds {
                    @Entrypoint
                    public static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        return true
                    }
                }
                """);
        var err = new ByteArrayOutputStream();
        var saved = System.err;
        int exit;
        try {
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            exit = new CommandLine(new BuildCommand()).execute(project.toString(), "--no-blueprint");
        } finally {
            System.setErr(saved);
        }
        String output = err.toString(StandardCharsets.UTF_8);
        assertEquals(1, exit, output);
        assertTrue(output.contains("JULC0085"), output);
        assertTrue(output.contains("AlwaysSucceeds.java"), output);
        assertTrue(output.contains("10"), output);  // the line of the missing ';'
        assertFalse(output.contains("at org.julclang"), "no stack trace: " + output);
    }
}
