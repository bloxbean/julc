package org.julclang.difftest;

import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.api.lifecycle.AfterProperty;
import net.jqwik.api.lifecycle.BeforeProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Captures the G1 fixed-seed corpus for ADR-068 M0's ledger: the plans and inputs that
 * {@link G1PropertyTest#generatedProgramsAgree} generates, with the same seed, tries and arbitraries, written to
 * {@value #FILE} one {@code plan|inputs} line per try. The ledger checks the capture against the recorded corpus,
 * which holds every program the real campaign compiled. Run by {@code ./gradlew :julc-difftest:adr068G1Capture}.
 */
@Tag("adr068-g1-capture")
class Adr068G1Capture {

    static final String FILE = "build/adr068/g1-fixed-seed.txt";

    private static final List<String> lines = new ArrayList<>();
    private final G1PropertyTest arbitraries = new G1PropertyTest();

    @Provide
    Arbitrary<List<Integer>> plans() {
        return arbitraries.plans();
    }

    @Provide
    Arbitrary<List<Integer>> inputs() {
        return arbitraries.inputs();
    }

    @BeforeProperty
    void start() {
        lines.clear();
    }

    /** The pull-request campaign's seed and tries ({@link G1PropertyTest#generatedProgramsAgree}). */
    @Property(tries = 150, seed = "20261030")
    void capture(@ForAll("plans") List<Integer> plan, @ForAll("inputs") List<Integer> inputs) {
        lines.add(join(plan) + "|" + join(inputs));
    }

    @AfterProperty
    void write() throws IOException {
        Path file = Path.of(FILE);
        Files.createDirectories(file.getParent());
        Files.write(file, lines);
    }

    private static String join(List<Integer> values) {
        var text = new StringBuilder();
        for (int i = 0; i < values.size(); i++) text.append(i == 0 ? "" : ",").append(values.get(i));
        return text.toString();
    }

    /** The captured {@code (plan, inputs)} pairs. */
    static List<List<List<Integer>>> read(Path file) throws IOException {
        var result = new ArrayList<List<List<Integer>>>();
        for (String line : Files.readAllLines(file)) {
            if (line.isBlank()) continue;
            String[] parts = line.split("\\|", -1);
            result.add(List.of(parse(parts[0]), parse(parts[1])));
        }
        return result;
    }

    private static List<Integer> parse(String text) {
        var values = new ArrayList<Integer>();
        if (!text.isEmpty()) for (String v : text.split(",")) values.add(Integer.parseInt(v));
        return values;
    }
}
