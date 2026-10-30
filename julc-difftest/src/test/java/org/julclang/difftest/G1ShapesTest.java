package org.julclang.difftest;

import org.julclang.difftest.gen.G1;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shapes of G1 read from the printed programs, independently of the generator's own markers (review of M2: F63,
 * F70, F71, F80): variants typed as themselves with nonzero tags, also as record components, {@code permits} orders that differ from the declaration
 * order, loop-body locals that shadow fields, and boolean helper calls whose result decides the outcome.
 */
class G1ShapesTest {

    static final int PLANS = 4000;

    private static List<Integer> plan(Random random) {
        var plan = new ArrayList<Integer>();
        for (int j = random.nextInt(201); j > 0; j--) plan.add(random.nextInt(1001));
        return plan;
    }

    private static final Pattern SIGNATURE = Pattern.compile(
            "public static (\\w+) m\\(BigInteger a, BigInteger b, JulcList<BigInteger> xs((?:, \\w+ \\w+)*)\\)");
    private static final Pattern PERMITS = Pattern.compile("(?m)^    sealed interface (\\w+) permits ([\\w, ]+) \\{}$");
    private static final Pattern VARIANT = Pattern.compile("(?m)^    record (\\w+)\\(.*\\) implements (\\w+) \\{}$");
    private static final Pattern FIELD = Pattern.compile("(?m)^    static final \\w+ (\\w+) = ");
    private static final Pattern LOOP = Pattern.compile("^( *)(while \\(|for \\(var ).*\\{$");

    /** Each variant's tag, from the tag table. */
    private static Map<String, Integer> tags(G1.Program program) {
        var tags = new HashMap<String, Integer>();
        for (var entry : program.tags().entries()) tags.put(entry.record().substring("Probe$".length()), entry.tag());
        return tags;
    }

    @Test
    void theShapesAppearInThePrintedPrograms() {
        var random = new Random(20261036);
        int variantResults = 0, variantArguments = 0, variantComponents = 0, permuted = 0, loopShadows = 0;
        for (int i = 0; i < PLANS; i++) {
            var program = G1.decode(plan(random), G1.Profile.DEFAULT);
            String source = program.source();
            var tags = tags(program);
            Matcher signature = SIGNATURE.matcher(source);
            assertTrue(signature.find(), source);
            if (tags.getOrDefault(signature.group(1), 0) != 0) variantResults++;
            for (String parameter : signature.group(2).split(", ")) {
                if (!parameter.isEmpty() && tags.getOrDefault(parameter.split(" ")[0], 0) != 0) {
                    variantArguments++;
                    break;
                }
            }
            if (variantComponent(source, tags)) variantComponents++;
            if (permitsPermuted(source)) permuted++;
            if (loopLocalShadowsField(source)) loopShadows++;
        }
        assertTrue(variantResults > PLANS / 50, "nonzero-tag variant results: " + variantResults);
        assertTrue(variantArguments > PLANS / 50, "nonzero-tag variant arguments: " + variantArguments);
        assertTrue(variantComponents > PLANS / 50, "components of a nonzero-tag variant: " + variantComponents);
        assertTrue(permuted > PLANS / 50, "permits orders that differ from declaration and name order: " + permuted);
        assertTrue(loopShadows > PLANS / 100, "loop-body locals shadowing a field: " + loopShadows);
    }

    private static final Pattern RECORD_COMPONENTS = Pattern.compile("(?m)^    record \\w+\\((.*)\\)");

    /** Whether some record has a component typed as a variant with a nonzero tag (F80). */
    static boolean variantComponent(String source, Map<String, Integer> tags) {
        Matcher record = RECORD_COMPONENTS.matcher(source);
        while (record.find()) {
            for (String component : record.group(1).split(", ")) {
                if (!component.isEmpty() && tags.getOrDefault(component.split(" ")[0], 0) != 0) return true;
            }
        }
        return false;
    }

    /**
     * Whether some family's {@code permits} order differs both from the order its variants are declared in and from
     * their names' order, so that tags follow neither.
     */
    static boolean permitsPermuted(String source) {
        Matcher permits = PERMITS.matcher(source);
        while (permits.find()) {
            var order = List.of(permits.group(2).split(", "));
            var declared = new ArrayList<String>();
            Matcher variant = VARIANT.matcher(source);
            while (variant.find()) if (variant.group(2).equals(permits.group(1))) declared.add(variant.group(1));
            if (!declared.equals(order) && !order.stream().sorted().toList().equals(order)) return true;
        }
        return false;
    }

    /** Whether a statement directly in a loop body declares a local with a field's name. */
    static boolean loopLocalShadowsField(String source) {
        var fields = new ArrayList<String>();
        Matcher field = FIELD.matcher(source);
        while (field.find()) fields.add(field.group(1));
        var lines = source.split("\n");
        for (int i = 0; i < lines.length; i++) {
            Matcher loop = LOOP.matcher(lines[i]);
            if (!loop.matches()) continue;
            String body = loop.group(1) + "    ";
            for (int j = i + 1; j < lines.length && lines[j].startsWith(body); j++) {
                if (lines[j].length() > body.length() && lines[j].charAt(body.length()) == ' ') continue;
                Matcher declaration = Pattern.compile("^\\w+ (\\w+) = ").matcher(lines[j].substring(body.length()));
                if (declaration.find() && fields.contains(declaration.group(1))) return true;
            }
        }
        return false;
    }

    private static final Pattern BOOLEAN_HELPER = Pattern.compile("(?m)^(    static boolean \\w+\\([^)]*\\) \\{\\n        return )(.*);$");

    /**
     * A boolean helper call decides the outcome (F71): negating the bodies of a program's boolean helpers changes the
     * JVM observation on some input, for some of the programs that call one.
     */
    @Test
    void aBooleanHelperCallDecidesTheOutcome() {
        var random = new Random(20261037);
        var executor = new JvmExecutor(5_000);
        int calling = 0, decided = 0;
        for (int i = 0; i < 3000 && calling < 40; i++) {
            var program = G1.decode(plan(random), G1.Profile.DEFAULT, "B" + i);
            if (!program.features().contains(G1.Feature.BOOLEAN_HELPER_CALL)) continue;
            calling++;
            String negated = BOOLEAN_HELPER.matcher(program.source()).replaceAll("$1!($2);");
            for (int input = 0; input < 6; input++) {
                var inputs = List.of(random.nextInt(1001), random.nextInt(1001), random.nextInt(1001), random.nextInt(1001),
                        random.nextInt(1001), random.nextInt(1001), random.nextInt(1001), random.nextInt(1001));
                var args = program.arguments(inputs);
                var original = executor.run(new DiffCase("b", program.source(), "B" + i, "m", args, program.tags(), null));
                var changed = executor.run(new DiffCase("b", negated, "B" + i, "m", args, program.tags(), null));
                if (!original.equals(changed)) {
                    decided++;
                    break;
                }
            }
        }
        assertTrue(calling >= 40, "programs calling a boolean helper: " + calling);
        assertTrue(decided >= 5, decided + " of " + calling + " programs had an outcome a boolean helper call decides");
    }
}
