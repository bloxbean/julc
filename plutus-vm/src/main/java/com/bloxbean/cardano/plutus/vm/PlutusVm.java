package com.bloxbean.cardano.plutus.vm;

import com.bloxbean.cardano.plutus.core.PlutusData;
import com.bloxbean.cardano.plutus.core.Program;

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
 * PlutusVm vm = PlutusVm.create();
 *
 * // Evaluate a program
 * EvalResult result = vm.evaluate(program);
 *
 * // Evaluate with arguments
 * EvalResult result = vm.evaluateWithArgs(program,
 *     List.of(datum, redeemer, scriptContext));
 * }</pre>
 *
 * @see PlutusVmProvider
 */
public final class PlutusVm {

    private final PlutusVmProvider provider;
    private final PlutusLanguage language;

    private PlutusVm(PlutusVmProvider provider, PlutusLanguage language) {
        this.provider = Objects.requireNonNull(provider);
        this.language = Objects.requireNonNull(language);
    }

    /**
     * Create a PlutusVm with the best available provider (highest priority)
     * for Plutus V3.
     *
     * @throws IllegalStateException if no provider is found on the classpath
     */
    public static PlutusVm create() {
        return create(PlutusLanguage.PLUTUS_V3);
    }

    /**
     * Create a PlutusVm with the best available provider (highest priority)
     * for the specified language version.
     *
     * @param language the Plutus language version
     * @throws IllegalStateException if no provider is found on the classpath
     */
    public static PlutusVm create(PlutusLanguage language) {
        var provider = ServiceLoader.load(PlutusVmProvider.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .max(Comparator.comparingInt(PlutusVmProvider::priority))
                .orElseThrow(() -> new IllegalStateException(
                        "No PlutusVmProvider found. Add plutus-vm-scalus or plutus-vm-java to your classpath."));
        return new PlutusVm(provider, language);
    }

    /**
     * Create a PlutusVm with an explicit provider and Plutus V3.
     */
    public static PlutusVm withProvider(PlutusVmProvider provider) {
        return new PlutusVm(provider, PlutusLanguage.PLUTUS_V3);
    }

    /**
     * Create a PlutusVm with an explicit provider and language version.
     */
    public static PlutusVm withProvider(PlutusVmProvider provider, PlutusLanguage language) {
        return new PlutusVm(provider, language);
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
