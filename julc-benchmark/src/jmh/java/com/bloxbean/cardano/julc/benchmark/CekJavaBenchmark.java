package com.bloxbean.cardano.julc.benchmark;

import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.core.flat.UplcFlatDecoder;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.julc.vm.PlutusLanguage;
import com.bloxbean.cardano.julc.vm.java.JavaVmProvider;
import org.openjdk.jmh.annotations.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmark for the pure Java CEK machine.
 * <p>
 * Each {@code @Param("file")} is a {@code .flat} UPLC script filename.
 * Files are loaded from the filesystem {@code data/} directory first,
 * falling back to classpath resources.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 1, time = 5, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 1, time = 5, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@Threads(1)
public class CekJavaBenchmark {

    @Param({})
    String file;

    private Program program;
    private JavaVmProvider provider;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        provider = new JavaVmProvider();
        program = UplcFlatDecoder.decodeProgram(loadFlatBytes(file));
    }

    @Benchmark
    public EvalResult bench() {
        return provider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);
    }

    static byte[] loadFlatBytes(String filename) throws IOException {
        // Try filesystem first (Docker: flat files copied to data/ by runner script)
        Path fsPath = Path.of("data", filename);
        if (Files.exists(fsPath)) {
            return Files.readAllBytes(fsPath);
        }
        // Fall back to classpath resources (local dev: files in src/jmh/resources/data/)
        String resourcePath = "data/" + filename;
        try (InputStream is = CekJavaBenchmark.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IOException("Flat file not found: " + filename
                        + " (tried filesystem data/ and classpath " + resourcePath + ")");
            }
            return is.readAllBytes();
        }
    }
}
