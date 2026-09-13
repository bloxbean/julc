package org.julclang.cli.cmd;

import org.julclang.cli.JulcVersionProvider;
import org.julclang.cli.output.AnsiColors;
import org.julclang.cli.project.ProjectLayout;
import org.julclang.cli.project.TomlParser;
import org.julclang.cli.scaffold.StdlibInstaller;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;

@Command(name = "install", description = "Install or update stdlib sources for IDE support")
public class InstallCommand implements Runnable {

    @Parameters(index = "0", defaultValue = ".", description = "Project directory")
    private Path projectDir;

    @Override
    public void run() {
        try {
            Path root = projectDir.toAbsolutePath();
            Path tomlFile = ProjectLayout.tomlFile(root);
            if (!Files.exists(tomlFile)) {
                System.err.println(AnsiColors.red("Not a julc project (no julc.toml found)"));
                System.exit(1);
            }

            var config = TomlParser.parse(tomlFile);
            String targetVersion = config.compiler();
            String currentVersion = JulcVersionProvider.VERSION;

            String sha256;
            if (targetVersion.equals(currentVersion)) {
                System.out.println("Installing stdlib from embedded sources (v" + currentVersion + ") ...");
                sha256 = StdlibInstaller.installFromEmbedded(root);
            } else {
                String url = "https://github.com/bloxbean/julc/releases/download/v" +
                        targetVersion + "/julc-stdlib-sources.zip";
                System.out.println("Downloading stdlib v" + targetVersion + " ...");
                sha256 = StdlibInstaller.installFromUrl(url, root);
            }

            StdlibInstaller.writeLock(root, sha256);
            System.out.println(AnsiColors.green("Stdlib installed to .julc/stdlib/"));
        } catch (Exception e) {
            System.err.println(AnsiColors.red("Install failed: " + e.getMessage()));
            System.exit(1);
        }
    }
}
