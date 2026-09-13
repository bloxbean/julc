package org.julclang.cli.cmd.blueprint;

import org.julclang.clientlib.JulcScriptAdapter;
import org.julclang.core.text.UplcPrinter;
import org.julclang.cli.cmd.EvalCommand;
import org.julclang.cli.output.AnsiColors;
import org.julclang.cli.project.ProjectLayout;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;

@Command(name = "inspect", description = "Show human-readable UPLC from blueprint")
public class InspectCommand implements Runnable {

    @Parameters(index = "0", defaultValue = ".", description = "Project directory")
    private Path projectDir;

    @Override
    public void run() {
        try {
            Path blueprintFile = ProjectLayout.plutusDir(projectDir.toAbsolutePath()).resolve("plutus.json");
            if (!Files.exists(blueprintFile)) {
                System.err.println(AnsiColors.red("No blueprint found. Run 'julc build' first."));
                System.exit(1);
            }

            String json = Files.readString(blueprintFile);
            String hex = EvalCommand.extractCompiledCode(json);
            var program = JulcScriptAdapter.toProgram(hex);
            System.out.println(UplcPrinter.print(program));
        } catch (Exception e) {
            System.err.println(AnsiColors.red("Error: " + e.getMessage()));
            System.exit(1);
        }
    }
}
