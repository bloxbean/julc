package org.julclang.cli;

import org.julclang.cli.cmd.*;
import org.julclang.cli.cmd.blueprint.BlueprintCommand;
import org.julclang.cli.cmd.uplc.UplcCommand;
import org.julclang.cli.cmd.verify.VerifyCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(
        name = "julc",
        mixinStandardHelpOptions = true,
        versionProvider = JulcVersionProvider.class,
        description = "Cardano smart contract toolkit for Java",
        subcommands = {
                NewCommand.class,
                InstallCommand.class,
                BuildCommand.class,
                CheckCommand.class,
                EvalCommand.class,
                ReplCommand.class,
                BlueprintCommand.class,
                UplcCommand.class,
                VerifyCommand.class,
                McpCommand.class,
                VersionCommand.class,
                CommandLine.HelpCommand.class
        }
)
public class JulcCommand implements Runnable {

    @Override
    public void run() {
        new CommandLine(this).usage(System.out);
    }
}
