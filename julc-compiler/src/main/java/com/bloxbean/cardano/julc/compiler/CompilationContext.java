package com.bloxbean.cardano.julc.compiler;

import com.bloxbean.cardano.julc.compiler.error.CompilerDiagnostic;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Immutable configuration resolved for one compiler invocation.
 *
 * <p>The context snapshots mutable {@link CompilerOptions} at the compiler
 * boundary. It is then passed through the pipeline so every target-sensitive
 * stage observes the same target and options.
 */
public final class CompilationContext {

    private final ResolvedCompilerTarget resolvedTarget;
    private final boolean verbose;
    private final boolean sourceMapEnabled;
    private final Consumer<String> logger;
    private final List<CompilerDiagnostic> diagnostics = new ArrayList<>();

    private CompilationContext(
            ResolvedCompilerTarget resolvedTarget,
            boolean verbose,
            boolean sourceMapEnabled,
            Consumer<String> logger) {
        this.resolvedTarget = Objects.requireNonNull(resolvedTarget, "resolvedTarget");
        this.verbose = verbose;
        this.sourceMapEnabled = sourceMapEnabled;
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** Resolve and snapshot options for one compilation. */
    public static CompilationContext resolve(CompilerOptions options) {
        var effectiveOptions = options != null ? options : new CompilerOptions();
        return new CompilationContext(
                CompilerTargetRegistry.resolve(effectiveOptions.getTarget()),
                effectiveOptions.isVerbose(),
                effectiveOptions.isSourceMapEnabled(),
                effectiveOptions.getLogger());
    }

    /** Create a context for the documented pinned PV11 defaults. */
    public static CompilationContext pv11Defaults() {
        return resolve(new CompilerOptions());
    }

    public ResolvedCompilerTarget resolvedTarget() {
        return resolvedTarget;
    }

    public CompilerTarget target() {
        return resolvedTarget.target();
    }

    public boolean isVerbose() {
        return verbose;
    }

    public boolean isSourceMapEnabled() {
        return sourceMapEnabled;
    }

    /** Return an immutable snapshot of diagnostics reported in this compilation. */
    public List<CompilerDiagnostic> diagnostics() {
        return List.copyOf(diagnostics);
    }

    List<CompilerDiagnostic> diagnosticBuffer() {
        return diagnostics;
    }

    public void log(String message) {
        if (verbose) {
            logger.accept("[julc] " + message);
        }
    }

    public void logf(String format, Object... args) {
        if (verbose) {
            logger.accept("[julc] " + String.format(format, args));
        }
    }

    public void warnf(String format, Object... args) {
        logger.accept("[julc] WARN: " + String.format(format, args));
    }
}
