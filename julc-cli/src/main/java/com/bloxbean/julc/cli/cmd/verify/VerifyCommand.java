package com.bloxbean.julc.cli.cmd.verify;

import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(
        name = "verify",
        description = "Generate and run formal-verification workspaces",
        subcommands = {
                VerifyInitCommand.class,
                VerifyRunCommand.class,
                CommandLine.HelpCommand.class
        }
)
public class VerifyCommand implements Runnable {
    @Override
    public void run() {
        new CommandLine(this).usage(System.out);
    }
}
