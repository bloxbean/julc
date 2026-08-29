package com.bloxbean.cardano.julc.compiler;

import com.bloxbean.cardano.julc.compiler.error.CompilerDiagnostic;
import com.bloxbean.cardano.julc.vm.OptimizationCostProfile;
import com.bloxbean.cardano.julc.vm.ProtocolCapability;

import java.util.ArrayList;
import java.util.LinkedHashSet;
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
    private final OptimizationLevel optimizationLevel;
    private final OptimizationCostProfile optimizationCostProfile;
    private final Consumer<String> logger;
    private final List<CompilerDiagnostic> diagnostics = new ArrayList<>();
    private final LinkedHashSet<String> appliedOptimizationRules = new LinkedHashSet<>();

    private CompilationContext(
            ResolvedCompilerTarget resolvedTarget,
            boolean verbose,
            boolean sourceMapEnabled,
            OptimizationLevel optimizationLevel,
            OptimizationCostProfile optimizationCostProfile,
            Consumer<String> logger) {
        this.resolvedTarget = Objects.requireNonNull(resolvedTarget, "resolvedTarget");
        this.verbose = verbose;
        this.sourceMapEnabled = sourceMapEnabled;
        this.optimizationLevel = Objects.requireNonNull(
                optimizationLevel, "optimizationLevel");
        this.optimizationCostProfile = optimizationCostProfile;
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** Resolve and snapshot options for one compilation. */
    public static CompilationContext resolve(CompilerOptions options) {
        var effectiveOptions = options != null ? options : new CompilerOptions();
        var resolvedTarget = CompilerTargetRegistry.resolve(effectiveOptions.getTarget());
        var optimizationLevel = effectiveOptions.getOptimizationLevel();
        var costProfile = effectiveOptions.getOptimizationCostProfile();
        if (optimizationLevel.costProfileRequired() && costProfile == null) {
            throw CompilerTargetDiagnostics.missingOptimizationCostProfile(optimizationLevel);
        }
        if (costProfile != null
                && !costProfile.target().equals(resolvedTarget.target().ledgerTarget())) {
            throw CompilerTargetDiagnostics.optimizationCostProfileTargetMismatch(
                    costProfile, resolvedTarget.target());
        }
        return new CompilationContext(
                resolvedTarget,
                effectiveOptions.isVerbose(),
                effectiveOptions.isSourceMapEnabled(),
                optimizationLevel,
                costProfile,
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

    public OptimizationLevel optimizationLevel() {
        return optimizationLevel;
    }

    public OptimizationCostProfile optimizationCostProfile() {
        return optimizationCostProfile;
    }

    /** Record one stable rule identity in first-application order. */
    public void recordOptimizationRule(String ruleId) {
        if (ruleId == null || ruleId.isBlank()) {
            throw new IllegalArgumentException("ruleId must not be blank");
        }
        appliedOptimizationRules.add(ruleId);
    }

    public void recordOptimizationRules(Iterable<String> ruleIds) {
        Objects.requireNonNull(ruleIds, "ruleIds");
        ruleIds.forEach(this::recordOptimizationRule);
    }

    public OptimizationReport optimizationReport() {
        return OptimizationReport.of(
                optimizationLevel,
                optimizationCostProfile,
                List.copyOf(appliedOptimizationRules));
    }

    /** Whether both the ledger profile and selected UPLC version support a capability. */
    public boolean supports(ProtocolCapability capability) {
        if (capability == ProtocolCapability.CONSTR_CASE
                && !target().uplcVersion().supportsConstrAndCase()) {
            return false;
        }
        return resolvedTarget.featureProfile().supports(capability);
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
