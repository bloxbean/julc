package com.bloxbean.julc.cli.project;

import com.bloxbean.julc.cli.JulcVersionProvider;

/**
 * Model for julc.toml project configuration.
 */
public record JulcToml(String name, String version, String compiler) {

    public static JulcToml defaultProject(String name) {
        return new JulcToml(name, JulcVersionProvider.VERSION, JulcVersionProvider.VERSION);
    }

    public String toToml() {
        return "[project]\n" +
                "name = \"" + name + "\"\n" +
                "version = \"" + version + "\"\n" +
                "compiler = \"" + compiler + "\"\n";
    }
}
