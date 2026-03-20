package com.bloxbean.julc.cli.project;

/**
 * Model for julc.toml project configuration.
 */
public record JulcToml(String name, String version, String compiler) {

    public static JulcToml defaultProject(String name) {
        return new JulcToml(name, "0.1.0", com.bloxbean.julc.cli.JulccVersionProvider.VERSION);
    }

    public String toToml() {
        return "[project]\n" +
                "name = \"" + name + "\"\n" +
                "version = \"" + version + "\"\n" +
                "compiler = \"" + compiler + "\"\n";
    }
}
