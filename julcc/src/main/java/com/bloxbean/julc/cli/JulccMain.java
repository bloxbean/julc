package com.bloxbean.julc.cli;

import picocli.CommandLine;

public class JulccMain {

    public static void main(String[] args) {
        int exitCode = new CommandLine(new JulccCommand())
                .setCaseInsensitiveEnumValuesAllowed(true)
                .execute(args);
        System.exit(exitCode);
    }
}
