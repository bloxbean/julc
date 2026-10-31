package org.julclang.difftest;

import org.julclang.vm.corpus.EvaluationRecorder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-068 M0's evaluation replay: it restores the recorded VM configuration (F315) and compares the failure
 * message's digest (F312). The evaluations are recorded by {@link Adr068EvaluationProbe} in a child JVM.
 */
class Adr068EvaluationsTest {

    @Test
    void replayRestoresTheConfigurationAndComparesFailures(@TempDir Path temp) throws Exception {
        Path corpus = temp.resolve("corpus");
        String output = run(corpus);
        assertEquals("pv10 Failure\npv11 Success\nhanded Failure\n", output);
        var byConfig = new LinkedHashMap<String, Adr068Evaluations.Evaluation>();
        try (Stream<Path> list = Files.list(corpus.resolve("evaluations"))) {
            for (Path dir : list.sorted().toList()) {
                var e = Adr068Evaluations.read(dir);
                byConfig.put(String.join(",", e.configs()), e);
            }
        }
        assertEquals(3, byConfig.size(), byConfig.keySet().toString());
        var pv10 = byConfig.get("default");
        var handed = byConfig.get("unknown");
        var pv11 = byConfig.entrySet().stream().filter(e -> e.getKey().startsWith("PLUTUS_V3 11.0 "))
                .map(java.util.Map.Entry::getValue).findFirst().orElseThrow();
        assertEquals("Failure", pv10.field("outcome"));
        assertEquals("Success", pv11.field("outcome"));
        assertEquals(pv10.field("program"), pv11.field("program"));

        // F315: each is replayed under its own configuration, so both agree, budgets included.
        assertEquals("agree", Adr068Evaluations.verdict(pv10, Adr068EvaluationProbe.EXP_MOD, corpus));
        assertEquals("agree", Adr068Evaluations.verdict(pv11, Adr068EvaluationProbe.EXP_MOD, corpus));
        // An evaluation on a provider the JulcVm was handed has no known configuration and is not replayed.
        assertFalse(Adr068Evaluations.configurationKnown(handed));
        assertTrue(Adr068Evaluations.configurationKnown(pv10));

        // F312: changing only the recorded failure digest is a disagreement; so is changing only the traces.
        assertEquals("DISAGREE", Adr068Evaluations.verdict(with(pv10, "error", "0000000000000000"),
                Adr068EvaluationProbe.EXP_MOD, corpus));
        assertEquals("DISAGREE", Adr068Evaluations.verdict(with(pv10, "traces", "1 0000000000000000"),
                Adr068EvaluationProbe.EXP_MOD, corpus));
    }

    private static Adr068Evaluations.Evaluation with(Adr068Evaluations.Evaluation e, String field, String value) {
        var fields = new LinkedHashMap<>(e.fields());
        assertTrue(fields.containsKey(field), field);
        fields.put(field, value);
        return new Adr068Evaluations.Evaluation(e.key(), fields, e.args(), e.configs(), e.origins());
    }

    private static String run(Path corpus) throws IOException, InterruptedException {
        var command = new ArrayList<String>();
        command.add(ProcessHandle.current().info().command().orElse("java"));
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add("-D" + EvaluationRecorder.PROPERTY + "=" + corpus);
        command.add(Adr068EvaluationProbe.class.getName());
        var process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(5, TimeUnit.MINUTES));
        assertEquals(0, process.exitValue(), output);
        return output;
    }
}
