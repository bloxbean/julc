package org.julclang.vm.java.ndc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.julclang.core.Program;
import org.julclang.core.flat.UplcFlatDecoder;
import org.julclang.vm.EvalResult;
import org.julclang.vm.ExBudget;
import org.julclang.vm.LedgerEvaluationTarget;
import org.julclang.vm.ProtocolFeatureRegistry;
import org.julclang.vm.java.JavaVmProvider;
import org.julclang.vm.java.PreparedProgram;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The node-diversity {@code uplc-turbo} scenario on the Java VM: every mainnet script of the pinned corpus, decoded
 * before timing, evaluated with the VM's default cost model of the script's protocol and Plutus versions, and timed as
 * {@link TurboMeasurement} describes. Run on demand by {@code ./gradlew :julc-vm-java:ndcUplcTurbo}; it never gates a
 * build or a release.
 *
 * <p>Timings depend on the machine, so scripts over the site's time or allocation thresholds ({@link TurboScore}) are
 * reported, never failed. The run fails only on what is not a matter of speed: a script that does not decode or does
 * not evaluate successfully (each one succeeded on mainnet), or a consumed budget that differs from cardano-node's.
 * The report and the summary are written either way.</p>
 */
class UplcTurboNdcScenario {

    static final String SCENARIO = "uplc-turbo";

    /** One measured script, with the reference reports' entries for it. */
    record Row(TurboSample sample, TurboMeasurement measurement, JsonNode cardanoNode, JsonNode amaru) {

        ExBudget budget() {
            return measurement.budget();
        }

        boolean cpuPasses() {
            return TurboScore.cpuPasses(measurement.cpuUs(), budget().cpuSteps());
        }

        boolean memPasses() {
            return TurboScore.memPasses(measurement.memBytes(), budget().memoryUnits());
        }

        boolean budgetAgrees() {
            return cardanoNode != null && cardanoNode.path("budget_cpu").asLong() == budget().cpuSteps()
                    && cardanoNode.path("budget_mem").asLong() == budget().memoryUnits();
        }

        boolean passes() {
            return measurement.succeeded() && cpuPasses() && memPasses() && budgetAgrees();
        }
    }

    @Test
    void measuresTheMainnetScripts() throws IOException {
        Path corpus = NdcCorpus.TURBO.fetch(NdcReports.cacheDir());
        Map<String, JsonNode> cardanoNode = benchmarks(NdcCorpus.TURBO_CARDANO_NODE);
        Map<String, JsonNode> amaru = benchmarks(NdcCorpus.TURBO_AMARU);
        String filter = System.getProperty("ndc.turbo.filter", "");
        List<TurboSample> all = TurboSample.list(corpus);
        assertTrue(all.size() == cardanoNode.size(), () -> "the corpus holds " + all.size()
                + " scripts and cardano-node's report " + cardanoNode.size() + "; check the pins belong together");

        JavaVmProvider provider = new JavaVmProvider();
        List<String> undecoded = new ArrayList<>();
        List<TurboSample> samples = new ArrayList<>();
        List<PreparedProgram> programs = new ArrayList<>();
        for (TurboSample sample : all) {
            try {
                programs.add(provider.prepare(decode(sample), target(sample)));
                samples.add(sample);
            } catch (RuntimeException e) {
                undecoded.add(sample.bench() + ": " + e.getMessage());
            }
        }
        long started = System.nanoTime();
        programs.forEach(TurboMeasurement::evaluate);
        long warmupMillis = (System.nanoTime() - started) / 1_000_000;

        ObjectNode report = NdcReports.report(SCENARIO);
        ((ObjectNode) report.get("implementation")).put("language", "Java");
        report.set("machine", NdcReports.machine());
        ArrayNode benchmarks = report.putArray("benchmarks");
        List<Row> rows = new ArrayList<>();
        started = System.nanoTime();
        for (int i = 0; i < samples.size(); i++) {
            TurboSample sample = samples.get(i);
            if (!sample.bench().startsWith(filter)) {
                continue;
            }
            TurboMeasurement m = TurboMeasurement.measure(programs.get(i));
            rows.add(new Row(sample, m, cardanoNode.get(sample.bench()), amaru.get(sample.bench())));
            if (!m.succeeded()) {
                // Left out of the report: the scenario counts a missing benchmark as failed.
                continue;
            }
            benchmarks.addObject()
                    .put("bench", sample.bench())
                    .put("plutus_version", sample.plutusVersion())
                    .put("protocol_version", sample.protocolVersion())
                    .put("budget_cpu", m.budget().cpuSteps())
                    .put("budget_mem", m.budget().memoryUnits())
                    .put("cpu_us", m.cpuUs())
                    .put("mem_bytes", m.memBytes());
        }
        long measureMillis = (System.nanoTime() - started) / 1_000_000;
        NdcReports.writeJson(SCENARIO, report);

        List<Row> failed = rows.stream().filter(r -> !r.measurement().succeeded()).toList();
        List<Row> disagreeing = rows.stream().filter(r -> r.measurement().succeeded() && !r.budgetAgrees()).toList();
        NdcReports.writeSummary(SCENARIO, summary(all.size(), filter, rows, undecoded, failed, disagreeing,
                warmupMillis, measureMillis));

        if (!undecoded.isEmpty() || !failed.isEmpty() || !disagreeing.isEmpty()) {
            fail(undecoded.size() + " scripts did not decode, " + failed.size() + " did not evaluate successfully and "
                    + disagreeing.size() + " consumed a budget other than cardano-node's. The summary lists them.");
        }
    }

    private static String summary(int corpusSize, String filter, List<Row> rows, List<String> undecoded,
                                  List<Row> failed, List<Row> disagreeing, long warmupMillis, long measureMillis) {
        JsonNode machine = NdcReports.machine();
        StringBuilder out = new StringBuilder();
        out.append("uplc-turbo on the Java VM (julc ").append(System.getProperty("ndc.version", "dev")).append(")\n");
        out.append("corpus ").append(NdcCorpus.TURBO.sha256()).append(", reference reports at ")
                .append(NdcCorpus.NDC_REVISION).append('\n');
        out.append("machine: ").append(machine.path("cpu").asText()).append(", ")
                .append(machine.path("logical_cores").asInt()).append(" logical cores, ")
                .append(machine.path("os").asText()).append(' ').append(machine.path("os_version").asText())
                .append(' ').append(machine.path("architecture").asText()).append(", Java ")
                .append(System.getProperty("java.version")).append(' ').append(System.getProperty("java.vm.name"))
                .append('\n');
        out.append(String.format("warm-up pass %d ms, measurement %d ms%n", warmupMillis, measureMillis));
        if (!filter.isEmpty()) {
            out.append("filter: scripts starting with ").append(filter).append('\n');
        }
        long passed = rows.stream().filter(Row::passes).count();
        out.append(String.format("score: %d / %d (%d scripts in the corpus)%n", passed, rows.size(), corpusSize));
        out.append(String.format("  did not decode: %d, evaluation failures: %d, budgets differing from "
                + "cardano-node: %d%n", undecoded.size(), failed.size(), disagreeing.size()));
        List<Row> measured = rows.stream().filter(r -> r.measurement().succeeded()).toList();
        out.append(String.format("  CPU over 2x budget: %d, allocation over 2x budget: %d%n",
                measured.stream().filter(r -> !r.cpuPasses()).count(),
                measured.stream().filter(r -> !r.memPasses()).count()));
        out.append(String.format("  mean cpu_us %.1f, mean mem_bytes %.0f%n%n",
                measured.stream().mapToDouble(r -> r.measurement().cpuUs()).average().orElse(0),
                measured.stream().mapToLong(r -> r.measurement().memBytes()).average().orElse(0)));

        Map<String, int[]> byVersion = new TreeMap<>();
        for (Row row : rows) {
            int[] count = byVersion.computeIfAbsent(row.sample().plutusVersion() + " / protocol "
                    + row.sample().protocolVersion(), k -> new int[2]);
            count[1]++;
            if (row.passes()) {
                count[0]++;
            }
        }
        out.append("by Plutus and protocol version:\n");
        byVersion.forEach((name, count) -> out.append(String.format("  %-22s %5d / %d%n", name, count[0], count[1])));

        appendRows(out, "CPU over 2x budget (cpu_us > 2 x budget_cpu / 1e6, beyond 200 us)",
                measured.stream().filter(r -> !r.cpuPasses()).toList());
        appendRows(out, "allocation over 2x budget (mem_bytes > 2 x budget_mem, beyond 320 KiB)",
                measured.stream().filter(r -> !r.memPasses()).toList());
        if (!undecoded.isEmpty()) {
            out.append("\ndid not decode:\n");
            undecoded.forEach(line -> out.append("  ").append(line).append('\n'));
        }
        if (!failed.isEmpty()) {
            out.append("\nevaluation failures:\n");
            failed.forEach(r -> out.append("  ").append(r.sample().bench()).append(": ")
                    .append(r.measurement().result() instanceof EvalResult.Failure f ? f.error() : "out of budget")
                    .append('\n'));
        }
        if (!disagreeing.isEmpty()) {
            out.append("\nbudgets differing from cardano-node:\n");
            disagreeing.forEach(r -> out.append(String.format("  %s (%s, protocol %s): julc cpu %d mem %d, "
                            + "cardano-node %s%n", r.sample().bench(), r.sample().plutusVersion(),
                    r.sample().protocolVersion(), r.budget().cpuSteps(), r.budget().memoryUnits(),
                    r.cardanoNode() == null ? "no entry" : "cpu " + r.cardanoNode().path("budget_cpu").asLong()
                            + " mem " + r.cardanoNode().path("budget_mem").asLong())));
        }
        return out.toString();
    }

    private static void appendRows(StringBuilder out, String title, List<Row> rows) {
        out.append('\n').append(title).append(": ").append(rows.size()).append('\n');
        if (rows.isEmpty()) {
            return;
        }
        out.append(String.format("  %-20s %-4s %-5s %13s %9s %12s %7s %12s %7s | %12s %12s | %12s %12s%n",
                "bench", "lang", "proto", "budget_cpu", "budget_mem", "cpu_us", "x cpu", "mem_bytes", "x mem",
                "node cpu_us", "node mem", "amaru cpu_us", "amaru mem"));
        for (Row r : rows) {
            ExBudget budget = r.budget();
            TurboMeasurement m = r.measurement();
            out.append(String.format("  %-20s %-4s %-5s %13d %9d %12.1f %7.2f %12d %7.2f | %12s %12s | %12s %12s%n",
                    r.sample().bench().substring(0, Math.min(20, r.sample().bench().length())),
                    r.sample().plutusVersion(), r.sample().protocolVersion(), budget.cpuSteps(), budget.memoryUnits(),
                    m.cpuUs(), m.cpuUs() / (budget.cpuSteps() / 1_000_000.0), m.memBytes(),
                    (double) m.memBytes() / budget.memoryUnits(),
                    field(r.cardanoNode(), "cpu_us"), field(r.cardanoNode(), "mem_bytes"),
                    field(r.amaru(), "cpu_us"), field(r.amaru(), "mem_bytes")));
        }
        out.append("  full names:\n");
        rows.forEach(r -> out.append("    ").append(r.sample().bench()).append('\n'));
    }

    private static String field(JsonNode entry, String name) {
        return entry == null || !entry.hasNonNull(name) ? "-" : entry.get(name).asText();
    }

    private static Map<String, JsonNode> benchmarks(NdcCorpus reference) throws IOException {
        JsonNode report = NdcReports.JSON.readTree(reference.fetch(NdcReports.cacheDir()).toFile());
        Map<String, JsonNode> byBench = new HashMap<>();
        report.path("benchmarks").forEach(entry -> byBench.put(entry.path("bench").asText(), entry));
        return byBench;
    }

    /** Decodes the Flat program with the decoding limits of the script's protocol and Plutus versions. */
    private static Program decode(TurboSample sample) {
        return UplcFlatDecoder.decodeProgram(sample.flat(),
                ProtocolFeatureRegistry.resolve(target(sample)).decodeLimits().toFlatDecodeLimits());
    }

    private static LedgerEvaluationTarget target(TurboSample sample) {
        return UplcEvaluation.target(sample.language(), sample.protocolMajor());
    }
}
