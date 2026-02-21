package com.bloxbean.cardano.julc.analyzer.cli;

import picocli.CommandLine;

/**
 * Entry point for the julc-analyzer CLI.
 */
public class Main {

    public static void main(String[] args) {
        int exitCode = new CommandLine(new JulcAnalyzerCommand()).execute(args);
        System.exit(exitCode);
    }
}
