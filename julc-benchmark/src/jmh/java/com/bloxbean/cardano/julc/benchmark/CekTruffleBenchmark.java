package com.bloxbean.cardano.julc.benchmark;

import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.core.flat.UplcFlatDecoder;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.julc.vm.PlutusLanguage;
import com.bloxbean.cardano.julc.vm.truffle.TruffleVmProvider;
import org.openjdk.jmh.annotations.*;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmark for the GraalVM Truffle JIT CEK machine.
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
public class CekTruffleBenchmark {

    @Param({})
    String file;

    private Program program;
    private TruffleVmProvider provider;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        provider = new TruffleVmProvider();
        program = UplcFlatDecoder.decodeProgram(CekJavaBenchmark.loadFlatBytes(file));
    }

    @Benchmark
    public EvalResult bench() {
        return provider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);
    }
}
