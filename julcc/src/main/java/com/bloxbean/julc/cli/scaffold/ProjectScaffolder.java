package com.bloxbean.julc.cli.scaffold;

import com.bloxbean.julc.cli.project.JulcToml;
import com.bloxbean.julc.cli.project.ProjectLayout;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Creates the directory structure and template files for a new julcc project.
 */
public final class ProjectScaffolder {

    private ProjectScaffolder() {}

    public static void scaffold(Path projectRoot, String projectName) throws IOException {
        // Create directory structure
        Files.createDirectories(ProjectLayout.srcDir(projectRoot));
        Files.createDirectories(ProjectLayout.testDir(projectRoot));
        Files.createDirectories(ProjectLayout.julcDir(projectRoot));
        Files.createDirectories(ProjectLayout.plutusDir(projectRoot));

        // julc.toml
        var toml = JulcToml.defaultProject(projectName);
        Files.writeString(ProjectLayout.tomlFile(projectRoot), toml.toToml());

        // .gitignore
        Files.writeString(projectRoot.resolve(".gitignore"),
                """
                build/
                .julc/stdlib/
                *.class
                """);

        // Starter validator
        Files.writeString(ProjectLayout.srcDir(projectRoot).resolve("AlwaysSucceeds.java"),
                """
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
                """);

        // Starter test
        Files.writeString(ProjectLayout.testDir(projectRoot).resolve("AlwaysSucceedsTest.java"),
                """
                import com.bloxbean.cardano.julc.stdlib.test.Test;

                public class AlwaysSucceedsTest {

                    @Test
                    public static boolean test_always_passes() {
                        return true;
                    }

                    @Test
                    public static boolean test_math() {
                        return 1 + 1 == 2;
                    }
                }
                """);

        // Install stdlib sources
        String sha256 = StdlibInstaller.installFromEmbedded(projectRoot);
        StdlibInstaller.writeLock(projectRoot, sha256);

        // Generate IntelliJ project files
        IdeaConfigGenerator.generate(projectRoot, projectName);
    }
}
