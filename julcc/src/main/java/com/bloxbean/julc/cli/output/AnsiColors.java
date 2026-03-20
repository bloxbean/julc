package com.bloxbean.julc.cli.output;

/**
 * ANSI terminal color helpers.
 */
public final class AnsiColors {

    private AnsiColors() {}

    private static final boolean ENABLED = !System.getenv().containsKey("NO_COLOR")
            && System.console() != null;

    public static final String RESET = ENABLED ? "\033[0m" : "";
    public static final String BOLD = ENABLED ? "\033[1m" : "";
    public static final String RED = ENABLED ? "\033[31m" : "";
    public static final String GREEN = ENABLED ? "\033[32m" : "";
    public static final String YELLOW = ENABLED ? "\033[33m" : "";
    public static final String BLUE = ENABLED ? "\033[34m" : "";
    public static final String CYAN = ENABLED ? "\033[36m" : "";
    public static final String DIM = ENABLED ? "\033[2m" : "";

    public static String red(String s) { return RED + s + RESET; }
    public static String green(String s) { return GREEN + s + RESET; }
    public static String yellow(String s) { return YELLOW + s + RESET; }
    public static String blue(String s) { return BLUE + s + RESET; }
    public static String cyan(String s) { return CYAN + s + RESET; }
    public static String bold(String s) { return BOLD + s + RESET; }
    public static String dim(String s) { return DIM + s + RESET; }
}
