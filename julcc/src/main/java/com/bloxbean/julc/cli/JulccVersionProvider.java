package com.bloxbean.julc.cli;

import picocli.CommandLine;

public class JulccVersionProvider implements CommandLine.IVersionProvider {

    public static final String VERSION;
    public static final String PLUTUS_VERSION = "V3";

    static {
        String v = "dev";
        try (var is = JulccVersionProvider.class.getClassLoader()
                .getResourceAsStream("julc-version.properties")) {
            if (is != null) {
                var props = new java.util.Properties();
                props.load(is);
                v = props.getProperty("version", "dev");
            }
        } catch (Exception _) {
            // fallback to "dev"
        }
        VERSION = v;
    }

    @Override
    public String[] getVersion() {
        return new String[]{
                "julcc " + VERSION,
                "  Plutus: " + PLUTUS_VERSION,
                "  Java:   " + Runtime.version().feature()
        };
    }
}
