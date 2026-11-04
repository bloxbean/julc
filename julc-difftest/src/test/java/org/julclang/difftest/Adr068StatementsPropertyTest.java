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
import org.julclang.core.PlutusData;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Three answers to one generated program, none derived from another: the plain Java model of {@link StatementPrograms},
 * the JVM, and each frontend at every level on the VM. The frontends must also agree with each other on value, cost and
 * size, so this is I1 and the JVM's check at once, over more shapes than a table holds.
 */
class Adr068StatementsPropertyTest {

    private static final DiffRunner RUNNER = new DiffRunner(configs(), 10_000);
    private static final TagTable TAGS = new TagTable(List.of(new TagTable.Entry("Case$S$A", "Case$S", 0),
            new TagTable.Entry("Case$S$B", "Case$S", 1), new TagTable.Entry("Case$S$D", "Case$S", 2)));

    private static List<DiffConfig> configs() {
        var configs = new ArrayList<>(DiffConfig.allLevels());
        return configs;
    }

    @Provide
    Arbitrary<List<Integer>> plans() {
        return Arbitraries.integers().between(0, 1000).list().ofMaxSize(160).withSizeDistribution(RandomDistribution.uniform());
    }

    @Provide
    Arbitrary<List<Integer>> inputs() {
        return Arbitraries.integers().between(0, 1000).list().ofSize(4);
    }

    /** What the tries held, so a generator that stopped reaching a shape fails rather than passes by saying less. */
    private static final Map<String, Integer> SEEN = new TreeMap<>();

    @BeforeProperty
    void clear() {
        SEEN.clear();
    }

    @AfterProperty
    void everyShapeWasRun() {
        System.out.println("statement programs " + SEEN);
        for (String shape : List.of("if (", "else {", "instanceof", "long ", "fail(0)", "saidL(\"", "long K =")) {
            assertTrue(SEEN.getOrDefault(shape, 0) >= 15, () -> shape + " in too few tries: " + SEEN);
        }
        assertTrue(SEEN.getOrDefault("failed", 0) >= 5, SEEN::toString);
        assertTrue(SEEN.getOrDefault("traced", 0) >= 15, SEEN::toString);
    }

    @Property(tries = 250, seed = "20261102")
    void theModelTheJvmAndBothFrontendsAgree(@ForAll("plans") List<Integer> plan, @ForAll("inputs") List<Integer> inputs) {
        var program = StatementPrograms.decode(plan, inputs);
        verify(program);
        String body = program.source().substring(program.source().indexOf("public static long m"));
        for (String shape : List.of("if (", "else {", "instanceof", "long ", "fail(0)", "saidL(\"", "long K =")) {
            if (body.indexOf(shape, body.indexOf('{')) >= 0) SEEN.merge(shape, 1, Integer::sum);
        }
        if (program.expected().failed()) SEEN.merge("failed", 1, Integer::sum);
        if (!program.expected().traces().isEmpty()) SEEN.merge("traced", 1, Integer::sum);
    }

    static void verify(StatementPrograms.Program program) {
        var report = RUNNER.run(new DiffCase("statements", program.source(), "Case", "m", program.arguments(), TAGS, null));
        var expected = program.expected();
        if (expected.failed()) {
            var failure = assertInstanceOf(Observation.Failure.class, report.jvm(), program::source);
            assertEquals(expected.traces(), failure.traces(), program::source);
        } else {
            assertEquals(new Observation.Value(new PlutusData.IntData(BigInteger.valueOf(expected.value())), expected.traces()),
                    report.jvm(), program::source);
        }
        assertTrue(report.all(Verdict.Kind.AGREE), () -> program.source() + "\n" + report.describe());
        report.results().forEach((config, result) -> {
        });
    }
}
