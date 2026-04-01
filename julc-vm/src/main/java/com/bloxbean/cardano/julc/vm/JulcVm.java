package com.bloxbean.cardano.julc.vm;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.Program;

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
 * // Evaluate with arguments and options
 * var options = EvalOptions.DEFAULT.withSourceMap(sourceMap).withTracing(true);
 * EvalResult result = vm.evaluateWithArgs(program, List.of(ctx), options);
 * // Traces are in the result: result.executionTrace(), result.builtinTrace()
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

    // --- Evaluate with default options ---

    /** Evaluate a UPLC program with unlimited budget and default options. */
    public EvalResult evaluate(Program program) {
        return provider.evaluate(program, language, null);
    }

    /** Evaluate a UPLC program with a maximum budget and default options. */
    public EvalResult evaluate(Program program, ExBudget budget) {
        return provider.evaluate(program, language, budget);
    }

    /** Evaluate with arguments, unlimited budget, default options. */
    public EvalResult evaluateWithArgs(Program program, List<PlutusData> args) {
        return provider.evaluateWithArgs(program, language, args, null);
    }

    /** Evaluate with arguments, maximum budget, default options. */
    public EvalResult evaluateWithArgs(Program program, List<PlutusData> args, ExBudget budget) {
        return provider.evaluateWithArgs(program, language, args, budget);
    }

    // --- Evaluate with explicit options ---

    /** Evaluate a UPLC program with per-evaluation options and unlimited budget. */
    public EvalResult evaluate(Program program, EvalOptions options) {
        return provider.evaluate(program, language, null, options);
    }

    /** Evaluate a UPLC program with per-evaluation options and a maximum budget. */
    public EvalResult evaluate(Program program, ExBudget budget, EvalOptions options) {
        return provider.evaluate(program, language, budget, options);
    }

    /** Evaluate with arguments, per-evaluation options, and unlimited budget. */
    public EvalResult evaluateWithArgs(Program program, List<PlutusData> args, EvalOptions options) {
        return provider.evaluateWithArgs(program, language, args, null, options);
    }

    /** Evaluate with arguments, per-evaluation options, and a maximum budget. */
    public EvalResult evaluateWithArgs(Program program, List<PlutusData> args,
                                       ExBudget budget, EvalOptions options) {
        return provider.evaluateWithArgs(program, language, args, budget, options);
    }

    // --- Configuration ---

    /**
     * Set the cost model parameter values for a specific Plutus language version.
     * Values must be in the canonical order (matching the on-chain costModels array).
     * <p>
     * This is shared/global configuration set once at startup — not per-evaluation.
     */
    public void setCostModelParams(long[] costModelValues, PlutusLanguage language,
                                   int protocolMajorVersion, int protocolMinorVersion) {
        provider.setCostModelParams(costModelValues, language, protocolMajorVersion, protocolMinorVersion);
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
