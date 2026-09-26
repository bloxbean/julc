package org.julclang.compiler;

import org.julclang.compiler.error.CompilerDiagnostic;
import org.julclang.compiler.pir.ArrayLiteralFoldPass;
import org.julclang.compiler.pir.ListIndexPromotionPass;
import org.julclang.compiler.pir.ValueConversionSharingPass;
import org.julclang.compiler.pir.ValueLiteralFoldPass;
import org.julclang.vm.OptimizationCostProfile;
import org.julclang.vm.ProtocolCapability;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Configuration resolved for one compiler invocation, and the state its stages report into.
 *
 * <p>The context snapshots mutable {@link CompilerOptions} at the compiler
 * boundary. It is then passed through the pipeline so every target-sensitive
 * stage observes the same target and options. Stages record diagnostics and
 * applied optimization rules here, and the Java frontend may install the
 * binder-namespace check (ADR-060).
 */
public final class CompilationContext {

    /**
     * The rules that {@link CompilerOptions#disableOptimizationRule(String)} accepts: the
     * PIR-to-PIR passes, each of which checks {@link #ruleEnabled(String)} before it rewrites.
     * Rules implemented inside {@code UplcGenerator} and {@code UplcOptimizer} are selected by
     * the optimization level only.
     */
    private static final Set<String> SWITCHABLE_OPTIMIZATION_RULES = Set.of(
            ValueConversionSharingPass.RULE,
            ValueConversionSharingPass.PROJECTION_RULE,
            ListIndexPromotionPass.RULE,
            ValueLiteralFoldPass.RULE,
            ArrayLiteralFoldPass.RULE);

    private final ResolvedCompilerTarget resolvedTarget;
    private final boolean verbose;
    private final boolean sourceMapEnabled;
    private final OptimizationLevel optimizationLevel;
    private final OptimizationCostProfile optimizationCostProfile;
    private final Set<String> disabledOptimizationRules;
    private final Consumer<String> logger;
    private final List<CompilerDiagnostic> diagnostics = new ArrayList<>();
    private final LinkedHashSet<String> appliedOptimizationRules = new LinkedHashSet<>();
    private Predicate<String> binderNamespace;

    private CompilationContext(
            ResolvedCompilerTarget resolvedTarget,
            boolean verbose,
            boolean sourceMapEnabled,
            OptimizationLevel optimizationLevel,
            OptimizationCostProfile optimizationCostProfile,
            Set<String> disabledOptimizationRules,
            Consumer<String> logger) {
        this.resolvedTarget = Objects.requireNonNull(resolvedTarget, "resolvedTarget");
        this.verbose = verbose;
        this.sourceMapEnabled = sourceMapEnabled;
        this.optimizationLevel = Objects.requireNonNull(
                optimizationLevel, "optimizationLevel");
        this.optimizationCostProfile = optimizationCostProfile;
        this.disabledOptimizationRules = Set.copyOf(disabledOptimizationRules);
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** Resolve and snapshot options for one compilation. */
    public static CompilationContext resolve(CompilerOptions options) {
        var effectiveOptions = options != null ? options : new CompilerOptions();
        var resolvedTarget = CompilerTargetRegistry.resolve(effectiveOptions.getTarget());
        var optimizationLevel = effectiveOptions.getOptimizationLevel();
        var costProfile = effectiveOptions.getOptimizationCostProfile();
        if (costProfile != null
                && !costProfile.target().equals(resolvedTarget.target().ledgerTarget())) {
            throw CompilerTargetDiagnostics.optimizationCostProfileTargetMismatch(
                    costProfile, resolvedTarget.target());
        }
        for (var ruleId : effectiveOptions.getDisabledOptimizationRules()) {
            if (!SWITCHABLE_OPTIMIZATION_RULES.contains(ruleId)) {
                throw CompilerTargetDiagnostics.unknownOptimizationRule(
                        ruleId, switchableOptimizationRules());
            }
        }
        return new CompilationContext(
                resolvedTarget,
                effectiveOptions.isVerbose(),
                effectiveOptions.isSourceMapEnabled(),
                optimizationLevel,
                costProfile,
                effectiveOptions.getDisabledOptimizationRules(),
                effectiveOptions.getLogger());
    }

    /** The rule ids that can be disabled individually, in catalog order. */
    public static List<String> switchableOptimizationRules() {
        return List.of(
                ValueConversionSharingPass.RULE,
                ValueConversionSharingPass.PROJECTION_RULE,
                ListIndexPromotionPass.RULE,
                ValueLiteralFoldPass.RULE,
                ArrayLiteralFoldPass.RULE);
    }

    /**
     * Whether a switchable rule may fire in this compilation. The level gates remain the
     * primary selection; this only subtracts rules that were explicitly disabled.
     */
    public boolean ruleEnabled(String ruleId) {
        return !disabledOptimizationRules.contains(ruleId);
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
                null, // No current compiler rule consumes numeric cost parameters (ADR-032 #153).
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

    /**
     * ADR-060 G2: require every binder that reaches UPLC generation to be accepted by
     * {@code allowed}. The Java frontend enables this in tests; other frontends own their names.
     */
    void verifyBinderNames(Predicate<String> allowed) {
        this.binderNamespace = Objects.requireNonNull(allowed, "allowed");
    }

    /** Fails when a binder is outside the namespace the frontend declared: a compiler bug. */
    public void checkBinderName(String name) {
        if (binderNamespace != null && !binderNamespace.test(name)) {
            throw new IllegalStateException("ADR-060: binder '" + name
                    + "' is neither a reserved '#' name nor a source name");
        }
    }
}
