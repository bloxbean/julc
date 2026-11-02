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
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Three answers to one generated loop program, none derived from another: the plain Java model of {@link LoopPrograms}, the
 * JVM, and each frontend at every level on the VM. The frontends must also agree with each other on value, cost and size, so
 * this is I1 and the JVM's check at once, read back on-chain over more shapes than a table holds.
 */
class Adr068LoopsPropertyTest {

    private static final DiffRunner RUNNER = new DiffRunner(configs(), 10_000);
    private static final List<String> SHAPES = List.of("for (", "while (", "break;", "else {", "long K =", "saidL(\"", "fail(0)");
    /** A bare block: a line that is only its brace. */
    private static final Pattern BARE_BLOCK = Pattern.compile("(?m)^\\s*\\{$");
    /** A local at the method's level, where the reference's name for it is the field's, so its identity {@code let} shows. */
    private static final Pattern HIDES_THE_FIELD = Pattern.compile("(?m)^ {8}long K =");

    private static List<DiffConfig> configs() {
        var configs = new ArrayList<>(DiffConfig.allLevels());
        configs.addAll(DiffConfig.allLevels(CompilerFrontend.JAVAC));
        return configs;
    }

    @Provide
    Arbitrary<List<Integer>> plans() {
        return Arbitraries.integers().between(0, 1000).list().ofMaxSize(200).withSizeDistribution(RandomDistribution.uniform());
    }

    @Provide
    Arbitrary<List<Integer>> inputs() {
        return Arbitraries.integers().between(0, 1000).list().ofSize(7);
    }

    /** What the tries held, so a generator that stopped reaching a shape fails rather than passes by saying less. */
    private static final Map<String, Integer> SEEN = new TreeMap<>();

    @BeforeProperty
    void clear() {
        SEEN.clear();
    }

    @AfterProperty
    void everyShapeWasRun() {
        System.out.println("loop programs " + SEEN);
        for (String shape : SHAPES) assertTrue(SEEN.getOrDefault(shape, 0) >= 15, () -> shape + " in too few tries: " + SEEN);
        assertTrue(SEEN.getOrDefault("bare block", 0) >= 15, SEEN::toString);
        assertTrue(SEEN.getOrDefault("failed", 0) >= 5, SEEN::toString);
        assertTrue(SEEN.getOrDefault("traced", 0) >= 15, SEEN::toString);
    }

    @Property(tries = 300, seed = "20261102")
    void theModelTheJvmAndBothFrontendsAgree(@ForAll("plans") List<Integer> plan, @ForAll("inputs") List<Integer> inputs) {
        var program = LoopPrograms.decode(plan, inputs);
        verify(program);
        String body = program.source().substring(program.source().indexOf("long r = 0;"));
        for (String shape : SHAPES) {
            if (body.contains(shape)) SEEN.merge(shape, 1, Integer::sum);
        }
        if (BARE_BLOCK.matcher(body).find()) SEEN.merge("bare block", 1, Integer::sum);
        if (program.expected().failed()) SEEN.merge("failed", 1, Integer::sum);
        if (!program.expected().traces().isEmpty()) SEEN.merge("traced", 1, Integer::sum);
    }

    static void verify(LoopPrograms.Program program) {
        var report = RUNNER.run(new DiffCase("loops", program.source(), "Case", "m", program.arguments()));
        var expected = program.expected();
        if (expected.failed()) {
            var failure = assertInstanceOf(Observation.Failure.class, report.jvm(), program::source);
            assertEquals(expected.traces(), failure.traces(), program::source);
        } else {
            assertEquals(new Observation.Value(new PlutusData.IntData(BigInteger.valueOf(expected.value())), expected.traces()),
                    report.jvm(), program::source);
        }
        assertTrue(report.all(Verdict.Kind.AGREE), () -> program.source() + "\n" + report.describe());
        // A local that hides a field after a loop has its own name here, which saves a let: JavacLoopsTest states it.
        if (HIDES_THE_FIELD.matcher(program.source()).find()) return;
        report.results().forEach((config, result) -> {
            if (config.frontend() == CompilerFrontend.JAVAPARSER) {
                var javac = report.results().get(new DiffConfig(config.level(), "Java", CompilerFrontend.JAVAC));
                assertEquals(result.measurement(), javac.measurement(), () -> config + "\n" + program.source());
            }
        });
    }
}
