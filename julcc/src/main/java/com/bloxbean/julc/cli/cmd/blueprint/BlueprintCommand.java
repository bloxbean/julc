package com.bloxbean.julc.cli.cmd.blueprint;

import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(
        name = "blueprint",
        description = "Blueprint management commands",
        subcommands = {
                ShowCommand.class,
                AddressCommand.class,
                ApplyCommand.class,
                ConvertCommand.class,
                InspectCommand.class,
                CommandLine.HelpCommand.class
        }
)
public class BlueprintCommand implements Runnable {
    @Override
    public void run() {
        new CommandLine(this).usage(System.out);
    }
}
