package com.bloxbean.cardano.julc.analyzer.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Top-level CLI command for julc-analyzer.
 */
@Command(
        name = "julc-analyzer",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class,
        description = "JuLC Security Analyzer for Cardano Plutus scripts",
        subcommands = {
                AnalyzeSubcommand.class,
                CommandLine.HelpCommand.class
        }
)
public class JulcAnalyzerCommand implements Runnable {

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }
}
