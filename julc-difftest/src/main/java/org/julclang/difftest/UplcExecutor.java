package org.julclang.difftest;

import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.error.CompilerDiagnostic;
import org.julclang.core.Program;
import org.julclang.core.Term;
import org.julclang.core.flat.UplcFlatEncoder;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.vm.EvalResult;
import org.julclang.vm.ExBudget;
import org.julclang.vm.JulcVm;
import org.julclang.vm.OptimizationCostProfiles;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

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

    /**
     * {@link #BUDGET} times {@code factor}, for a confirmation rerun (ADR-063 D6, r6). A rerun is evidence for a
     * registry explanation only; no verdict uses it (D2).
     */
    public static ExBudget budgetTimes(long factor) {
        if (factor < 1) throw new IllegalArgumentException("the budget factor must be positive");
        return new ExBudget(Math.multiplyExact(BUDGET.cpuSteps(), factor), Math.multiplyExact(BUDGET.memoryUnits(), factor));
    }

    /**
     * The compile cache keeps at most this many entries. It serves a program run on several inputs one
     * after another, as the exhaustive corpus does; it is not a store for a whole campaign.
     */
    static final int MAX_CACHED_PROGRAMS = 64;
    /**
     * The compile cache also keeps at most this many FLAT bytes of scripts in all, and never a script larger
     * than this, which is compiled again each time. A program's AST is far larger than its FLAT script, and a
     * fuzz session through one runner produces many multi-megabyte programs (ADR-063 D13): unbounded, the
     * cache filled the test heap until later compiles failed with {@code OutOfMemoryError}.
     */
    static final long MAX_CACHED_SCRIPT_BYTES = 1L << 20;

    private final Map<String, JulcVm> vms = new ConcurrentHashMap<>();
    private final int maxCachedPrograms;
    private final long maxCachedScriptBytes;
    /** Applied to each evaluated result term before it is mapped: the identity, except in tests of the runner. */
    private final UnaryOperator<Term> resultTerms;
    /**
     * Compiled programs by source, method and level, least recently used first; a rejection or crash is
     * cached as its observation, with no script bytes. Guarded by {@code this}.
     */
    private final LinkedHashMap<String, Compiled> compiled = new LinkedHashMap<>(16, 0.75f, true);
    private long cachedScriptBytes;

    /** A compile result: a {@link Program} with its FLAT size, or the {@link Observation} it produced. */
    private record Compiled(Object result, int scriptBytes) {
    }

    public UplcExecutor() {
        this(MAX_CACHED_PROGRAMS, MAX_CACHED_SCRIPT_BYTES);
    }

    /** An executor whose compile cache has the given bounds. */
    UplcExecutor(int maxCachedPrograms, long maxCachedScriptBytes) {
        this(maxCachedPrograms, maxCachedScriptBytes, UnaryOperator.identity());
    }

    /**
     * A test seam: an executor that replaces each evaluated result term before mapping it, so that a test can give
     * {@link DiffRunner} a result with the right Data in the wrong representation (ADR-066 D4).
     */
    UplcExecutor(UnaryOperator<Term> resultTerms) {
        this(MAX_CACHED_PROGRAMS, MAX_CACHED_SCRIPT_BYTES, resultTerms);
    }

    private UplcExecutor(int maxCachedPrograms, long maxCachedScriptBytes, UnaryOperator<Term> resultTerms) {
        this.maxCachedPrograms = maxCachedPrograms;
        this.maxCachedScriptBytes = maxCachedScriptBytes;
        this.resultTerms = resultTerms;
    }

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
        return run(diffCase, config, BUDGET);
    }

    /** Runs the case under the configuration with another evaluation budget. */
    public Execution run(DiffCase diffCase, DiffConfig config, ExBudget budget) {
        return run(diffCase, config, budget, ValueMapping.Representation.ANY);
    }

    /**
     * Runs the case under the configuration with another evaluation budget. A result whose term does not have the
     * {@code representation} of the entry method's declared return type is a {@code Crash("result: representation
     * …")} (ADR-066 D4, G1-I10).
     */
    public Execution run(DiffCase diffCase, DiffConfig config, ExBudget budget, ValueMapping.Representation representation) {
        String key = config.level() + "\0" + diffCase.method() + "\0" + diffCase.source();
        Compiled entry = cached(key);
        if (entry == null) {
            entry = compile(diffCase, config);
            cache(key, entry);
        }
        if (entry.result() instanceof Observation observation) return new Execution(observation, null);
        var program = (Program) entry.result();
        int size = entry.scriptBytes();
        EvalResult result;
        try {
            result = vm(config.vm()).evaluateWithArgs(program, diffCase.args(), budget);
        } catch (RuntimeException | StackOverflowError | OutOfMemoryError e) {
            return new Execution(new Observation.Crash("vm: " + e), null);
        }
        var consumed = result.budgetConsumed();
        var measurement = new Measurement(consumed.cpuSteps(), consumed.memoryUnits(), size);
        Observation observation = switch (result) {
            case EvalResult.Success s -> {
                try {
                    yield new Observation.Value(ValueMapping.fromTerm(resultTerms.apply(s.resultTerm()), representation), s.traces());
                } catch (HarnessException e) {
                    yield new Observation.Crash("result: " + e.getMessage());
                }
            }
            case EvalResult.Failure f -> new Observation.Failure(f.error(), f.traces());
            case EvalResult.BudgetExhausted b -> new Observation.Inconclusive("budget exhausted");
        };
        return new Execution(observation, measurement);
    }

    private synchronized Compiled cached(String key) {
        return compiled.get(key);
    }

    /** Adds an entry, then evicts the least recently used until both bounds hold. */
    private synchronized void cache(String key, Compiled entry) {
        if (entry.scriptBytes() > maxCachedScriptBytes) return;
        Compiled previous = compiled.put(key, entry);
        if (previous != null) cachedScriptBytes -= previous.scriptBytes();
        cachedScriptBytes += entry.scriptBytes();
        Iterator<Compiled> eldest = compiled.values().iterator();
        while (compiled.size() > maxCachedPrograms || cachedScriptBytes > maxCachedScriptBytes) {
            cachedScriptBytes -= eldest.next().scriptBytes();
            eldest.remove();
        }
    }

    /** How many compile results the cache holds. */
    synchronized int cachedPrograms() {
        return compiled.size();
    }

    /** The FLAT bytes of the scripts the cache holds. */
    synchronized long cachedScriptBytes() {
        return cachedScriptBytes;
    }

    private static Compiled compile(DiffCase diffCase, DiffConfig config) {
        Program program;
        try {
            program = new JulcCompiler(StdlibRegistry.defaultRegistry(),
                    new CompilerOptions().setOptimizationLevel(config.level()))
                    .compileMethod(diffCase.source(), diffCase.method()).program();
        } catch (CompilerException e) {
            var error = e.diagnostics().stream()
                    .filter(d -> d.level() == CompilerDiagnostic.Level.ERROR).findFirst();
            return new Compiled(new Observation.Rejected(error.map(CompilerDiagnostic::code).orElse(null),
                    error.map(CompilerDiagnostic::message).orElse(e.getMessage())), 0);
        } catch (RuntimeException | StackOverflowError | OutOfMemoryError e) {
            // Running out of memory while compiling a small program is a compiler defect, such as
            // exponential growth; the harness reports it rather than dying with the test JVM.
            return new Compiled(new Observation.Crash("compiler: " + e), 0);
        }
        try {
            return new Compiled(program, UplcFlatEncoder.encodeProgram(program).length);
        } catch (RuntimeException | StackOverflowError | OutOfMemoryError e) {
            // julc-core's FLAT encoder, not the compiler: labelled apart so that triage looks in the right place.
            return new Compiled(new Observation.Crash("encoder: " + e), 0);
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
