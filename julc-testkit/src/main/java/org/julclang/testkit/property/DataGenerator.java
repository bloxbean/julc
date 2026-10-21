package org.julclang.testkit.property;

import org.julclang.core.PlutusData;

import java.util.SplittableRandom;
import java.util.stream.Stream;

/**
 * A seeded random source of {@link PlutusData} values of one shape, with type-directed
 * shrinking. Generators are pure functions of the random source and a size, so a seed
 * reproduces a run, and they use no reflection, so they also run in a native image.
 * <p>
 * Build them with {@link DataGenerators}, usually from a PIR type with
 * {@link DataGenerators#forType}, and run properties with {@link PropertyCheck}.
 */
public interface DataGenerator {

    /**
     * A random value. {@code size} (from 0) bounds lengths and magnitudes; it grows over a run
     * so the first cases are small.
     */
    PlutusData generate(SplittableRandom random, int size);

    /**
     * Simpler values of the same shape, simplest first. Each candidate must be a value this
     * generator could have produced; the default shrinks nothing.
     */
    default Stream<PlutusData> shrink(PlutusData value) {
        return Stream.empty();
    }
}
