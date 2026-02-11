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

    /** The name of the active VM provider. */
    public String providerName() {
        return provider.name();
    }

    /** The Plutus language version being used. */
    public PlutusLanguage language() {
        return language;
    }
}
