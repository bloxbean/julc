package com.bloxbean.julc.cli.cmd.uplc;

import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(
        name = "uplc",
        description = "UPLC utility commands",
        subcommands = {
                FmtCommand.class,
                UplcEvalCommand.class,
                EncodeCommand.class,
                DecodeCommand.class,
                UplcInspectCommand.class,
                CommandLine.HelpCommand.class
        }
)
public class UplcCommand implements Runnable {
    @Override
    public void run() {
        new CommandLine(this).usage(System.out);
    }
}
