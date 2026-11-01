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
 * Three answers to one generated program, none derived from another: the plain Java model of {@link RecordPrograms}, the JVM,
 * and each frontend at every level on the VM. The frontends must also agree with each other on value, cost and size, so this
 * is I1 and the JVM's check at once, over more shapes than a table holds.
 */
class Adr068RecordsPropertyTest {

    private static final DiffRunner RUNNER = new DiffRunner(configs(), 10_000);

    private static final List<String> SHAPES = List.of("new R", "new S.V", "new V", "new N(", "new Tuple2<>", "new Tuple2<BigInteger", " instanceof ",
            "said(\"", "fail(0)", "new BigInteger(", "new byte[]", " permits ", "sealed interface S {");

    private static List<DiffConfig> configs() {
        var configs = new ArrayList<>(DiffConfig.allLevels());
        configs.addAll(DiffConfig.allLevels(CompilerFrontend.JAVAC));
        return configs;
    }

    @Provide
    Arbitrary<List<Integer>> plans() {
        return Arbitraries.integers().between(0, 1000).list().ofMaxSize(160).withSizeDistribution(RandomDistribution.uniform());
    }

    @Provide
    Arbitrary<List<Integer>> inputs() {
        return Arbitraries.integers().between(0, 1000).list().ofSize(2);
    }

    /** What the tries held, so a generator that stopped reaching a shape fails rather than passes by saying less. */
    private static final Map<String, Integer> SEEN = new TreeMap<>();

    @BeforeProperty
    void clear() {
        SEEN.clear();
    }

    @AfterProperty
    void everyShapeWasRun() {
        System.out.println("record programs " + SEEN);
        for (String shape : SHAPES) assertTrue(SEEN.getOrDefault(shape, 0) >= 10, () -> shape + " in too few tries: " + SEEN);
        assertTrue(SEEN.getOrDefault("failed", 0) >= 10, SEEN::toString);
        assertTrue(SEEN.getOrDefault("traced", 0) >= 15, SEEN::toString);
        assertTrue(SEEN.getOrDefault("returned", 0) >= 50, SEEN::toString);
    }

    @Property(tries = 250, seed = "20261102")
    void theModelTheJvmAndBothFrontendsAgree(@ForAll("plans") List<Integer> plan, @ForAll("inputs") List<Integer> inputs) {
        var program = RecordPrograms.decode(plan, inputs);
        verify(program);
        for (String shape : SHAPES) {
            if (program.source().contains(shape)) SEEN.merge(shape, 1, Integer::sum);
        }
        if (program.failed()) SEEN.merge("failed", 1, Integer::sum);
        else SEEN.merge("returned", 1, Integer::sum);
        if (!program.traces().isEmpty()) SEEN.merge("traced", 1, Integer::sum);
    }

    static void verify(RecordPrograms.Program program) {
        var report = RUNNER.run(new DiffCase("records", program.source(), "Case", "m", program.arguments(), program.tags(), null));
        if (program.failed()) {
            var failure = assertInstanceOf(Observation.Failure.class, report.jvm(), program::source);
            assertEquals(program.traces(), failure.traces(), program::source);
        } else {
            assertEquals(new Observation.Value(program.value(), program.traces()), report.jvm(), program::source);
        }
        assertTrue(report.all(Verdict.Kind.AGREE), () -> program.source() + "\n" + report.describe());
        report.results().forEach((config, result) -> {
            if (config.frontend() == CompilerFrontend.JAVAPARSER) {
                var javac = report.results().get(new DiffConfig(config.level(), "Java", CompilerFrontend.JAVAC));
                assertEquals(result.measurement(), javac.measurement(), () -> config + "\n" + program.source());
            }
        });
    }
}
