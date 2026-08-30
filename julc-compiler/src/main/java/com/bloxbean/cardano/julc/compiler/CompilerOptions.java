package com.bloxbean.cardano.julc.compiler;

import com.bloxbean.cardano.julc.vm.OptimizationCostProfile;

import java.util.Objects;
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
    private CompilerTarget target = CompilerTarget.PLUTUS_V3_PV11;
    private OptimizationLevel optimizationLevel = OptimizationLevel.DEFAULT;
    private OptimizationCostProfile optimizationCostProfile;
    private Consumer<String> logger = System.out::println;

    /**
     * Select the ledger target for this compilation.
     *
     * <p>JuLC currently supports only {@link CompilerTarget#PLUTUS_V3_PV11}.
     * Unsupported targets fail explicitly when compilation begins; they never
     * fall back to this default.
     */
    public CompilerOptions setTarget(CompilerTarget target) {
        this.target = Objects.requireNonNull(target, "target");
        return this;
    }

    /** Return the requested compiler target. */
    public CompilerTarget getTarget() {
        return target;
    }

    /** Select optimizer rollout independently from compiler-target legality. */
    public CompilerOptions setOptimizationLevel(OptimizationLevel optimizationLevel) {
        this.optimizationLevel = Objects.requireNonNull(
                optimizationLevel, "optimizationLevel");
        return this;
    }

    public OptimizationLevel getOptimizationLevel() {
        return optimizationLevel;
    }

    /**
     * Select a pinned cost profile for {@link OptimizationLevel#PV11_COSTED}.
     * Supplying a profile does not enable cost-directed rules by itself.
     */
    public CompilerOptions setOptimizationCostProfile(
            OptimizationCostProfile optimizationCostProfile) {
        this.optimizationCostProfile = Objects.requireNonNull(
                optimizationCostProfile, "optimizationCostProfile");
        return this;
    }

    public OptimizationCostProfile getOptimizationCostProfile() {
        return optimizationCostProfile;
    }

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
        this.logger = Objects.requireNonNull(logger, "logger");
        return this;
    }

    Consumer<String> getLogger() {
        return logger;
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

    /**
     * Log a warning unconditionally (not gated by verbose mode).
     */
    public void warnf(String fmt, Object... args) {
        logger.accept("[julc] WARN: " + String.format(fmt, args));
    }
}
