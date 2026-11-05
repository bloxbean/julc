package org.julclang.vm;

import org.julclang.core.PlutusData;
import org.julclang.core.Program;
import org.julclang.vm.corpus.EvaluationRecorder;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
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
 * <p>The language-only overloads are compatibility APIs. The Java and Truffle
 * providers use the protocol retained with a configured cost model, or PV10
 * when none is configured. Ledger validation should use an overload accepting
 * {@link LedgerEvaluationTarget}; providers without protocol-aware support fail
 * closed on that canonical path.</p>
 *
 * @see JulcVmProvider
 */
public final class JulcVm {

    private final JulcVmProvider provider;
    private final PlutusLanguage language;
    /**
     * For the ADR-068 M0 evaluation recorder only (F315): whether this facade created its provider (so its
     * configuration is the built-in default plus what this facade set), and the cost configurations set through
     * it, the last per language. Kept only while the recorder is active; never read by an evaluation.
     */
    private final boolean providerCreatedHere;
    private final Map<PlutusLanguage, EvaluationRecorder.CostConfiguration> recordedCosts = new EnumMap<>(PlutusLanguage.class);

    private JulcVm(JulcVmProvider provider, PlutusLanguage language, boolean providerCreatedHere) {
        this.provider = Objects.requireNonNull(provider);
        this.language = Objects.requireNonNull(language);
        this.providerCreatedHere = providerCreatedHere;
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
        return new JulcVm(provider, language, true);
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
        return new JulcVm(provider, language, true);
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
        return new JulcVm(provider, PlutusLanguage.PLUTUS_V3, false);
    }

    /**
     * Create a JulcVm with an explicit provider and language version.
     */
    public static JulcVm withProvider(JulcVmProvider provider, PlutusLanguage language) {
        return new JulcVm(provider, language, false);
    }

    // --- Evaluate with default options ---

    /**
     * Evaluate through the provider's language-only compatibility path.
     * Java and Truffle default this path to PV10 when no model is configured.
     */
    public EvalResult evaluate(Program program) {
        return observed(language, program, List.of(), null, provider.evaluate(program, language, null));
    }

    /** Evaluate a UPLC program with a maximum budget and default options. */
    public EvalResult evaluate(Program program, ExBudget budget) {
        return observed(language, program, List.of(), budget, provider.evaluate(program, language, budget));
    }

    /** Evaluate with arguments, unlimited budget, default options. */
    public EvalResult evaluateWithArgs(Program program, List<PlutusData> args) {
        return observed(language, program, args, null, provider.evaluateWithArgs(program, language, args, null));
    }

    /** Evaluate with arguments, maximum budget, default options. */
    public EvalResult evaluateWithArgs(Program program, List<PlutusData> args, ExBudget budget) {
        return observed(language, program, args, budget, provider.evaluateWithArgs(program, language, args, budget));
    }

    // --- Evaluate with explicit options ---

    /** Evaluate a UPLC program with per-evaluation options and unlimited budget. */
    public EvalResult evaluate(Program program, EvalOptions options) {
        return observed(language, program, List.of(), null, provider.evaluate(program, language, null, options));
    }

    /** Evaluate a UPLC program with per-evaluation options and a maximum budget. */
    public EvalResult evaluate(Program program, ExBudget budget, EvalOptions options) {
        return observed(language, program, List.of(), budget, provider.evaluate(program, language, budget, options));
    }

    /** Evaluate with arguments, per-evaluation options, and unlimited budget. */
    public EvalResult evaluateWithArgs(Program program, List<PlutusData> args, EvalOptions options) {
        return observed(language, program, args, null, provider.evaluateWithArgs(program, language, args, null, options));
    }

    /** Evaluate with arguments, per-evaluation options, and a maximum budget. */
    public EvalResult evaluateWithArgs(Program program, List<PlutusData> args,
                                       ExBudget budget, EvalOptions options) {
        return observed(language, program, args, budget, provider.evaluateWithArgs(program, language, args, budget, options));
    }

    // --- Canonical protocol-aware evaluation ---

    /** Evaluate for an explicit ledger target with unlimited budget. */
    public EvalResult evaluate(Program program, LedgerEvaluationTarget target) {
        return observed(target, program, List.of(), null, provider.evaluate(program, target, null, EvalOptions.DEFAULT));
    }

    /** Evaluate for an explicit ledger target and budget. */
    public EvalResult evaluate(Program program, LedgerEvaluationTarget target, ExBudget budget) {
        return observed(target, program, List.of(), budget, provider.evaluate(program, target, budget, EvalOptions.DEFAULT));
    }

    /** Evaluate for an explicit ledger target, budget, and options. */
    public EvalResult evaluate(Program program, LedgerEvaluationTarget target,
                               ExBudget budget, EvalOptions options) {
        return observed(target, program, List.of(), budget, provider.evaluate(program, target, budget, options));
    }

    /** Evaluate with arguments for an explicit ledger target. */
    public EvalResult evaluateWithArgs(Program program, LedgerEvaluationTarget target,
                                       List<PlutusData> args, ExBudget budget,
                                       EvalOptions options) {
        return observed(target, program, args, budget, provider.evaluateWithArgs(program, target, args, budget, options));
    }

    /**
     * Hands the evaluation to the ADR-068 M0 evaluation recorder when one is active (test-only; one test of a
     * {@code static final} field otherwise), and returns the result unchanged.
     */
    private EvalResult observed(Object target, Program program, List<PlutusData> args, ExBudget budget,
                                EvalResult result) {
        if (EvaluationRecorder.ACTIVE != null) {
            EvaluationRecorder.ACTIVE.record(provider.name(), String.valueOf(target), recordedConfiguration(), program,
                    args == null ? List.of() : args, budget, result);
        }
        return result;
    }

    /**
     * The provider's effective cost configuration for the recorder: the configurations set through this facade, in
     * language order, or null when the provider was handed in and its state is unknown.
     */
    private List<EvaluationRecorder.CostConfiguration> recordedConfiguration() {
        if (!providerCreatedHere) return null;
        synchronized (recordedCosts) {
            return List.copyOf(recordedCosts.values());
        }
    }

    /** Remembers a configuration the provider accepted, while the recorder is active (test-only). */
    private void recordCosts(long[] costModelValues, PlutusLanguage language, int major, int minor) {
        if (EvaluationRecorder.ACTIVE == null) return;
        synchronized (recordedCosts) {
            recordedCosts.put(language, new EvaluationRecorder.CostConfiguration(language, major, minor, costModelValues));
        }
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
        recordCosts(costModelValues, language, protocolMajorVersion, protocolMinorVersion);
    }

    /** Set cost parameters for an explicit ledger target. */
    public void setCostModelParams(long[] costModelValues, LedgerEvaluationTarget target) {
        provider.setCostModelParams(costModelValues, target);
        recordCosts(costModelValues, target.ledgerLanguage(), target.protocolVersion().major(),
                target.protocolVersion().minor());
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
