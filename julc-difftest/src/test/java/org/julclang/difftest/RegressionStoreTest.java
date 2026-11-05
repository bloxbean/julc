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

    /** A confirmation rerun is history in the failure record (ADR-063 D6, r6): readable, and never checked. */
    @Test
    void aConfirmationRerunIsRecorded(@TempDir Path dir) {
        Path file = dir.resolve("case.diffcase");
        RegressionStore.write(file, CASE, RegressionStore.Expectation.AGREE, null, Map.of(), null);
        RegressionStore.addRecorded(file, "Java/NONE.rerun", "Value 24 []");
        RegressionStore.addRecorded(file, "Java/NONE.rerun.cost", "factor 100 budget cpu 1000000000000 mem 1400000000");
        var saved = RegressionStore.read(file);
        assertEquals(value(-5), ObservationText.parse(saved.recorded().get("Java/NONE.rerun")));
        assertEquals("factor 100 budget cpu 1000000000000 mem 1400000000", saved.recorded().get("Java/NONE.rerun.cost"));
        assertEquals(RegressionStore.Expectation.AGREE, saved.expectation());
        assertThrows(RuntimeException.class, () -> RegressionStore.addRecorded(file, "Java/NONE.rerun", "not an observation"));
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

    /** A record with unequal tags under two parents (#279's shape) and a profile: format 3 (ADR-066 D3, D5). */
    private static final TagTable TWO_PARENTS = TagTable.of(new TagTable.Entry("Probe$R", "Probe$T", 1),
            new TagTable.Entry("Probe$Q", "Probe$S", 1), new TagTable.Entry("Probe$R", "Probe$S", 0),
            new TagTable.Entry("Probe$U", "Probe$T", 0));

    @Test
    void aCaseWithTagsAndAProfileRoundTripsAsFormat3(@TempDir Path dir) throws IOException {
        var diffCase = new DiffCase("tagged", SOURCE, "Probe", "m", CASE.args(), TWO_PARENTS, "G1 r1 -GUARDS -RECORD_PATTERNS");
        Path file = dir.resolve("tagged.diffcase");
        RegressionStore.write(file, diffCase, RegressionStore.Expectation.AGREE, null, Map.of(), null);
        String text = Files.readString(file);
        assertTrue(text.startsWith("format: 3\nname: tagged\nclass: Probe\nmethod: m\n"
                + "tag: Probe$Q Probe$S 1\ntag: Probe$R Probe$S 0\ntag: Probe$R Probe$T 1\ntag: Probe$U Probe$T 0\n"
                + "profile: G1 r1 -GUARDS -RECORD_PATTERNS\narg: 02\n"), text);
        var saved = RegressionStore.read(file);
        assertEquals(diffCase, saved.diffCase());
        assertEquals(TWO_PARENTS, saved.diffCase().tags());
        // Tags are part of the case: the same source and arguments under swapped tags is another case.
        var swapped = TagTable.of(new TagTable.Entry("Probe$R", "Probe$T", 0), new TagTable.Entry("Probe$Q", "Probe$S", 0),
                new TagTable.Entry("Probe$R", "Probe$S", 1), new TagTable.Entry("Probe$U", "Probe$T", 1));
        assertNotEquals(saved.diffCase(), new DiffCase("tagged", SOURCE, "Probe", "m", CASE.args(), swapped,
                "G1 r1 -GUARDS -RECORD_PATTERNS"));
    }

    /** A case without tags or a profile, such as every G0 case, is still written as format 2 (ADR-066 D5). */
    @Test
    void aCaseWithoutTagsIsWrittenAsFormat2(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("untagged.diffcase");
        RegressionStore.write(file, CASE, RegressionStore.Expectation.AGREE, null, Map.of(), null);
        assertEquals("format: 2\nname: identity\nclass: Probe\nmethod: m\narg: 02\nexpect: AGREE\n---\n" + SOURCE,
                Files.readString(file));
        assertEquals(CASE, RegressionStore.read(file).diffCase());
        var profiled = new DiffCase("profiled", SOURCE, "Probe", "m", CASE.args(), TagTable.EMPTY, "G1 r1");
        RegressionStore.write(file, profiled, RegressionStore.Expectation.AGREE, null, Map.of(), null);
        assertTrue(Files.readString(file).startsWith("format: 3\n"));
        assertEquals(profiled, RegressionStore.read(file).diffCase());
    }

    @Test
    void invalidTagLinesAreRejected(@TempDir Path dir) throws IOException {
        // Tags and profiles need format 3.
        assertRejected(dir, "tag: Probe$A Probe$S 0\nexpect: AGREE\n");
        assertRejected(dir, "profile: G1 r1\nexpect: AGREE\n");
        // Malformed, duplicated or gapped tags, and two profiles.
        assertRejected3(dir, "tag: Probe$A Probe$S\nexpect: AGREE\n");
        assertRejected3(dir, "tag: Probe$A Probe$S zero\nexpect: AGREE\n");
        assertRejected3(dir, "tag: Probe$A Probe$S 0\ntag: Probe$A Probe$S 0\nexpect: AGREE\n");
        assertRejected3(dir, "tag: Probe$A Probe$S 0\ntag: Probe$B Probe$S 0\nexpect: AGREE\n");
        assertRejected3(dir, "tag: Probe$A Probe$S 0\ntag: Probe$B Probe$S 2\nexpect: AGREE\n");
        assertRejected3(dir, "profile: G1 r1\nprofile: G1 r1\nexpect: AGREE\n");
        // Format 3 reads its lines in any order; format 4 does not exist.
        Path file = Files.createTempFile(dir, "case", ".diffcase");
        Files.writeString(file, "format: 3\nname: x\nclass: Probe\nmethod: m\narg: 02\ntag: Probe$B Probe$S 1\n"
                + "expect: AGREE\ntag: Probe$A Probe$S 0\n---\n" + SOURCE);
        assertEquals(TagTable.of(new TagTable.Entry("Probe$A", "Probe$S", 0), new TagTable.Entry("Probe$B", "Probe$S", 1)),
                RegressionStore.read(file).diffCase().tags());
        Files.writeString(file, "format: 4\nname: x\nclass: Probe\nmethod: m\narg: 02\nexpect: AGREE\n---\n" + SOURCE);
        assertThrows(HarnessException.class, () -> RegressionStore.read(file));
    }

    private static void assertRejected3(Path dir, String lines) throws IOException {
        Path file = Files.createTempFile(dir, "case", ".diffcase");
        Files.writeString(file, "format: 3\nname: x\nclass: Probe\nmethod: m\narg: 02\n" + lines + "---\n" + SOURCE);
        assertThrows(HarnessException.class, () -> RegressionStore.read(file), lines);
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
