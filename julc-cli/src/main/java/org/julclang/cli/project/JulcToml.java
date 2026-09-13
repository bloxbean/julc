package org.julclang.cli.project;

import org.julclang.cli.JulcVersionProvider;

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
