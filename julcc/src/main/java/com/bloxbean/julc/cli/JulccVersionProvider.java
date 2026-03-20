package com.bloxbean.julc.cli;

import com.bloxbean.cardano.julc.blueprint.JulcVersion;
import picocli.CommandLine;

public class JulccVersionProvider implements CommandLine.IVersionProvider {

    public static final String VERSION = JulcVersion.VERSION;
    public static final String PLUTUS_VERSION = "V3";
    public static final String CARDANO_CLIENT_LIB_VERSION = "0.8.0-preview1";

    @Override
    public String[] getVersion() {
        return new String[]{
                "julcc " + VERSION,
                "  Plutus: " + PLUTUS_VERSION,
                "  Java:   " + Runtime.version().feature()
        };
    }
}
