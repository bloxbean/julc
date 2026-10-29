package org.julclang.difftest;

import org.julclang.compiler.OptimizationLevel;
import org.julclang.core.PlutusData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The saved-case format: pins, provenance and recorded history (ADR-063 D6, D8, H4). */
class RegressionStoreTest {

    private static final String SOURCE = "public class Probe { public static long m(long n) { return n; } }\n";
    private static final DiffCase CASE = new DiffCase("identity", SOURCE, "Probe", "m",
            List.of(new PlutusData.IntData(BigInteger.TWO)));

    private static Observation value(long v, String... traces) {
        return new Observation.Value(new PlutusData.IntData(BigInteger.valueOf(v)), List.of(traces));
    }

    @Test
    void aFailureRecordRoundTripsWithItsProvenanceAndObservations(@TempDir Path dir) {
        var config = new DiffConfig(OptimizationLevel.BASELINE, "Java");
        var jvm = value(2, "line one\nline two");
        var uplc = new Observation.Failure("Plutus error", List.of("line one\nline two"));
        var report = new DiffRunner.Report(CASE, jvm, Map.of(config,
                new DiffRunner.Result(jvm, uplc, Verdict.compare(jvm, uplc), new UplcExecutor.Measurement(10, 20, 30))));
        var provenance = new LinkedHashMap<String, String>();
        provenance.put("by", "G0PropertyTest");
        provenance.put("generator", "G0 r1");
        provenance.put("plan", "1,2,3");
        provenance.put("compiler", "0.1.0-pre20-SNAPSHOT abc123");
        Path file = dir.resolve("case.diffcase");
        RegressionStore.write(file, CASE, RegressionStore.Expectation.AGREE, "a note", provenance, report);
        RegressionStore.addProvenance(file, "seed", "-42");

        var saved = RegressionStore.read(file);
        assertEquals(CASE, saved.diffCase());
        assertEquals(RegressionStore.Expectation.AGREE, saved.expectation());
        assertEquals("a note", saved.note());
        var expectedProvenance = new LinkedHashMap<>(provenance);
        expectedProvenance.put("seed", "-42");
        assertEquals(expectedProvenance, saved.provenance());
        assertEquals(jvm, ObservationText.parse(saved.recorded().get("jvm")));
        assertEquals(uplc, ObservationText.parse(saved.recorded().get("Java/BASELINE")));
        assertEquals("cpu 10 mem 20 size 30", saved.recorded().get("Java/BASELINE.cost"));
    }

    @Test
    void aDivergenceRoundTripsWithItsPins(@TempDir Path dir) {
        var expectation = RegressionStore.Expectation.parse("DIVERGE T12 Value/Value").pinned(value(1), value(1, "checked"));
        Path file = dir.resolve("case.diffcase");
        RegressionStore.write(file, CASE, expectation, null, Map.of(), null);
        var saved = RegressionStore.read(file);
        assertEquals(expectation, saved.expectation());
        assertEquals(expectation, saved.expectationFor(OptimizationLevel.PV11_SAFE));
    }

    @Test
    void perLevelPinsOverrideTheDefault(@TempDir Path dir) throws IOException {
        Path file = write(dir, "expect: DIVERGE T12 Value/Value\njvm: Value 01 []\nuplc: Value 01 [\"checked\"]\n"
                + "uplc.PV11_SAFE: Value 01 [\"other\"]\n");
        var saved = RegressionStore.read(file);
        assertEquals(value(1, "checked"), saved.expectationFor(OptimizationLevel.NONE).uplc());
        assertEquals(value(1, "other"), saved.expectationFor(OptimizationLevel.PV11_SAFE).uplc());
    }

    @Test
    void invalidHeadersAreRejected(@TempDir Path dir) throws IOException {
        // A divergence must pin both sides, with the kinds it names, and failure details are not pinned.
        assertRejected(dir, "expect: DIVERGE T7 Value/Failure\n");
        assertRejected(dir, "expect: DIVERGE T7 Value/Failure\njvm: Value 01 []\n");
        assertRejected(dir, "expect: DIVERGE T7 Value/Failure\njvm: Value 01 []\nuplc: Value 02 []\n");
        assertRejected(dir, "expect: DIVERGE T7 Value/Failure\njvm: Value 01 []\nuplc: Failure [] \"boom\"\n");
        // Pins without a divergence, unknown keys and unreadable history.
        assertRejected(dir, "expect: AGREE\njvm: Value 01 []\n");
        assertRejected(dir, "expect: AGREE\nseed: 1\n");
        assertRejected(dir, "expect: AGREE\nrecorded.jvm: Value\n");
        // The format is versioned.
        Path old = dir.resolve("old.diffcase");
        Files.writeString(old, "name: x\nclass: Probe\nmethod: m\narg: 02\nexpect: AGREE\n---\n" + SOURCE);
        assertThrows(HarnessException.class, () -> RegressionStore.read(old));
    }

    private static void assertRejected(Path dir, String lines) throws IOException {
        Path file = write(dir, lines);
        assertThrows(HarnessException.class, () -> RegressionStore.read(file), lines);
    }

    private static Path write(Path dir, String lines) throws IOException {
        Path file = Files.createTempFile(dir, "case", ".diffcase");
        Files.writeString(file, "format: 2\nname: x\nclass: Probe\nmethod: m\narg: 02\n" + lines + "---\n" + SOURCE);
        return file;
    }
}
