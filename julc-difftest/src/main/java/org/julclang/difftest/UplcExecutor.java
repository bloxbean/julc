package org.julclang.difftest;

import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.error.CompilerDiagnostic;
import org.julclang.core.Program;
import org.julclang.core.flat.UplcFlatEncoder;
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
 * and a bounded budget. This side never consults the JVM result (H1). Besides the observation it
 * records what the script cost and how large it is (D2); the comparison never uses either.
 */
public final class UplcExecutor {

    /**
     * The evaluation bound: 10^10 CPU and 1.4 * 10^7 memory units, the order of a mainnet transaction's
     * limit. It is a harness bound, not a ledger rule. Without it a script that never terminates runs
     * until the test JVM is out of memory; with it, it is an exhausted budget (ADR-063 H5).
     */
    static final ExBudget BUDGET = new ExBudget(10_000_000_000L, 14_000_000L);

    private final Map<String, JulcVm> vms = new ConcurrentHashMap<>();
    /** Compiled programs by source, method and level; a rejection or crash is cached as its observation. */
    private final Map<String, Object> compiled = new ConcurrentHashMap<>();

    /** The budget an evaluation consumed and the FLAT size of the script, in bytes. */
    public record Measurement(long cpu, long memory, int scriptBytes) {
        @Override
        public String toString() {
            return "cpu " + cpu + " mem " + memory + " size " + scriptBytes;
        }
    }

    /** What one UPLC execution produced; the measurement is null when nothing was evaluated. */
    public record Execution(Observation observation, Measurement measurement) {
    }

    public Execution run(DiffCase diffCase, DiffConfig config) {
        Object compiledProgram = compiled.computeIfAbsent(
                config.level() + "\0" + diffCase.method() + "\0" + diffCase.source(), key -> compile(diffCase, config));
        if (compiledProgram instanceof Observation observation) return new Execution(observation, null);
        var program = (Program) compiledProgram;
        int size = UplcFlatEncoder.encodeProgram(program).length;
        EvalResult result;
        try {
            result = vm(config.vm()).evaluateWithArgs(program, diffCase.args(), BUDGET);
        } catch (RuntimeException | StackOverflowError | OutOfMemoryError e) {
            return new Execution(new Observation.Crash("vm: " + e), null);
        }
        var budget = result.budgetConsumed();
        var measurement = new Measurement(budget.cpuSteps(), budget.memoryUnits(), size);
        Observation observation = switch (result) {
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
        return new Execution(observation, measurement);
    }

    private static Object compile(DiffCase diffCase, DiffConfig config) {
        try {
            return new JulcCompiler(StdlibRegistry.defaultRegistry(),
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
