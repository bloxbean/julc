package org.julclang.difftest;

import org.julclang.compiler.OptimizationLevel;
import org.julclang.core.PlutusData;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The compile cache of {@link UplcExecutor} is bounded (ADR-063 D13, review round r2 of #253, F1). A
 * fuzz session runs one executor over many large programs; an unbounded cache kept every one of them
 * and filled the heap, so that later compiles failed with {@code OutOfMemoryError}.
 */
class UplcExecutorCacheTest {

    private static final DiffConfig CONFIG = new DiffConfig(OptimizationLevel.BASELINE, "Java");

    private static DiffCase program(int constant) {
        String source = """
                import java.math.BigInteger;

                public class Probe {
                    public static BigInteger m(BigInteger a) {
                        return a.add(BigInteger.valueOf(%d));
                    }
                }
                """.formatted(constant);
        return new DiffCase("cache " + constant, source, "Probe", "m", List.of(new PlutusData.IntData(BigInteger.ONE)));
    }

    private static Observation value(long v) {
        return new Observation.Value(new PlutusData.IntData(BigInteger.valueOf(v)), List.of());
    }

    @Test
    void theCacheKeepsAtMostItsBoundOfPrograms() {
        var executor = new UplcExecutor(3, Long.MAX_VALUE);
        for (int n = 0; n < 8; n++) {
            assertEquals(value(n + 1), executor.run(program(n), CONFIG).observation());
            assertTrue(executor.cachedPrograms() <= 3, "cached " + executor.cachedPrograms() + " after " + (n + 1));
        }
        assertEquals(3, executor.cachedPrograms());
        // An evicted program compiles again, to the same result.
        assertEquals(value(1), executor.run(program(0), CONFIG).observation());
    }

    @Test
    void theCacheKeepsAtMostItsBoundOfScriptBytes() {
        var size = new UplcExecutor().run(program(0), CONFIG).measurement().scriptBytes();
        var executor = new UplcExecutor(64, 2L * size);
        for (int n = 0; n < 8; n++) {
            assertEquals(value(n + 1), executor.run(program(n), CONFIG).observation());
            assertTrue(executor.cachedScriptBytes() <= 2L * size, executor.cachedScriptBytes() + " bytes cached");
        }
        assertEquals(2, executor.cachedPrograms());
    }

    @Test
    void aScriptLargerThanTheBoundIsNotCached() {
        var executor = new UplcExecutor(64, 1);
        assertEquals(value(1), executor.run(program(0), CONFIG).observation());
        assertEquals(value(1), executor.run(program(0), CONFIG).observation());
        assertEquals(0, executor.cachedPrograms());
        assertEquals(0, executor.cachedScriptBytes());
    }
}
