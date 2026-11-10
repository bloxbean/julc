package org.julclang.vm.java.ndc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * The node-diversity {@code uplc-conformance} scenario on the Java VM: every vector of the pinned corpus, with the cost
 * model the corpus ships for its protocol and Plutus versions, scored against the scenario's expectations as the site
 * scores it. Run by {@code ./gradlew :julc-vm-java:ndcUplcConformance}, which gates releases.
 *
 * <p>Fails when any expectation is not met, unless the entry is in {@link #ALLOWED_MISMATCHES}, and when an allowed
 * mismatch has started to pass (so the list cannot go stale). The report and the summary are written either way.</p>
 */
class UplcConformanceNdcScenario {

    static final String SCENARIO = "uplc-conformance";

    /**
     * Expectations the VM is known not to meet, by {@link UplcConformanceScore#key}, each with the reason it is accepted
     * and the issue that tracks it. Adding an entry needs review: it lets a release ship with that difference.
     */
    static final Map<String, String> ALLOWED_MISMATCHES = Map.of();

    @Test
    void meetsTheScenarioExpectations() throws IOException {
        Path corpus = NdcCorpus.UPLC_CONFORMANCE.fetch(NdcReports.cacheDir());
        JsonNode expectations = NdcReports.JSON.readTree(
                NdcCorpus.UPLC_EXPECTATIONS.fetch(NdcReports.cacheDir()).toFile()).get("scenarios");
        List<UplcVector> vectors = UplcVector.list(corpus);
        Map<String, long[]> costModels = new HashMap<>();

        ObjectNode report = NdcReports.report(SCENARIO);
        ArrayNode entries = report.putArray("scenarios");
        long started = System.nanoTime();
        for (UplcVector vector : vectors) {
            long[] costModel = costModels.computeIfAbsent(vector.protocolMajor() + "/" + vector.language(),
                    k -> UplcEvaluation.costModel(corpus.resolve("cost-models/protocol-version-"
                            + vector.protocolMajor() + "/v" + vector.language() + ".json")));
            Path flat = vector.flat();
            UplcEvaluation.Outcome outcome = UplcEvaluation.evaluate(
                    Files.isRegularFile(flat) ? Files.readString(flat) : null, Files.readString(vector.program()),
                    Files.readString(vector.expected()), vector.language(), vector.protocolMajor(), costModel);
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
        NdcReports.writeSummary(SCENARIO, summary(vectors.size(), millis, expectations, result, stale));

        assertEquals(expectations.size(), vectors.size(),
                "the corpus and the expectations should hold the same vectors; check the two pins belong together");
        assertTrue(stale.isEmpty(), () -> "allowed mismatches that now pass; remove them from ALLOWED_MISMATCHES: "
                + stale);
        if (!unexpected.isEmpty()) {
            fail(unexpected.size() + " of " + result.total() + " uplc-conformance vectors differ from the "
                    + "scenario's expectations (first: " + describe(unexpected.getFirst()) + "). The summary lists "
                    + "them all.");
        }
    }

    private static String summary(int vectors, long millis, JsonNode expectations, UplcConformanceScore.Result result,
                                  List<String> stale) {
        StringBuilder out = new StringBuilder();
        out.append("uplc-conformance on the Java VM (julc ").append(System.getProperty("ndc.version", "dev"))
                .append(")\n");
        out.append("corpus ").append(NdcCorpus.UPLC_CONFORMANCE.sha256()).append(", expectations at ")
                .append(NdcCorpus.NDC_REVISION).append('\n');
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
