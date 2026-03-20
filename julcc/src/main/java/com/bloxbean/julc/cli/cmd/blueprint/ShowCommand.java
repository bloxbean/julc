package com.bloxbean.julc.cli.cmd.blueprint;

import com.bloxbean.julc.cli.output.AnsiColors;
import com.bloxbean.julc.cli.project.ProjectLayout;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Command(name = "show", description = "Show blueprint summary")
public class ShowCommand implements Runnable {

    @Parameters(index = "0", defaultValue = ".", description = "Project directory")
    private Path projectDir;

    @Override
    public void run() {
        try {
            Path blueprintFile = ProjectLayout.plutusDir(projectDir.toAbsolutePath()).resolve("plutus.json");
            if (!Files.exists(blueprintFile)) {
                System.err.println(AnsiColors.red("No blueprint found. Run 'julcc build' first."));
                System.exit(1);
            }
            String json = Files.readString(blueprintFile);
            // Simple display — print the JSON with indentation
            System.out.println(json);
        } catch (IOException e) {
            System.err.println(AnsiColors.red("Error: " + e.getMessage()));
            System.exit(1);
        }
    }
}
