package org.julclang.testkit.property;

import org.julclang.core.PlutusData;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.SplittableRandom;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Runs a property over generated arguments: {@link Settings#tries()} cases with sizes growing
 * from 0 to {@link Settings#maxSize()}, all drawn from one seeded random source. The first
 * failure is shrunk greedily, argument by argument, to a simpler counterexample that still
 * fails. The same seed and settings reproduce the same run.
 * <pre>{@code
 * var amounts = DataGenerators.integers(0, 1_000_000);
 * var result = PropertyCheck.check(List.of(amounts, amounts), args -> {
 *     var sum = eval(add, args);                       // e.g. a compiled on-chain function
 *     return sum.equals(expected(args)) ? Verdict.pass() : Verdict.fail("got " + sum);
 * }, Settings.seeded(42));
 * assertTrue(result.passed(), result::describe);
 * }</pre>
 */
public final class PropertyCheck {

    private PropertyCheck() {}

    /**
     * @param seed       the random seed; report it to reproduce a run
     * @param tries      the number of generated cases
     * @param maxSize    the size of the last case (the first is 0)
     * @param maxShrinks the most property evaluations spent shrinking a failure
     */
    public record Settings(long seed, int tries, int maxSize, int maxShrinks) {
        public static final int DEFAULT_TRIES = 100;
        public static final int DEFAULT_MAX_SIZE = 30;
        public static final int DEFAULT_MAX_SHRINKS = 1000;

        public Settings {
            if (tries < 1) throw new IllegalArgumentException("tries must be positive");
            if (maxSize < 0) throw new IllegalArgumentException("maxSize must not be negative");
            if (maxShrinks < 0) throw new IllegalArgumentException("maxShrinks must not be negative");
        }

        /** Default settings with a fresh seed. */
        public static Settings defaults() {
            return seeded(new SplittableRandom(System.nanoTime() ^ System.currentTimeMillis()).nextLong());
        }

        public static Settings seeded(long seed) {
            return new Settings(seed, DEFAULT_TRIES, DEFAULT_MAX_SIZE, DEFAULT_MAX_SHRINKS);
        }

        public Settings withSeed(long seed) {
            return new Settings(seed, tries, maxSize, maxShrinks);
        }

        public Settings withTries(int tries) {
            return new Settings(seed, tries, maxSize, maxShrinks);
        }

        public Settings withMaxSize(int maxSize) {
            return new Settings(seed, tries, maxSize, maxShrinks);
        }

        public Settings withMaxShrinks(int maxShrinks) {
            return new Settings(seed, tries, maxSize, maxShrinks);
        }
    }

    /** A property over generated arguments, in generator order. */
    @FunctionalInterface
    public interface Property {
        Verdict test(List<PlutusData> arguments) throws Exception;
    }

    /** The outcome of one case; a failure carries the reason. */
    public record Verdict(boolean passed, String message) {
        private static final Verdict PASS = new Verdict(true, "");

        public Verdict {
            Objects.requireNonNull(message, "message");
        }

        public static Verdict pass() {
            return PASS;
        }

        public static Verdict fail(String message) {
            return new Verdict(false, message);
        }

        public static Verdict of(boolean passed, String failureMessage) {
            return passed ? PASS : fail(failureMessage);
        }
    }

    /**
     * The outcome of a run.
     *
     * @param passed         whether every case passed
     * @param seed           the seed of the run
     * @param tries          the cases run, up to and including a failing one
     * @param counterexample the shrunk failing arguments, or empty
     * @param original       the failing arguments as first generated, or empty
     * @param shrinks        the successful shrink steps from original to counterexample
     * @param message        the counterexample's failure message, or empty
     */
    public record Result(boolean passed, long seed, int tries, List<PlutusData> counterexample,
                         List<PlutusData> original, int shrinks, String message) {
        public Result {
            counterexample = List.copyOf(counterexample);
            original = List.copyOf(original);
            Objects.requireNonNull(message, "message");
        }

        /** A one-paragraph report, with the seed that reproduces it. */
        public String describe() {
            if (passed) return "passed " + tries + " cases (seed " + seed + ")";
            return "failed after " + tries + (tries == 1 ? " case" : " cases") + " (seed " + seed + "), shrunk "
                    + shrinks + (shrinks == 1 ? " time" : " times") + ": " + message + "\n  counterexample: "
                    + counterexample;
        }
    }

    /** Run {@code property} over values of {@code generators}, one argument per generator. */
    public static Result check(List<? extends DataGenerator> generators, Property property, Settings settings) {
        Objects.requireNonNull(property, "property");
        var random = new SplittableRandom(settings.seed());
        for (int trial = 0; trial < settings.tries(); trial++) {
            int size = settings.tries() == 1 ? settings.maxSize()
                    : (int) ((long) settings.maxSize() * trial / (settings.tries() - 1));
            var arguments = new ArrayList<PlutusData>(generators.size());
            for (var generator : generators) arguments.add(generator.generate(random, size));
            var verdict = run(property, arguments);
            if (!verdict.passed()) return shrink(generators, property, settings, trial + 1, arguments, verdict);
        }
        return new Result(true, settings.seed(), settings.tries(), List.of(), List.of(), 0, "");
    }

    private static Result shrink(List<? extends DataGenerator> generators, Property property, Settings settings,
                                 int tries, List<PlutusData> original, Verdict failure) {
        var current = List.copyOf(original);
        var message = failure.message();
        int steps = 0;
        int evaluations = 0;
        search:
        while (evaluations < settings.maxShrinks()) {
            Iterator<List<PlutusData>> candidates = candidates(generators, current).iterator();
            while (candidates.hasNext()) {
                if (evaluations++ >= settings.maxShrinks()) break search;
                var candidate = candidates.next();
                var verdict = run(property, candidate);
                if (!verdict.passed()) {
                    current = candidate;
                    message = verdict.message();
                    steps++;
                    continue search;
                }
            }
            break;
        }
        return new Result(false, settings.seed(), tries, current, original, steps, message);
    }

    /** Simpler argument lists: each argument shrunk in turn, the others kept. */
    private static Stream<List<PlutusData>> candidates(List<? extends DataGenerator> generators, List<PlutusData> arguments) {
        return IntStream.range(0, arguments.size()).boxed().flatMap(i -> generators.get(i).shrink(arguments.get(i))
                .map(simpler -> {
                    var replaced = new ArrayList<>(arguments);
                    replaced.set(i, simpler);
                    return List.copyOf(replaced);
                }));
    }

    private static Verdict run(Property property, List<PlutusData> arguments) {
        try {
            var verdict = property.test(arguments);
            return verdict != null ? verdict : Verdict.fail("the property returned no verdict");
        } catch (Exception | AssertionError e) {
            return Verdict.fail(e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }
}
