package com.bloxbean.julc.cli.project;

import com.bloxbean.julc.cli.JulccVersionProvider;

/**
 * Model for julc.toml project configuration.
 */
public record JulcToml(String name, String version, String compiler) {

    public static JulcToml defaultProject(String name) {
        return new JulcToml(name, JulccVersionProvider.VERSION, JulccVersionProvider.VERSION);
    }

    public String toToml() {
        return "[project]\n" +
                "name = \"" + name + "\"\n" +
                "version = \"" + version + "\"\n" +
                "compiler = \"" + compiler + "\"\n";
    }
}
