package com.bloxbean.cardano.julc.compiler;

import java.util.function.Consumer;

/**
 * Configuration options for the JuLC compiler.
 * <p>
 * Use {@link #setVerbose(boolean)} to enable verbose output that logs
 * each compilation stage and internal decisions.
 */
public class CompilerOptions {
    private boolean verbose = false;
    private Consumer<String> logger = System.out::println;

    public CompilerOptions setVerbose(boolean verbose) {
        this.verbose = verbose;
        return this;
    }

    public boolean isVerbose() {
        return verbose;
    }

    public CompilerOptions setLogger(Consumer<String> logger) {
        this.logger = logger;
        return this;
    }

    /**
     * Log a message if verbose mode is enabled.
     */
    public void log(String msg) {
        if (verbose) logger.accept("[julc] " + msg);
    }

    /**
     * Log a formatted message if verbose mode is enabled.
     */
    public void logf(String fmt, Object... args) {
        if (verbose) logger.accept("[julc] " + String.format(fmt, args));
    }
}
