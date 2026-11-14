package org.julclang.vm.java.ndc;

import co.nstant.in.cbor.CborBuilder;
import co.nstant.in.cbor.CborEncoder;
import co.nstant.in.cbor.CborException;
import org.julclang.core.Program;
import org.julclang.core.flat.UplcFlatDecoder;
import org.julclang.core.flat.UplcFlatEncoder;
import org.julclang.core.text.UplcParser;
import org.julclang.vm.EvalResult;
import org.julclang.vm.java.JavaVmProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TurboTest {

    @Test
    void protocolVersionFollowsTheEpoch() {
        assertEquals(8, TurboSample.protocolMajor(506));
        assertEquals(9, TurboSample.protocolMajor(507));
        assertEquals(9, TurboSample.protocolMajor(536));
        assertEquals(10, TurboSample.protocolMajor(537));
        assertEquals(10, TurboSample.protocolMajor(643));
        assertEquals(11, TurboSample.protocolMajor(644));
    }

    @Test
    void languageFollowsTheSuffix() {
        TurboSample sample = TurboSample.of(600, Path.of("600/ABC-0-DEF-v2.flat"));

        assertEquals("ABC-0-DEF-v2.flat", sample.bench());
        assertEquals("V2", sample.plutusVersion());
        assertEquals("10.0", sample.protocolVersion());
        assertThrows(IllegalArgumentException.class, () -> TurboSample.of(600, Path.of("600/ABC.flat")));
        assertThrows(IllegalArgumentException.class, () -> TurboSample.of(600, Path.of("600/ABC-v4.flat")));
    }

    @Test
    void unwrapsOneCborByteString() throws CborException {
        byte[] flat = {1, 2, 3};
        assertArrayEquals(flat, TurboSample.unwrap(cborBytes(flat), "x"));
        assertThrows(IllegalArgumentException.class, () -> TurboSample.unwrap(new byte[]{0x01}, "x"));
        assertThrows(IllegalArgumentException.class, () -> TurboSample.unwrap(new byte[]{0x43, 1}, "x"));
    }

    @Test
    void meanOfTheBestThirty() {
        long[] values = new long[50];
        for (int i = 0; i < values.length; i++) {
            values[i] = 50 - i;
        }
        // the 30 smallest are 1..30
        assertEquals(15.5, TurboMeasurement.meanOfBest(values));
    }

    @Test
    void thresholdsFollowTheSite() {
        // 1B CPU units = 1000 us: up to twice that passes
        assertTrue(TurboScore.cpuPasses(2000, 1_000_000_000L));
        assertFalse(TurboScore.cpuPasses(2000.1, 1_000_000_000L));
        // a small script passes within 200 us of its budget, whatever the ratio
        assertTrue(TurboScore.cpuPasses(250, 100_000_000L));
        assertFalse(TurboScore.cpuPasses(301, 100_000_000L));
        assertEquals(1.0, TurboScore.cpuDelta(2000, 1_000_000_000L), 1e-9);

        assertTrue(TurboScore.memPasses(2_000_000, 1_000_000));
        assertFalse(TurboScore.memPasses(2_000_001, 1_000_000));
        // within 320 KiB of the budget passes
        assertTrue(TurboScore.memPasses(300_000 + 320 * 1024, 300_000));
        assertFalse(TurboScore.memPasses(300_001 + 320 * 1024, 300_000));
    }

    @Test
    void measuresASampleScript(@TempDir Path root) throws IOException, CborException {
        Program program = UplcParser.parseProgram(
                "(program 1.0.0 [(builtin addInteger) (con integer 1) (con integer 2)])");
        Path file = root.resolve("650/SCRIPT-0-HASH-v3.flat");
        Files.createDirectories(file.getParent());
        Files.write(file, cborBytes(UplcFlatEncoder.encodeProgram(program)));
        Files.writeString(root.resolve("README.md"), "not a sample");

        List<TurboSample> samples = TurboSample.list(root);
        assertEquals(1, samples.size());
        TurboSample sample = samples.getFirst();
        assertEquals(11, sample.protocolMajor());

        Program decoded = UplcFlatDecoder.decodeProgram(sample.flat());
        TurboMeasurement m = TurboMeasurement.measure(new JavaVmProvider()
                .prepare(decoded, UplcEvaluation.target(sample.language(), sample.protocolMajor())));

        assertTrue(m.succeeded(), () -> String.valueOf(m.result()));
        assertTrue(m.result() instanceof EvalResult.Success);
        assertTrue(m.budget().cpuSteps() > 0);
        assertTrue(m.cpuUs() >= 0);
        assertTrue(m.memBytes() >= 0);
    }

    private static byte[] cborBytes(byte[] content) throws CborException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new CborEncoder(out).encode(new CborBuilder().add(content).build());
        return out.toByteArray();
    }
}
