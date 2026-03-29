package com.bloxbean.julc.cli;

import com.bloxbean.julc.cli.cmd.*;
import com.bloxbean.julc.cli.cmd.blueprint.BlueprintCommand;
import com.bloxbean.julc.cli.cmd.uplc.UplcCommand;
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
