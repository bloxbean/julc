package com.bloxbean.cardano.julc.benchmark.optimization;

import com.bloxbean.cardano.julc.clientlib.JulcScriptAdapter;
import com.bloxbean.cardano.julc.compiler.CompileResult;
import com.bloxbean.cardano.julc.compiler.CompilerOptions;
import com.bloxbean.cardano.julc.compiler.CompilerTarget;
import com.bloxbean.cardano.julc.compiler.JulcCompiler;
import com.bloxbean.cardano.julc.compiler.OptimizationLevel;
import com.bloxbean.cardano.julc.core.DefaultFun;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.core.Term;
import com.bloxbean.cardano.julc.core.flat.UplcFlatEncoder;
import com.bloxbean.cardano.julc.stdlib.StdlibRegistry;
import com.bloxbean.cardano.julc.vm.EvalOptions;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.julc.vm.ExBudget;
import com.bloxbean.cardano.julc.vm.JulcVm;
import com.bloxbean.cardano.julc.vm.JulcVmProvider;
import com.bloxbean.cardano.julc.vm.OptimizationCostProfile;
import com.bloxbean.cardano.julc.vm.java.JavaVmProvider;
import com.bloxbean.cardano.julc.vm.truffle.TruffleVmProvider;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Reproducible compiler-optimization comparison harness.
 *
 * <p>This is intentionally separate from the VM-throughput JMH benchmarks. It
 * compiles one source fixture at two optimizer levels, evaluates both with the
 * same pinned ledger cost parameters, and records exact FLAT size, CPU, memory,
 * traces, result class, structural metrics, applied rules, and script hash.
 */
public final class OptimizationBenchmarkRunner {

    private OptimizationBenchmarkRunner() {
    }

    public record Fixture(
            String id,
            String javaSource,
            String methodName,
            List<InputCase> cases) {
        public Fixture {
            requireText(id, "id");
            requireText(javaSource, "javaSource");
            requireText(methodName, "methodName");
            cases = List.copyOf(cases);
            if (cases.isEmpty()) throw new IllegalArgumentException("cases must not be empty");
        }
    }

    public record InputCase(String id, List<PlutusData> arguments) {
        public InputCase {
            requireText(id, "id");
            arguments = List.copyOf(arguments);
        }

        public static InputCase of(String id, PlutusData... arguments) {
            return new InputCase(id, List.of(arguments));
        }
    }

    /** Native UPLC arguments for research forms not yet exposed by typed Java/PIR. */
    public record TermInputCase(String id, List<Term> arguments) {
        public TermInputCase {
            requireText(id, "id");
            arguments = List.copyOf(arguments);
        }

        public static TermInputCase of(String id, Term... arguments) {
            return new TermInputCase(id, List.of(arguments));
        }
    }

    public record Backend(String id, Supplier<JulcVmProvider> providerFactory) {
        public Backend {
            requireText(id, "id");
            Objects.requireNonNull(providerFactory, "providerFactory");
        }

        public static Backend javaVm() {
            return new Backend("java", JavaVmProvider::new);
        }

        public static Backend truffleVm() {
            return new Backend("truffle", TruffleVmProvider::new);
        }
    }

    public record TermMetrics(int nodes, Map<DefaultFun, Integer> builtins) {
        public TermMetrics {
            builtins = Map.copyOf(builtins);
        }
    }

    public record ArtifactMeasurement(
            OptimizationLevel level,
            String targetId,
            String costProfileId,
            String costParameterHash,
            int flatBytes,
            String scriptHash,
            TermMetrics termMetrics,
            List<String> appliedRules) {
        public ArtifactMeasurement {
            appliedRules = List.copyOf(appliedRules);
        }
    }

    public enum Outcome {
        SUCCESS,
        FAILURE,
        BUDGET_EXHAUSTED
    }

    public record EvaluationMeasurement(
            String backend,
            String caseId,
            Outcome outcome,
            Term resultTerm,
            String failure,
            ExBudget budget,
            List<String> traces) {
        public EvaluationMeasurement {
            traces = List.copyOf(traces);
        }
    }

    public record Comparison(
            String fixtureId,
            ArtifactMeasurement baselineArtifact,
            ArtifactMeasurement candidateArtifact,
            List<EvaluationMeasurement> baselineEvaluations,
            List<EvaluationMeasurement> candidateEvaluations) {
        public Comparison {
            baselineEvaluations = List.copyOf(baselineEvaluations);
            candidateEvaluations = List.copyOf(candidateEvaluations);
        }

        /** Fail if result class/value or trace order differs for any backend/input pair. */
        public void verifyEquivalent() {
            if (baselineEvaluations.size() != candidateEvaluations.size()) {
                throw new AssertionError("Evaluation count differs for " + fixtureId);
            }
            for (int i = 0; i < baselineEvaluations.size(); i++) {
                var before = baselineEvaluations.get(i);
                var after = candidateEvaluations.get(i);
                if (!before.backend().equals(after.backend())
                        || !before.caseId().equals(after.caseId())) {
                    throw new AssertionError("Evaluation identity differs at index " + i);
                }
                if (before.outcome() != after.outcome()) {
                    throw new AssertionError("Outcome differs for " + before.backend()
                            + "/" + before.caseId() + ": " + before.outcome()
                            + " vs " + after.outcome());
                }
                if (!Objects.equals(before.resultTerm(), after.resultTerm())) {
                    throw new AssertionError("Result differs for " + before.backend()
                            + "/" + before.caseId());
                }
                if (!Objects.equals(before.failure(), after.failure())) {
                    throw new AssertionError("Failure differs for " + before.backend()
                            + "/" + before.caseId() + ": " + before.failure()
                            + " vs " + after.failure());
                }
                if (!before.traces().equals(after.traces())) {
                    throw new AssertionError("Trace order differs for " + before.backend()
                            + "/" + before.caseId() + ": " + before.traces()
                            + " vs " + after.traces());
                }
            }
        }

        /** Render deterministic, release-note-ready artifact and ledger-budget tables. */
        public String toMarkdown() {
            var before = baselineArtifact;
            var after = candidateArtifact;
            var markdown = new StringBuilder()
                    .append("### ").append(fixtureId).append("\n\n")
                    .append("Target: `").append(after.targetId()).append("`; cost profile: `")
                    .append(after.costProfileId()).append("` (`")
                    .append(after.costParameterHash()).append("`).\n\n")
                    .append("Baseline script hash: `").append(before.scriptHash())
                    .append("`; candidate script hash: `").append(after.scriptHash())
                    .append("`.\n\n")
                    .append("| Metric | Baseline | Candidate | Delta |\n")
                    .append("|---|---:|---:|---:|\n")
                    .append("| FLAT bytes | ").append(before.flatBytes()).append(" | ")
                    .append(after.flatBytes()).append(" | ")
                    .append(signed(after.flatBytes() - before.flatBytes())).append(" |\n")
                    .append("| UPLC term nodes | ").append(before.termMetrics().nodes()).append(" | ")
                    .append(after.termMetrics().nodes()).append(" | ")
                    .append(signed(after.termMetrics().nodes() - before.termMetrics().nodes()))
                    .append(" |\n\n")
                    .append("| Backend | Case | Outcome | CPU baseline | CPU candidate | CPU delta | Memory baseline | Memory candidate | Memory delta |\n")
                    .append("|---|---|---|---:|---:|---:|---:|---:|---:|\n");
            for (int i = 0; i < baselineEvaluations.size(); i++) {
                var baseline = baselineEvaluations.get(i);
                var candidate = candidateEvaluations.get(i);
                markdown.append("| ").append(baseline.backend()).append(" | ")
                        .append(baseline.caseId()).append(" | ")
                        .append(candidate.outcome()).append(" | ")
                        .append(baseline.budget().cpuSteps()).append(" | ")
                        .append(candidate.budget().cpuSteps()).append(" | ")
                        .append(signed(candidate.budget().cpuSteps()
                                - baseline.budget().cpuSteps())).append(" | ")
                        .append(baseline.budget().memoryUnits()).append(" | ")
                        .append(candidate.budget().memoryUnits()).append(" | ")
                        .append(signed(candidate.budget().memoryUnits()
                                - baseline.budget().memoryUnits())).append(" |\n");
            }
            markdown.append("\nApplied candidate rules: ")
                    .append(after.appliedRules().isEmpty()
                            ? "none"
                            : after.appliedRules().stream()
                                    .map(rule -> "`" + rule + "`")
                                    .reduce((left, right) -> left + ", " + right)
                                    .orElse("none"))
                    .append(".\n");
            return markdown.toString();
        }

        private static String signed(long delta) {
            return delta > 0 ? "+" + delta : Long.toString(delta);
        }
    }

    /** Compare BASELINE with a candidate optimizer level under selected backends. */
    public static Comparison compare(
            Fixture fixture,
            OptimizationLevel candidateLevel,
            OptimizationCostProfile costProfile,
            List<Backend> backends) {
        Objects.requireNonNull(fixture, "fixture");
        Objects.requireNonNull(candidateLevel, "candidateLevel");
        Objects.requireNonNull(costProfile, "costProfile");
        backends = List.copyOf(backends);
        if (backends.isEmpty()) throw new IllegalArgumentException("backends must not be empty");

        var baseline = compile(fixture, OptimizationLevel.BASELINE, costProfile);
        var candidate = compile(fixture, candidateLevel, costProfile);
        var baselineEvals = evaluate(fixture, baseline, costProfile, backends);
        var candidateEvals = evaluate(fixture, candidate, costProfile, backends);

        var comparison = new Comparison(
                fixture.id(),
                measureArtifact(baseline, costProfile),
                measureArtifact(candidate, costProfile),
                baselineEvals,
                candidateEvals);
        comparison.verifyEquivalent();
        return comparison;
    }

    public static Comparison compareWithJavaAndTruffle(
            Fixture fixture,
            OptimizationLevel candidateLevel,
            OptimizationCostProfile costProfile) {
        return compare(fixture, candidateLevel, costProfile,
                List.of(Backend.javaVm(), Backend.truffleVm()));
    }

    /**
     * Compare two explicit Java source shapes at BASELINE for design research.
     * Non-equivalence is returned to the caller as evidence instead of being
     * accepted as a compiler rule.
     */
    public static Comparison compareResearchFixturesWithJavaAndTruffle(
            String fixtureId,
            Fixture baselineFixture,
            Fixture candidateFixture,
            String candidateRule,
            OptimizationCostProfile costProfile) {
        requireText(fixtureId, "fixtureId");
        requireText(candidateRule, "candidateRule");
        requireMatchingInputs(baselineFixture.cases(), candidateFixture.cases());
        var baseline = compile(baselineFixture, OptimizationLevel.BASELINE, costProfile);
        var candidate = compile(candidateFixture, OptimizationLevel.BASELINE, costProfile);
        var backends = List.of(Backend.javaVm(), Backend.truffleVm());
        var candidateArtifact = measureArtifact(candidate, costProfile);
        candidateArtifact = new ArtifactMeasurement(
                candidateArtifact.level(),
                candidateArtifact.targetId(),
                candidateArtifact.costProfileId(),
                candidateArtifact.costParameterHash(),
                candidateArtifact.flatBytes(),
                candidateArtifact.scriptHash(),
                candidateArtifact.termMetrics(),
                append(candidateArtifact.appliedRules(), candidateRule));
        return new Comparison(
                fixtureId,
                measureArtifact(baseline, costProfile),
                candidateArtifact,
                evaluate(baselineFixture, baseline, costProfile, backends),
                evaluate(candidateFixture, candidate, costProfile, backends));
    }

    /**
     * Compare two typed-by-construction UPLC templates under native constant
     * arguments. This is for ADR research only; it does not authorize an
     * untyped compiler rewrite.
     */
    public static Comparison compareTermsWithJavaAndTruffle(
            String fixtureId,
            Term baselineTemplate,
            Term candidateTemplate,
            List<TermInputCase> cases,
            String candidateRule,
            OptimizationCostProfile costProfile) {
        return compareTerms(
                fixtureId, baselineTemplate, candidateTemplate, cases,
                candidateRule, costProfile, true);
    }

    /** Return deliberately non-equivalent research evidence for a deferred rule. */
    public static Comparison compareResearchTermsWithJavaAndTruffle(
            String fixtureId,
            Term baselineTemplate,
            Term candidateTemplate,
            List<TermInputCase> cases,
            String candidateRule,
            OptimizationCostProfile costProfile) {
        return compareTerms(
                fixtureId, baselineTemplate, candidateTemplate, cases,
                candidateRule, costProfile, false);
    }

    private static Comparison compareTerms(
            String fixtureId,
            Term baselineTemplate,
            Term candidateTemplate,
            List<TermInputCase> cases,
            String candidateRule,
            OptimizationCostProfile costProfile,
            boolean requireEquivalent) {
        requireText(fixtureId, "fixtureId");
        Objects.requireNonNull(baselineTemplate, "baselineTemplate");
        Objects.requireNonNull(candidateTemplate, "candidateTemplate");
        cases = List.copyOf(cases);
        if (cases.isEmpty()) throw new IllegalArgumentException("cases must not be empty");
        requireText(candidateRule, "candidateRule");

        var baseline = Program.plutusV3(baselineTemplate);
        var candidate = Program.plutusV3(candidateTemplate);
        var backends = List.of(Backend.javaVm(), Backend.truffleVm());
        var comparison = new Comparison(
                fixtureId,
                measureArtifact(baseline, OptimizationLevel.BASELINE,
                        List.of(), costProfile),
                measureArtifact(candidate, OptimizationLevel.PV11_SAFE,
                        List.of(candidateRule), costProfile),
                evaluateTerms(baseline, cases, costProfile, backends),
                evaluateTerms(candidate, cases, costProfile, backends));
        if (requireEquivalent) comparison.verifyEquivalent();
        return comparison;
    }

    private static CompileResult compile(
            Fixture fixture,
            OptimizationLevel level,
            OptimizationCostProfile costProfile) {
        var options = new CompilerOptions().setOptimizationLevel(level);
        if (level.costProfileRequired()) {
            options.setOptimizationCostProfile(costProfile);
        }
        return new JulcCompiler(StdlibRegistry.defaultRegistry(), options)
                .compileMethod(fixture.javaSource(), fixture.methodName());
    }

    private static List<EvaluationMeasurement> evaluate(
            Fixture fixture,
            CompileResult compiled,
            OptimizationCostProfile costProfile,
            List<Backend> backends) {
        var measurements = new ArrayList<EvaluationMeasurement>();
        for (var backend : backends) {
            var vm = JulcVm.withProvider(
                    backend.providerFactory().get(),
                    compiled.target().ledgerTarget().ledgerLanguage());
            vm.setCostModelParams(costProfile.costModelParameters(), costProfile.target());
            for (var input : fixture.cases()) {
                var result = vm.evaluateWithArgs(
                        compiled.program(),
                        compiled.target().ledgerTarget(),
                        input.arguments(),
                        null,
                        EvalOptions.DEFAULT.withBuiltinTrace(false));
                measurements.add(measure(backend.id(), input.id(), result));
            }
        }
        return measurements;
    }

    private static ArtifactMeasurement measureArtifact(
            CompileResult compiled,
            OptimizationCostProfile costProfile) {
        return new ArtifactMeasurement(
                compiled.optimizationReport().level(),
                compiled.target().profileId(),
                costProfile.profileId(),
                costProfile.parameterHash(),
                compiled.scriptSizeBytes(),
                JulcScriptAdapter.scriptHash(compiled.program()),
                metrics(compiled.program().term()),
                compiled.optimizationReport().appliedRules());
    }

    private static ArtifactMeasurement measureArtifact(
            Program program,
            OptimizationLevel level,
            List<String> appliedRules,
            OptimizationCostProfile costProfile) {
        return new ArtifactMeasurement(
                level,
                CompilerTarget.PLUTUS_V3_PV11.profileId(),
                costProfile.profileId(),
                costProfile.parameterHash(),
                UplcFlatEncoder.encodeProgram(program).length,
                JulcScriptAdapter.scriptHash(program),
                metrics(program.term()),
                appliedRules);
    }

    private static List<EvaluationMeasurement> evaluateTerms(
            Program template,
            List<TermInputCase> cases,
            OptimizationCostProfile costProfile,
            List<Backend> backends) {
        var measurements = new ArrayList<EvaluationMeasurement>();
        for (var backend : backends) {
            var vm = JulcVm.withProvider(
                    backend.providerFactory().get(),
                    costProfile.target().ledgerLanguage());
            vm.setCostModelParams(costProfile.costModelParameters(), costProfile.target());
            for (var input : cases) {
                var applied = template.term();
                for (var argument : input.arguments()) {
                    applied = Term.apply(applied, argument);
                }
                var program = new Program(
                        template.major(), template.minor(), template.patch(), applied);
                var result = vm.evaluate(
                        program,
                        costProfile.target(),
                        null,
                        EvalOptions.DEFAULT.withBuiltinTrace(false));
                measurements.add(measure(backend.id(), input.id(), result));
            }
        }
        return measurements;
    }

    private static EvaluationMeasurement measure(
            String backend,
            String caseId,
            EvalResult result) {
        return switch (result) {
            case EvalResult.Success success -> new EvaluationMeasurement(
                    backend, caseId, Outcome.SUCCESS, success.resultTerm(), null,
                    success.consumed(), success.traces());
            case EvalResult.Failure failure -> new EvaluationMeasurement(
                    backend, caseId, Outcome.FAILURE, null, failure.error(),
                    failure.consumed(), failure.traces());
            case EvalResult.BudgetExhausted exhausted -> new EvaluationMeasurement(
                    backend, caseId, Outcome.BUDGET_EXHAUSTED, null,
                    "budget exhausted", exhausted.consumed(), exhausted.traces());
        };
    }

    private static void requireMatchingInputs(
            List<InputCase> baseline,
            List<InputCase> candidate) {
        if (baseline.size() != candidate.size()) {
            throw new IllegalArgumentException("research fixture input counts differ");
        }
        for (int i = 0; i < baseline.size(); i++) {
            if (!baseline.get(i).equals(candidate.get(i))) {
                throw new IllegalArgumentException(
                        "research fixture input differs at index " + i);
            }
        }
    }

    private static List<String> append(List<String> values, String value) {
        var result = new ArrayList<>(values);
        result.add(value);
        return List.copyOf(result);
    }

    static TermMetrics metrics(Term term) {
        var builtins = new EnumMap<DefaultFun, Integer>(DefaultFun.class);
        return new TermMetrics(count(term, builtins), builtins);
    }

    private static int count(Term term, EnumMap<DefaultFun, Integer> builtins) {
        return switch (term) {
            case Term.Var _, Term.Const _, Term.Error() -> 1;
            case Term.Builtin(var fun) -> {
                builtins.merge(fun, 1, Integer::sum);
                yield 1;
            }
            case Term.Lam(_, var body) -> 1 + count(body, builtins);
            case Term.Delay(var body) -> 1 + count(body, builtins);
            case Term.Force(var body) -> 1 + count(body, builtins);
            case Term.Apply(var function, var argument) ->
                    1 + count(function, builtins) + count(argument, builtins);
            case Term.Constr(_, var fields) ->
                    1 + fields.stream().mapToInt(field -> count(field, builtins)).sum();
            case Term.Case(var scrutinee, var branches) ->
                    1 + count(scrutinee, builtins)
                            + branches.stream().mapToInt(branch -> count(branch, builtins)).sum();
        };
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
