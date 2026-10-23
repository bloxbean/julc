package org.julclang.difftest;

import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.error.CompilerDiagnostic;
import org.julclang.core.Program;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.vm.EvalResult;
import org.julclang.vm.ExBudget;
import org.julclang.vm.JulcVm;
import org.julclang.vm.OptimizationCostProfiles;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The UPLC side of the harness (ADR-063 D2): JuLC compiles the entry method under the
 * configuration's optimization level, and the configured VM evaluates it with the PV11 cost model
 * and a bounded budget. This side never consults the JVM result (H1).
 */
public final class UplcExecutor {

    /**
     * The evaluation bound: 10^10 CPU and 1.4 * 10^7 memory units, the order of a mainnet transaction's
     * limit. It is a harness bound, not a ledger rule. Without it a script that never terminates runs
     * until the test JVM is out of memory; with it, it is an exhausted budget (ADR-063 H5).
     */
    static final ExBudget BUDGET = new ExBudget(10_000_000_000L, 14_000_000L);

    private final Map<String, JulcVm> vms = new ConcurrentHashMap<>();

    public Observation run(DiffCase diffCase, DiffConfig config) {
        Program program;
        try {
            program = new JulcCompiler(StdlibRegistry.defaultRegistry(),
                    new CompilerOptions().setOptimizationLevel(config.level()))
                    .compileMethod(diffCase.source(), diffCase.method()).program();
        } catch (CompilerException e) {
            var error = e.diagnostics().stream()
                    .filter(d -> d.level() == CompilerDiagnostic.Level.ERROR).findFirst();
            return new Observation.Rejected(error.map(CompilerDiagnostic::code).orElse(null),
                    error.map(CompilerDiagnostic::message).orElse(e.getMessage()));
        } catch (RuntimeException | StackOverflowError | OutOfMemoryError e) {
            // Running out of memory while compiling a small program is a compiler defect, such as
            // exponential growth; the harness reports it rather than dying with the test JVM.
            return new Observation.Crash("compiler: " + e);
        }
        EvalResult result;
        try {
            result = vm(config.vm()).evaluateWithArgs(program, diffCase.args(), BUDGET);
        } catch (RuntimeException | StackOverflowError | OutOfMemoryError e) {
            return new Observation.Crash("vm: " + e);
        }
        return switch (result) {
            case EvalResult.Success s -> {
                try {
                    yield new Observation.Value(ValueMapping.fromTerm(s.resultTerm()), s.traces());
                } catch (HarnessException e) {
                    yield new Observation.Crash("result: " + e.getMessage());
                }
            }
            case EvalResult.Failure f -> new Observation.Failure(f.error(), f.traces());
            case EvalResult.BudgetExhausted b -> new Observation.Inconclusive("budget exhausted");
        };
    }

    private JulcVm vm(String provider) {
        return vms.computeIfAbsent(provider, name -> {
            var vm = JulcVm.create(name);
            var profile = OptimizationCostProfiles.PLUTUS_V3_PV11_COSTS_V1;
            vm.setCostModelParams(profile.costModelParameters(), profile.target());
            return vm;
        });
    }
}
