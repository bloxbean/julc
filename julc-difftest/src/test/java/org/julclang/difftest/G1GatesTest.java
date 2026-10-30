package org.julclang.difftest;

import org.julclang.difftest.gen.G1;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Generation profiles and their gates (ADR-066 D6, G1-I6). Under the default profile no program contains a gated
 * construct, judged both by the generator's markers and by reading the printed source; under each historical
 * profile the construct is generated; and the switches that M3 implements change nothing yet.
 */
class G1GatesTest {

    static final int PLANS = 20_000;

    private static List<Integer> plan(Random random) {
        var plan = new ArrayList<Integer>();
        for (int j = random.nextInt(201); j > 0; j--) plan.add(random.nextInt(1001));
        return plan;
    }

    @Test
    void theDefaultProfileGeneratesNoGatedConstruct() {
        var random = new Random(20261030);
        for (int i = 0; i < PLANS; i++) {
            var plan = plan(random);
            var program = G1.decode(plan, G1.Profile.DEFAULT);
            var gated = EnumSet.copyOf(G1.Feature.GATED);
            gated.retainAll(program.features());
            assertTrue(gated.isEmpty(), gated + " in plan " + plan + "\n" + program.source());
            var found = GatedShapes.in(program.source());
            assertTrue(found.isEmpty(), found + " in plan " + plan + "\n" + program.source());
        }
    }

    /** Each implemented gate's profile generates its construct, as the markers and the source both show. */
    @Test
    void eachHistoricalProfileGeneratesItsConstruct() {
        var cases = Map.of(
                G1.Profile.REDISCOVER_209, G1.Feature.SHADOW_IN_LOOP_BRANCH,
                G1.Profile.REDISCOVER_280, G1.Feature.ZERO_ARG_HELPER,
                G1.Profile.REDISCOVER_146, G1.Feature.TRACE_IN_COMPARISON,
                G1.Profile.DEFAULT.with(G1.Gate.LOOP_BREAK_SHAPES), G1.Feature.LOOP_BREAK_SHAPE);
        cases.forEach((profile, marker) -> {
            var random = new Random(20261031);
            int marked = 0, seen = 0;
            for (int i = 0; i < 4000; i++) {
                var program = G1.decode(plan(random), profile);
                if (program.features().contains(marker)) marked++;
                if (GatedShapes.in(program.source()).contains(marker)) seen++;
            }
            assertTrue(marked > 0, profile + " never generated " + marker);
            assertTrue(seen > 0, profile + ": the source never shows " + marker);
        });
    }

    /** The switches of M3 are defined, printed and parsed, and change no program in G1 r1. */
    @Test
    void inertGatesChangeNothing() {
        var random = new Random(20261032);
        for (int i = 0; i < 2000; i++) {
            var plan = plan(random);
            String source = G1.decode(plan, G1.Profile.DEFAULT).source();
            for (G1.Gate gate : G1.Gate.INERT) {
                assertEquals(source, G1.decode(plan, G1.Profile.DEFAULT.with(gate)).source(), gate + " " + plan);
            }
        }
    }

    @Test
    void profilesRoundTripAsText() {
        assertEquals("G1 r1 -GUARDS -RECORD_PATTERNS -CROSS_TYPE_EQUALS -SHARED_VARIANT -ASSIGN_PATTERN_BINDING "
                + "-ZERO_ARG_HELPERS -SHADOW_IN_LOOP_BRANCH -LOOP_BREAK_SHAPES -TRACE_IN_COMPARISON", G1.Profile.DEFAULT.text());
        for (var profile : List.of(G1.Profile.DEFAULT, G1.Profile.REDISCOVER_209, G1.Profile.REDISCOVER_280,
                G1.Profile.REDISCOVER_146, new G1.Profile(EnumSet.allOf(G1.Gate.class)))) {
            assertEquals(profile, G1.Profile.parse(profile.text()));
        }
        String text = G1.Profile.DEFAULT.text();
        for (String bad : List.of("G0 r2 -GUARDS", text.replace("-GUARDS ", ""), text + " -GUARDS",
                text.replace("-GUARDS", "-WHATEVER"), text.replace("-GUARDS", "GUARDS"), "G1 r1")) {
            assertThrows(HarnessException.class, () -> G1.Profile.parse(bad), bad);
        }
    }

    /** The gated constructs as read from a printed program, independently of the generator's markers. */
    static final class GatedShapes {

        private static final Pattern ZERO_ARG = Pattern.compile("(?m)^    static \\w+ \\w+\\(\\) \\{$");
        private static final Pattern FIELD = Pattern.compile("(?m)^    static final \\w+ (\\w+) = ");
        private static final Pattern HELPER = Pattern.compile("(?m)^    static \\w+ (\\w+)\\(([^)]*)\\) \\{\\n        return (.*);$");
        private static final Pattern LOOP = Pattern.compile("^( *)(while \\(|for \\(var ).*\\{$");

        static Set<G1.Feature> in(String source) {
            var found = EnumSet.noneOf(G1.Feature.class);
            if (ZERO_ARG.matcher(source).find()) found.add(G1.Feature.ZERO_ARG_HELPER);
            var fields = new HashSet<String>();
            Matcher field = FIELD.matcher(source);
            while (field.find()) fields.add(field.group(1));
            var lines = source.split("\n");
            for (int i = 0; i < lines.length; i++) {
                Matcher loop = LOOP.matcher(lines[i]);
                if (!loop.matches()) continue;
                String body = loop.group(1) + "    ";
                boolean breaks = false, conditionalUpdate = false;
                for (int j = i + 1; j < lines.length && lines[j].startsWith(body); j++) {
                    if (lines[j].length() > body.length() && lines[j].charAt(body.length()) == ' ') continue;
                    String statement = lines[j].substring(body.length());
                    if (statement.startsWith("if (") && statement.endsWith("{ break; }")) breaks = true;
                    else if (statement.startsWith("if (") && statement.endsWith("{")) {
                        conditionalUpdate = true;
                        String first = j + 1 < lines.length ? lines[j + 1].trim() : "";
                        Matcher declaration = Pattern.compile("^\\w+ (\\w+) = ").matcher(first);
                        if (declaration.find() && fields.contains(declaration.group(1))) {
                            found.add(G1.Feature.SHADOW_IN_LOOP_BRANCH);
                        }
                    }
                }
                if (breaks && conditionalUpdate) found.add(G1.Feature.LOOP_BREAK_SHAPE);
            }
            var tracing = new HashSet<String>(Set.of("t"));
            Matcher helper = HELPER.matcher(source);
            while (helper.find()) {
                if (!helper.group(1).equals("t") && traces(helper.group(3), tracing)) tracing.add(helper.group(1));
            }
            for (String operand : comparisonOperands(source)) {
                if (traces(operand, tracing)) found.add(G1.Feature.TRACE_IN_COMPARISON);
            }
            return found;
        }

        private static boolean traces(String expression, Set<String> tracing) {
            for (String name : tracing) if (Pattern.compile("(?<![\\w.])" + name + "\\(").matcher(expression).find()) return true;
            return false;
        }

        /** The receiver and argument of every {@code .compareTo(…)}. */
        static List<String> comparisonOperands(String source) {
            var operands = new ArrayList<String>();
            int at = source.indexOf(".compareTo(");
            while (at >= 0) {
                int open = at + ".compareTo".length();
                int close = matching(source, open);
                operands.add(source.substring(open + 1, close));
                operands.add(source.substring(receiverStart(source, at), at));
                at = source.indexOf(".compareTo(", at + 1);
            }
            return operands;
        }

        private static int matching(String text, int open) {
            int depth = 0;
            for (int i = open; i < text.length(); i++) {
                if (text.charAt(i) == '(') depth++;
                if (text.charAt(i) == ')' && --depth == 0) return i;
            }
            throw new IllegalStateException("unbalanced: " + text.substring(open));
        }

        /** Scans back over identifiers, dots, string literals and balanced parentheses. */
        private static int receiverStart(String text, int end) {
            int i = end - 1;
            while (i >= 0) {
                char c = text.charAt(i);
                if (c == ')') {
                    int depth = 0;
                    for (; i >= 0; i--) {
                        if (text.charAt(i) == ')') depth++;
                        if (text.charAt(i) == '(' && --depth == 0) break;
                    }
                    i--;
                } else if (Character.isLetterOrDigit(c) || c == '.' || c == '_') {
                    i--;
                } else {
                    break;
                }
            }
            return i + 1;
        }
    }
}
