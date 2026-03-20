package com.bloxbean.julc.cli;

import picocli.CommandLine;

public class JulccVersionProvider implements CommandLine.IVersionProvider {

    public static final String VERSION = "0.1.0";
    public static final String PLUTUS_VERSION = "V3";

    @Override
    public String[] getVersion() {
        return new String[]{
                "julcc " + VERSION,
                "  Plutus: " + PLUTUS_VERSION,
                "  Java:   " + Runtime.version().feature()
        };
    }
}
