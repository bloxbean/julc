package com.bloxbean.julc.cli.project;

import java.nio.file.Path;

/**
 * Constants for the julc project directory layout.
 */
public final class ProjectLayout {

    private ProjectLayout() {}

    public static final String TOML_FILE = "julc.toml";
    public static final String SRC_DIR = "src";
    public static final String TEST_DIR = "test";
    public static final String BUILD_DIR = "build";
    public static final String PLUTUS_DIR = "build/plutus";
    public static final String JULC_DIR = ".julc";
    public static final String STDLIB_DIR = ".julc/stdlib";
    public static final String LOCK_FILE = ".julc/lock.toml";

    public static Path tomlFile(Path root) { return root.resolve(TOML_FILE); }
    public static Path srcDir(Path root) { return root.resolve(SRC_DIR); }
    public static Path testDir(Path root) { return root.resolve(TEST_DIR); }
    public static Path buildDir(Path root) { return root.resolve(BUILD_DIR); }
    public static Path plutusDir(Path root) { return root.resolve(PLUTUS_DIR); }
    public static Path julcDir(Path root) { return root.resolve(JULC_DIR); }
    public static Path stdlibDir(Path root) { return root.resolve(STDLIB_DIR); }
    public static Path lockFile(Path root) { return root.resolve(LOCK_FILE); }
}
