package com.bloxbean.cardano.julc.vm;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.core.source.SourceMap;
import com.bloxbean.cardano.julc.vm.trace.BuiltinExecution;
import com.bloxbean.cardano.julc.vm.trace.ExecutionTraceEntry;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.ServiceLoader;

/**
 * Main facade for evaluating UPLC programs.
 * <p>
 * Usage:
 * <pre>{@code
 * // Auto-detect best available VM backend
 * JulcVm vm = JulcVm.create();
 *
 * // Evaluate a program
 * EvalResult result = vm.evaluate(program);
 *
 * // Evaluate with arguments
 * EvalResult result = vm.evaluateWithArgs(program,
 *     List.of(datum, redeemer, scriptContext));
 * }</pre>
 *
 * @see JulcVmProvider
 */
public final class JulcVm {

    private final JulcVmProvider provider;
    private final PlutusLanguage language;

    private JulcVm(JulcVmProvider provider, PlutusLanguage language) {
        this.provider = Objects.requireNonNull(provider);
        this.language = Objects.requireNonNull(language);
    }

    /**
     * Create a JulcVm with the best available provider (highest priority)
     * for Plutus V3.
     *
     * @throws IllegalStateException if no provider is found on the classpath
     */
    public static JulcVm create() {
        return create(PlutusLanguage.PLUTUS_V3);
    }

    /**
     * Create a JulcVm with the best available provider (highest priority)
     * for the specified language version.
     *
     * @param language the Plutus language version
     * @throws IllegalStateException if no provider is found on the classpath
     */
    public static JulcVm create(PlutusLanguage language) {
        var provider = ServiceLoader.load(JulcVmProvider.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .max(Comparator.comparingInt(JulcVmProvider::priority))
                .orElseThrow(() -> new IllegalStateException(
                        "No JulcVmProvider found. Add plutus-vm-scalus or plutus-vm-java to your classpath."));
        return new JulcVm(provider, language);
    }

    /**
     * Create a JulcVm with a specific provider selected by name (e.g., "Truffle", "Java", "Scalus").
     *
     * @param providerName the name to match (case-insensitive)
     * @throws IllegalStateException if no provider with that name is found
     */
    public static JulcVm create(String providerName) {
        return create(providerName, PlutusLanguage.PLUTUS_V3);
    }

    /**
     * Create a JulcVm with a specific provider selected by name and language version.
     *
     * @param providerName the name to match (case-insensitive)
     * @param language     the Plutus language version
     * @throws IllegalStateException if no provider with that name is found
     */
    public static JulcVm create(String providerName, PlutusLanguage language) {
        var provider = ServiceLoader.load(JulcVmProvider.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .filter(p -> p.name().equalsIgnoreCase(providerName))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No JulcVmProvider with name '" + providerName + "' found. " +
                        "Available: " + availableProviders()));
        return new JulcVm(provider, language);
    }

    /**
     * List all available VM providers, sorted by priority (highest first).
     * Useful for logging which backends are on the classpath.
     *
     * @return list of provider descriptions (e.g., "Truffle (priority 200)")
     */
    public static List<String> availableProviders() {
        return ServiceLoader.load(JulcVmProvider.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .sorted(Comparator.comparingInt(JulcVmProvider::priority).reversed())
                .map(p -> p.name() + " (priority " + p.priority() + ")")
                .toList();
    }

    /**
     * Create a JulcVm with an explicit provider and Plutus V3.
     */
    public static JulcVm withProvider(JulcVmProvider provider) {
        return new JulcVm(provider, PlutusLanguage.PLUTUS_V3);
    }

    /**
     * Create a JulcVm with an explicit provider and language version.
     */
    public static JulcVm withProvider(JulcVmProvider provider, PlutusLanguage language) {
        return new JulcVm(provider, language);
    }

    /**
     * Evaluate a UPLC program with unlimited budget.
     */
    public EvalResult evaluate(Program program) {
        return provider.evaluate(program, language, null);
    }

    /**
     * Evaluate a UPLC program with a maximum budget.
     */
    public EvalResult evaluate(Program program, ExBudget budget) {
        return provider.evaluate(program, language, budget);
    }

    /**
     * Evaluate a UPLC program applied to the given arguments, with unlimited budget.
     * <p>
     * Arguments are applied as PlutusData constants.
     */
    public EvalResult evaluateWithArgs(Program program, List<PlutusData> args) {
        return provider.evaluateWithArgs(program, language, args, null);
    }

    /**
     * Evaluate a UPLC program applied to the given arguments, with a maximum budget.
     */
    public EvalResult evaluateWithArgs(Program program, List<PlutusData> args, ExBudget budget) {
        return provider.evaluateWithArgs(program, language, args, budget);
    }

    /**
     * Set the cost model parameter values for a specific Plutus language version.
     * Values must be in the canonical order (matching the on-chain costModels array).
     * <p>
     * Mixed-version transactions require calling this once per language version
     * present in the transaction (V1, V2, and/or V3).
     * <p>
     * The protocol version determines which builtins are available and therefore
     * the expected parameter count. New builtins are added to all language versions
     * (V1/V2/V3) in each protocol version, so V1/V2 arrays grow over time too.
     *
     * @param costModelValues       ordered array of cost model parameter values
     * @param language              the Plutus language version these parameters are for
     * @param protocolMajorVersion  the protocol major version (e.g. 9 for Chang, 10 for Plomin)
     * @param protocolMinorVersion  the protocol minor version
     */
    public void setCostModelParams(long[] costModelValues, PlutusLanguage language,
                                   int protocolMajorVersion, int protocolMinorVersion) {
        provider.setCostModelParams(costModelValues, language, protocolMajorVersion, protocolMinorVersion);
    }

    /**
     * Set the source map for debugging support.
     * When set, evaluation errors can include the originating Java source location,
     * and execution tracing can map CEK steps back to Java source lines.
     * <p>
     * No-op if the active provider does not support source maps.
     *
     * @param sourceMap the source map from compilation (nullable — pass null to disable)
     */
    public void setSourceMap(SourceMap sourceMap) {
        provider.setSourceMap(sourceMap);
    }

    /**
     * Enable or disable execution tracing.
     * When enabled (and a source map is set), each statement-level CEK step
     * is recorded with its Java source location.
     * <p>
     * This is the heavier tracing option — for lightweight diagnostics, use
     * {@link #setBuiltinTraceEnabled(boolean)} instead.
     * <p>
     * No-op if the active provider does not support tracing.
     *
     * @param enabled true to enable execution tracing, false to disable
     */
    public void setTracingEnabled(boolean enabled) {
        provider.setTracingEnabled(enabled);
    }

    /**
     * Enable or disable builtin trace collection.
     * When enabled, the last N builtin executions (function name, argument values,
     * result value) are recorded. This is lightweight and useful for showing
     * <em>what values</em> caused a validator failure.
     * <p>
     * Enabled by default. Disable for zero-overhead production evaluation.
     * <p>
     * No-op if the active provider does not support builtin tracing.
     *
     * @param enabled true to enable builtin tracing, false to disable
     */
    public void setBuiltinTraceEnabled(boolean enabled) {
        provider.setBuiltinTraceEnabled(enabled);
    }

    /**
     * Returns the execution trace from the most recent evaluation.
     * Empty list if tracing was disabled, no source map was set, or
     * the active provider does not support tracing.
     */
    public List<ExecutionTraceEntry> getLastExecutionTrace() {
        return provider.getLastExecutionTrace();
    }

    /**
     * Returns the last N builtin executions from the most recent evaluation.
     * Empty list if builtin tracing was disabled or the active provider does not support this.
     */
    public List<BuiltinExecution> getLastBuiltinTrace() {
        return provider.getLastBuiltinTrace();
    }

    /** The name of the active VM provider. */
    public String providerName() {
        return provider.name();
    }

    /** The Plutus language version being used. */
    public PlutusLanguage language() {
        return language;
    }
}
