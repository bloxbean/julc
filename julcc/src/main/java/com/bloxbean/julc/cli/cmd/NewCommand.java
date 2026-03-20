package com.bloxbean.julc.cli.cmd;

import com.bloxbean.julc.cli.output.AnsiColors;
import com.bloxbean.julc.cli.scaffold.ProjectScaffolder;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;

@Command(name = "new", description = "Create a new julcc project")
public class NewCommand implements Runnable {

    @Parameters(index = "0", description = "Project name")
    private String name;

    @Override
    public void run() {
        try {
            Path projectRoot = Path.of(name);
            if (Files.exists(projectRoot)) {
                System.err.println(AnsiColors.red("Directory '" + name + "' already exists."));
                System.exit(1);
            }

            System.out.println("Creating project " + AnsiColors.bold(name) + " ...");
            ProjectScaffolder.scaffold(projectRoot, name);
            System.out.println(AnsiColors.green("Project created at ./" + name));
            System.out.println();
            System.out.println("  cd " + name);
            System.out.println("  julcc build    # compile validators");
            System.out.println("  julcc check    # run tests");
        } catch (Exception e) {
            System.err.println(AnsiColors.red("Failed to create project: " + e.getMessage()));
            System.exit(1);
        }
    }
}
