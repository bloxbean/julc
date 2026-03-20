package com.bloxbean.julc.cli.cmd.blueprint;

import com.bloxbean.julc.cli.cmd.EvalCommand;
import com.bloxbean.julc.cli.output.AnsiColors;
import com.bloxbean.julc.cli.project.ProjectLayout;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;

@Command(name = "convert", description = "Convert blueprint to cardano-cli TextEnvelope format")
public class ConvertCommand implements Runnable {

    @Parameters(index = "0", defaultValue = ".", description = "Project directory")
    private Path projectDir;

    @Option(names = {"-o", "--out"}, description = "Output file path")
    private Path outFile;

    @Override
    public void run() {
        try {
            Path blueprintFile = ProjectLayout.plutusDir(projectDir.toAbsolutePath()).resolve("plutus.json");
            if (!Files.exists(blueprintFile)) {
                System.err.println(AnsiColors.red("No blueprint found. Run 'julcc build' first."));
                System.exit(1);
            }

            String json = Files.readString(blueprintFile);
            String hex = EvalCommand.extractCompiledCode(json);

            // cardano-cli TextEnvelope format
            String textEnvelope = """
                    {
                        "type": "PlutusScriptV3",
                        "description": "",
                        "cborHex": "%s"
                    }
                    """.formatted(hex);

            if (outFile != null) {
                Files.writeString(outFile, textEnvelope);
                System.out.println("Written to " + outFile);
            } else {
                System.out.println(textEnvelope);
            }
        } catch (Exception e) {
            System.err.println(AnsiColors.red("Error: " + e.getMessage()));
            System.exit(1);
        }
    }
}
