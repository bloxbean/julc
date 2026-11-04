package org.julclang.difftest;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.RandomDistribution;
import net.jqwik.api.lifecycle.AfterProperty;
import net.jqwik.api.lifecycle.BeforeProperty;
import org.julclang.compiler.CompilerFrontend;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Three answers to one generated pipeline, none derived from another: the plain Java model of {@link LambdaPrograms}, the JVM,
 * and each frontend at every level on the VM. Where the reference gives that answer too the frontends must agree on cost and
 * size, so this is I1 and the JVM's check at once, over more shapes than a table holds.
 */
class Adr068LambdasPropertyTest {

    private static final DiffRunner RUNNER = new DiffRunner(configs(), 10_000);
    private static final TagTable TAGS = new TagTable(List.of(new TagTable.Entry("Case$S$A", "Case$S", 0),
            new TagTable.Entry("Case$S$D", "Case$S", 1)));
    private static final List<String> SHAPES = List.of("filter", "map", "any", "all", "find", "nested", "capture a parameter", "capture a local",
            "capture a static", "capture a boolean", "capture a pattern variable", "written type", "block", "branches", "trace", "failing", "a record",
            "a record element", "a variant", "a variant element", "a boolean element", "a list", "a list element", "a text element");

    private static List<DiffConfig> configs() {
        var configs = new ArrayList<>(DiffConfig.allLevels());
        return configs;
    }

    @Provide
    Arbitrary<List<Integer>> plans() {
        return Arbitraries.integers().between(0, 1000).list().ofMaxSize(24).withSizeDistribution(RandomDistribution.uniform());
    }

    @Provide
    Arbitrary<List<Integer>> inputs() {
        return Arbitraries.integers().between(0, 1000).list().ofSize(13);
    }

    /** What the tries held, so a generator that stopped reaching a shape fails rather than passes by saying less. */
    private static final Map<String, Integer> SEEN = new TreeMap<>();

    @BeforeProperty
    void clear() {
        SEEN.clear();
    }

    @AfterProperty
    void everyShapeWasRun() {
        System.out.println("lambda programs " + SEEN);
        for (String shape : SHAPES) assertTrue(SEEN.getOrDefault(shape, 0) >= 5, () -> shape + " in too few tries: " + SEEN);
        assertTrue(SEEN.getOrDefault("failed", 0) >= 10, SEEN::toString);
        assertTrue(SEEN.getOrDefault("traced", 0) >= 10, SEEN::toString);
        assertTrue(SEEN.getOrDefault("returned", 0) >= 100, SEEN::toString);
    }

    @Property(tries = 450, seed = "20261102")
    void theModelTheJvmAndBothFrontendsAgree(@ForAll("plans") List<Integer> plan, @ForAll("inputs") List<Integer> inputs) {
        var program = LambdaPrograms.decode(plan, inputs);
        verify(program);
        program.shapes().forEach(shape -> SEEN.merge(shape, 1, Integer::sum));
        if (program.failed()) SEEN.merge("failed", 1, Integer::sum);
        else SEEN.merge("returned", 1, Integer::sum);
        if (!program.traces().isEmpty()) SEEN.merge("traced", 1, Integer::sum);
    }

    static void verify(LambdaPrograms.Program program) {
        var report = RUNNER.run(new DiffCase("lambdas", program.source(), "Case", "m", program.arguments(), TAGS, null));
        if (program.failed()) {
            var failure = assertInstanceOf(Observation.Failure.class, report.jvm(), program::source);
            assertEquals(program.traces(), failure.traces(), program::source);
        } else {
            assertEquals(new Observation.Value(program.value(), program.traces()), report.jvm(), program::source);
        }
        report.results().forEach((config, result) -> assertEquals(Verdict.Kind.AGREE, result.verdict().kind(),
                () -> program.source() + "\n" + report.describe()));
    }
}
