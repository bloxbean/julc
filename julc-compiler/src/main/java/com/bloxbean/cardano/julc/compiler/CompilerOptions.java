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
    private boolean sourceMapEnabled = false;
    private Consumer<String> logger = System.out::println;

    public CompilerOptions setVerbose(boolean verbose) {
        this.verbose = verbose;
        return this;
    }

    public boolean isVerbose() {
        return verbose;
    }

    /**
     * Enable source map generation. When enabled, the compiler captures source positions
     * for UPLC terms and skips optimization to preserve Term identity for runtime lookup.
     */
    public CompilerOptions setSourceMapEnabled(boolean sourceMapEnabled) {
        this.sourceMapEnabled = sourceMapEnabled;
        return this;
    }

    public boolean isSourceMapEnabled() {
        return sourceMapEnabled;
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
