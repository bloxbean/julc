package org.julclang.difftest;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs a {@link DiffCase} once on the JVM and once per UPLC configuration, and compares each UPLC
 * observation with the JVM one (ADR-063 D2, D3).
 */
public final class DiffRunner {

    private final JvmExecutor jvm;
    private final UplcExecutor uplc = new UplcExecutor();
    private final List<DiffConfig> configs;

    public DiffRunner(List<DiffConfig> configs, long jvmTimeoutMillis) {
        this.configs = List.copyOf(configs);
        this.jvm = new JvmExecutor(jvmTimeoutMillis);
    }

    /** The pull-request matrix: every optimization level on the Java VM, with a 10-second JVM bound. */
    public static DiffRunner standard() {
        return new DiffRunner(DiffConfig.allLevels(), 10_000);
    }

    /** Compiles the JVM side of many cases at once; their classes must have distinct names. */
    public void precompile(List<DiffCase> cases) {
        jvm.precompile(cases);
    }

    public Report run(DiffCase diffCase) {
        Observation jvmObservation = jvm.run(diffCase);
        var results = new LinkedHashMap<DiffConfig, Result>();
        for (DiffConfig config : configs) {
            var execution = uplc.run(diffCase, config);
            Observation observation = execution.observation();
            results.put(config, new Result(jvmObservation, observation, Verdict.compare(jvmObservation, observation),
                    execution.measurement()));
        }
        return new Report(diffCase, jvmObservation, results);
    }

    /**
     * One UPLC observation, the JVM observation it was compared with, the verdict, and what the script
     * cost (null when nothing was evaluated). The verdict never depends on the measurement.
     */
    public record Result(Observation jvm, Observation uplc, Verdict verdict, UplcExecutor.Measurement measurement) {

        public Result(Observation jvm, Observation uplc, Verdict verdict) {
            this(jvm, uplc, verdict, null);
        }
    }

    /** Both sides of one case under every configuration. */
    public record Report(DiffCase diffCase, Observation jvm, Map<DiffConfig, Result> results) {

        public Report {
            results = Collections.unmodifiableMap(new LinkedHashMap<>(results));
        }

        /** True when every configuration's verdict has this kind. */
        public boolean all(Verdict.Kind kind) {
            return results.values().stream().allMatch(r -> r.verdict().kind() == kind);
        }

        public String describe() {
            var text = new StringBuilder(diffCase.name()).append("\n  JVM: ").append(jvm);
            results.forEach((config, result) -> text.append("\n  ").append(config).append(": ")
                    .append(result.verdict().kind()).append(" ").append(result.uplc())
                    .append(result.measurement() == null ? "" : " (" + result.measurement() + ")"));
            return text.toString();
        }
    }
}
