package org.julclang.cli.cmd;

import org.julclang.cli.project.ProjectLayout;
import org.julclang.cli.scaffold.ProjectScaffolder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-068 M6 removed {@code julc build --frontend}: a build that still names another frontend stops with what to do, and javac
 * is what JuLC does anyway. A blueprint builds on javac.
 */
class BuildCommandFrontendTest {

    private static Path project(Path dir, String name, String body) throws Exception {
        Path project = dir.resolve(name);
        ProjectScaffolder.scaffold(project, name);
        Files.writeString(ProjectLayout.srcDir(project).resolve("AlwaysSucceeds.java"), """
                import java.math.BigInteger;
                import org.julclang.core.PlutusData;
                import org.julclang.ledger.ScriptContext;
                import org.julclang.stdlib.annotation.Entrypoint;
                import org.julclang.stdlib.annotation.SpendingValidator;

                @SpendingValidator
                public class AlwaysSucceeds {
                    @Entrypoint
                    public static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        %s
                    }
                }
                """.formatted(body));
        return project;
    }

    private static int build(Path project, String... options) {
        var arguments = new String[options.length + 2];
        arguments[0] = project.toString();
        arguments[1] = "--no-blueprint";
        System.arraycopy(options, 0, arguments, 2, options.length);
        return new CommandLine(new BuildCommand()).execute(arguments);
    }

    /** Every artifact of the build, by name: the build writes more than one for a validator. */
    private static Map<String, String> artifacts(Path project) throws Exception {
        var artifacts = new TreeMap<String, String>();
        try (var files = Files.list(ProjectLayout.plutusDir(project))) {
            for (var file : files.filter(Files::isRegularFile).toList()) {
                artifacts.put(file.getFileName().toString(), Files.readString(file));
            }
        }
        return artifacts;
    }

    @Test
    void naming_javaparser_stopsTheBuildWithWhatToDo(@TempDir Path dir) throws Exception {
        var err = System.err;
        var written = new java.io.ByteArrayOutputStream();
        System.setErr(new java.io.PrintStream(written, true));
        try {
            assertEquals(1, build(project(dir, "old", "return true;"), "--frontend", "javaparser"));
        } finally {
            System.setErr(err);
        }
        assertTrue(written.toString().contains("The Java frontend option is gone"), written.toString());
    }

    @Test
    void naming_javac_builds(@TempDir Path dir) throws Exception {
        assertEquals(0, build(project(dir, "still", "return true;"), "--frontend", "javac"));
    }

    @Test
    void aBlueprintBuilds(@TempDir Path dir) throws Exception {
        Path project = project(dir, "blueprint", "return ctx.txInfo().fee().compareTo(BigInteger.TWO) > 0;");
        assertEquals(0, new CommandLine(new BuildCommand()).execute(project.toString()));
        assertTrue(artifacts(project).containsKey("plutus.json"), () -> project.toString());
    }
}
