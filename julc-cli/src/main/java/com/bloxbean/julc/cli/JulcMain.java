package com.bloxbean.julc.cli;

import picocli.CommandLine;

public class JulcMain {

    public static void main(String[] args) {
        int exitCode = new CommandLine(new JulcCommand())
                .setCaseInsensitiveEnumValuesAllowed(true)
                .execute(args);
        System.exit(exitCode);
    }
}
