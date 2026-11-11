package org.julclang.vm.java.ndc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The node-diversity {@code uplc-conformance} scenario on the Java VM: every vector of the vendored corpus
 * ({@link UplcConformanceCorpus}), with the cost model the corpus ships for its protocol and Plutus versions, scored
 * against the scenario's expectations as the site scores it. It runs in {@code test} (so in {@code ./gradlew build})
 * and alone as {@code ./gradlew :julc-vm-java:ndcUplcConformance}, which gates releases. No network is used.
 *
 * <p>Fails when any expectation is not met, unless the entry is in {@link #ALLOWED_MISMATCHES}, and when an allowed
 * mismatch has started to pass (so the list cannot go stale). Also fails on JuLC's own checks, which the site's score
 * does not make ({@link #ALLOWED_PROBLEMS}): the Flat program alone gives what {@code .flat.expected} says, no
 * failure is a crash of the VM. It also reports, without failing, the vectors that the VM's default cost model of
 * their target prices differently from the corpus's cost model. The report and the summary are written either
 * way.</p>
 */
class UplcConformanceNdcScenario {

    static final String SCENARIO = "uplc-conformance";

    /**
     * Expectations the VM is known not to meet, by {@link UplcConformanceScore#key}, each with the reason it is
     * accepted and the issue that tracks it. Adding an entry needs review: it lets a release ship with that difference.
     */
    static final Map<String, String> ALLOWED_MISMATCHES = Map.of();

    /**
     * A problem of JuLC's own checks that is accepted for one vector: only that exact problem, so any other problem on
     * the vector (a crash above all) still fails the gate.
     *
     * @param problem the exact problem text, as {@link UplcEvaluation} reports it
     * @param reason  why it is accepted, and the issue that tracks it
     */
    record AllowedProblem(String problem, String reason) {
    }

    /**
     * Problems of JuLC's own checks ({@link UplcEvaluation}: the Flat program alone gives what {@code .flat.expected}
     * says; no failure is a crash of the VM) accepted for a vector, by {@link UplcConformanceScore#key}. Adding an entry
     * needs review.
     */
    static final Map<String, AllowedProblem> ALLOWED_PROBLEMS = Map.of(
            "term_constr_constr_07_v3 V3/11.0", new AllowedProblem(
                    UplcEvaluation.flatKindProblem(UplcEvaluation.Kind.FAILURE, UplcEvaluation.Kind.PARSE_ERROR),
                    "its text has the tag -5, which does not parse, and its .flat.expected repeats that outcome; its "
                            + "Flat program holds the tag 2^64 - 5, which fits Plutus's Word64 constr tag (Constr "
                            + "!ann !Word64), so it decodes, and its (error) field fails evaluation; the reported "
                            + "outcome then comes from the text, as the scenario expects"));

    /** @return every problem, as {@code key: problem}, that {@code allowed} does not accept exactly */
    static List<String> unexpectedProblems(Map<String, List<String>> problems, Map<String, AllowedProblem> allowed) {
        List<String> unexpected = new ArrayList<>();
        problems.forEach((key, list) -> list.stream()
                .filter(problem -> !isAllowed(allowed, key, problem))
                .forEach(problem -> unexpected.add(key + ": " + problem)));
        return unexpected;
    }

    /** @return the keys whose accepted problem no longer occurs */
    static List<String> staleProblems(Map<String, List<String>> problems, Map<String, AllowedProblem> allowed) {
        return allowed.entrySet().stream()
                .filter(e -> !problems.getOrDefault(e.getKey(), List.of()).contains(e.getValue().problem()))
                .map(Map.Entry::getKey).sorted().toList();
    }

    private static boolean isAllowed(Map<String, AllowedProblem> allowed, String key, String problem) {
        AllowedProblem accepted = allowed.get(key);
        return accepted != null && accepted.problem().equals(problem);
    }

    @Test
    void meetsTheScenarioExpectations() throws IOException {
        Path corpus = UplcConformanceCorpus.corpus();
        JsonNode expectations = NdcReports.JSON.readTree(UplcConformanceCorpus.expectations().toFile())
                .get("scenarios");
        List<UplcVector> vectors = UplcVector.list(corpus);
        Map<String, long[]> costModels = new HashMap<>();
        Map<String, String> scenarioEvaluations = new HashMap<>();
        expectations.forEach(e -> scenarioEvaluations.put(UplcConformanceScore.key(e), e.path("evaluation").asText()));

        ObjectNode report = NdcReports.report(SCENARIO);
        ArrayNode entries = report.putArray("scenarios");
        Map<String, List<String>> problems = new TreeMap<>();
        Map<String, int[]> defaultCostModels = new TreeMap<>();
        List<String> defaultCostModelDifferences = new ArrayList<>();
        int flatChecks = 0;
        long started = System.nanoTime();
        for (UplcVector vector : vectors) {
            String versions = "V" + vector.language() + " / protocol " + vector.protocolVersion();
            long[] costModel = costModels.computeIfAbsent(vector.protocolMajor() + "/" + vector.language(),
                    k -> UplcEvaluation.costModel(corpus.resolve("cost-models/protocol-version-"
                            + vector.protocolMajor() + "/v" + vector.language() + ".json")));
            String flat = readIfPresent(vector.flat());
            String flatExpected = flat == null ? null : readIfPresent(vector.flatExpected());
            if (flatExpected != null) {
                flatChecks++;
            }
            String text = Files.readString(vector.program());
            String expected = Files.readString(vector.expected());
            String key = vector.id() + " " + vector.plutusVersion() + "/" + vector.protocolVersion();
            UplcEvaluation.Outcome outcome = UplcEvaluation.evaluate(flat, flatExpected, text, expected,
                    scenarioEvaluations.get(key), vector.language(), vector.protocolMajor(), costModel);
            if (!outcome.problems().isEmpty()) {
                problems.put(key, outcome.problems());
            }
            if (outcome.budget() != null) {
                // Reported, not failed: the corpus's cost model of a protocol version can be newer than the VM's
                // default for it (protocol 11's parameters are not final), and the budgets above already use it.
                UplcEvaluation.Outcome withDefaults = UplcEvaluation.evaluate(flat, flatExpected, text, expected,
                        vector.language(), vector.protocolMajor(), null);
                int[] count = defaultCostModels.computeIfAbsent(versions, k -> new int[2]);
                count[1]++;
                if (outcome.budget().equals(withDefaults.budget())) {
                    count[0]++;
                } else {
                    defaultCostModelDifferences.add(key + ": " + withDefaults.budget() + " with the VM's default, "
                            + outcome.budget() + " with the corpus's");
                }
            }
            ObjectNode entry = entries.addObject()
                    .put("id", vector.id())
                    .put("title", vector.title())
                    .put("group", vector.group())
                    .put("plutus_version", vector.plutusVersion())
                    .put("protocol_version", vector.protocolVersion())
                    .put("evaluation", outcome.evaluation());
            if (outcome.budget() == null) {
                entry.putNull("budget");
            } else {
                entry.putObject("budget").put("cpu", outcome.budget().cpuSteps())
                        .put("mem", outcome.budget().memoryUnits());
            }
        }
        long millis = (System.nanoTime() - started) / 1_000_000;
        NdcReports.writeJson(SCENARIO, report);

        UplcConformanceScore.Result result = UplcConformanceScore.score(entries, expectations);
        List<UplcConformanceScore.Mismatch> unexpected = result.mismatches().stream()
                .filter(m -> !ALLOWED_MISMATCHES.containsKey(m.key())).toList();
        List<String> stale = ALLOWED_MISMATCHES.keySet().stream()
                .filter(key -> result.mismatches().stream().noneMatch(m -> m.key().equals(key))).sorted().toList();
        List<String> unexpectedProblems = unexpectedProblems(problems, ALLOWED_PROBLEMS);
        List<String> staleProblems = staleProblems(problems, ALLOWED_PROBLEMS);
        NdcReports.writeSummary(SCENARIO, summary(vectors.size(), millis, expectations, result, stale, flatChecks,
                defaultCostModels, defaultCostModelDifferences, problems, staleProblems));

        assertEquals(expectations.size(), vectors.size(),
                "the corpus and the expectations should hold the same vectors; check the two pins belong together");
        assertTrue(stale.isEmpty(), () -> "allowed mismatches that now pass; remove them from ALLOWED_MISMATCHES: "
                + stale);
        assertTrue(staleProblems.isEmpty(), () -> "allowed problems that no longer occur; remove them from "
                + "ALLOWED_PROBLEMS: " + staleProblems);
        if (!unexpected.isEmpty()) {
            fail(unexpected.size() + " of " + result.total() + " uplc-conformance vectors differ from the "
                    + "scenario's expectations (first: " + describe(unexpected.getFirst()) + "). The summary lists "
                    + "them all.");
        }
        if (!unexpectedProblems.isEmpty()) {
            fail(unexpectedProblems.size() + " problems of JuLC's own checks on the uplc-conformance vectors (first: "
                    + unexpectedProblems.getFirst() + "). The summary lists them all.");
        }
    }

    private static String readIfPresent(Path file) throws IOException {
        return Files.isRegularFile(file) ? Files.readString(file) : null;
    }

    private static String summary(int vectors, long millis, JsonNode expectations, UplcConformanceScore.Result result,
                                  List<String> stale, int flatChecks, Map<String, int[]> defaultCostModels,
                                  List<String> defaultCostModelDifferences, Map<String, List<String>> problems,
                                  List<String> staleProblems) {
        StringBuilder out = new StringBuilder();
        out.append("uplc-conformance on the Java VM (julc ").append(System.getProperty("ndc.version", "dev"))
                .append(")\n");
        out.append("corpus (vendored) from tarball ").append(UplcConformanceCorpus.TARBALL_SHA256)
                .append(", expectations at ").append(NdcCorpus.NDC_REVISION).append('\n');
        out.append(String.format("%d vectors evaluated in %d ms%n", vectors, millis));
        out.append(String.format("score: %d / %d (%.2f%%)%n", result.passed(), result.total(),
                100.0 * result.passed() / Math.max(1, result.total())));
        out.append(String.format("budgets: %d / %d of the vectors with an expected budget%n", result.budgetPassed(),
                result.budgeted()));
        out.append(String.format("mismatches: %d (%d allowed)%n%n", result.mismatches().size(),
                result.mismatches().stream().filter(m -> ALLOWED_MISMATCHES.containsKey(m.key())).count()));

        appendBreakdown(out, "by group", expectations, result, e -> topGroup(e.path("group").asText()));
        appendBreakdown(out, "by Plutus version", expectations, result, e -> e.path("plutus_version").asText());
        appendBreakdown(out, "by protocol version", expectations, result, e -> e.path("protocol_version").asText());

        if (!result.mismatches().isEmpty()) {
            out.append("\nmismatches:\n");
            for (UplcConformanceScore.Mismatch m : result.mismatches()) {
                out.append("  ").append(describe(m));
                String reason = ALLOWED_MISMATCHES.get(m.key());
                if (reason != null) {
                    out.append(" [allowed: ").append(reason).append(']');
                }
                out.append('\n');
            }
        }
        if (!stale.isEmpty()) {
            out.append("\nallowed mismatches that now pass: ").append(stale).append('\n');
        }

        out.append("\nJuLC's own checks (not part of the site's score):\n");
        out.append(String.format("  Flat programs checked against .flat.expected: %d%n", flatChecks));
        out.append("  results priced the same by the VM's default cost models and the corpus's (reported, not "
                + "failed):\n");
        defaultCostModels.forEach((name, count) -> out.append(String.format("    %-22s %5d / %d%n", name, count[0],
                count[1])));
        defaultCostModelDifferences.forEach(line -> out.append("      ").append(line).append('\n'));
        int problemCount = problems.values().stream().mapToInt(List::size).sum();
        out.append(String.format("  problems: %d on %d vectors (%d allowed)%n", problemCount, problems.size(),
                problemCount - unexpectedProblems(problems, ALLOWED_PROBLEMS).size()));
        problems.forEach((key, list) -> {
            out.append("    ").append(key).append('\n');
            list.forEach(problem -> {
                out.append("      ").append(oneLine(problem));
                if (isAllowed(ALLOWED_PROBLEMS, key, problem)) {
                    out.append(" [allowed: ").append(ALLOWED_PROBLEMS.get(key).reason()).append(']');
                }
                out.append('\n');
            });
        });
        if (!staleProblems.isEmpty()) {
            out.append("  allowed problems that no longer occur: ").append(staleProblems).append('\n');
        }
        return out.toString();
    }

    private static void appendBreakdown(StringBuilder out, String title, JsonNode expectations,
                                        UplcConformanceScore.Result result, Function<JsonNode, String> classifier) {
        Map<String, int[]> counts = new TreeMap<>();
        Set<String> failedKeys = new HashSet<>();
        result.mismatches().forEach(m -> failedKeys.add(m.key()));
        for (JsonNode expected : expectations) {
            int[] count = counts.computeIfAbsent(classifier.apply(expected), k -> new int[2]);
            count[1]++;
            if (!failedKeys.contains(UplcConformanceScore.key(expected))) {
                count[0]++;
            }
        }
        out.append(title).append(":\n");
        counts.forEach((name, count) -> out.append(String.format("  %-24s %5d / %d%n", name, count[0], count[1])));
    }

    private static String topGroup(String group) {
        int slash = group.indexOf('/');
        return slash < 0 ? group : group.substring(0, slash);
    }

    private static String describe(UplcConformanceScore.Mismatch m) {
        return m.key() + ": expected " + oneLine(m.expectedEvaluation()) + " " + m.expectedBudget() + ", got "
                + (m.actualEvaluation() == null ? "no entry" : oneLine(m.actualEvaluation()) + " " + m.actualBudget());
    }

    private static String oneLine(String text) {
        String line = text.replaceAll("\\s+", " ").strip();
        return line.length() > 200 ? line.substring(0, 200) + "..." : line;
    }
}
