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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Generation profiles and their gates (ADR-066 D6, G1-I6). Under the default profile no program contains a construct
 * whose gate is off there, judged both by the generator's markers and by reading the printed source; under each
 * historical profile the construct is generated; and the switches that M3 implements change nothing yet.
 */
class G1GatesTest {

    static final int PLANS = 20_000;

    /**
     * The markers of the gates that are on in the default profile, because their defect is fixed below (D6, "After a
     * fix"): #291's two symptoms ({@code DEFAULT_BLOCK_SCOPE}, G1 r8).
     */
    static final Set<G1.Feature> OPEN_IN_DEFAULT =
            Set.copyOf(EnumSet.of(G1.Feature.DEFAULT_BLOCK_SHADOW, G1.Feature.DEFAULT_BLOCK_AFTER_LOOP));

    /** Every gate off: the default profile before any gated defect was fixed (G1 r7's). */
    static final G1.Profile ALL_GATES_OFF = new G1.Profile(EnumSet.noneOf(G1.Gate.class));

    private static List<Integer> plan(Random random) {
        var plan = new ArrayList<Integer>();
        for (int j = random.nextInt(201); j > 0; j--) plan.add(random.nextInt(1001));
        return plan;
    }

    @Test
    void theDefaultProfileGeneratesNoGatedConstruct() {
        assertEquals(EnumSet.of(G1.Gate.DEFAULT_BLOCK_SCOPE), G1.Profile.DEFAULT.on(), "the gates open in the default profile");
        var closed = EnumSet.copyOf(G1.Feature.GATED);
        closed.removeAll(OPEN_IN_DEFAULT);
        var random = new Random(20261030);
        var open = EnumSet.noneOf(G1.Feature.class);
        for (int i = 0; i < PLANS; i++) {
            var plan = plan(random);
            var program = G1.decode(plan, G1.Profile.DEFAULT);
            var gated = EnumSet.copyOf(closed);
            gated.retainAll(program.features());
            assertTrue(gated.isEmpty(), gated + " in plan " + plan + "\n" + program.source());
            var found = GatedShapes.in(program.source());
            found.retainAll(closed);
            assertTrue(found.isEmpty(), found + " in plan " + plan + "\n" + program.source());
            for (var marker : OPEN_IN_DEFAULT) if (program.features().contains(marker)) open.add(marker);
        }
        // Fixed below, #291's constructs are allowed again; the default campaign generates both.
        assertEquals(OPEN_IN_DEFAULT, open, "the open gates' constructs in " + PLANS + " default plans");
    }

    /** Each implemented gate's profile generates its construct, as the markers and the source both show. */
    @Test
    void eachHistoricalProfileGeneratesItsConstruct() {
        var cases = Map.of(
                G1.Profile.REDISCOVER_209, G1.Feature.SHADOW_IN_LOOP_BRANCH,
                G1.Profile.REDISCOVER_280, G1.Feature.ZERO_ARG_HELPER,
                G1.Profile.REDISCOVER_146, G1.Feature.TRACE_IN_COMPARISON,
                G1.Profile.REDISCOVER_162, G1.Feature.ASSIGN_PATTERN_BINDING,
                G1.Profile.REDISCOVER_291, G1.Feature.DEFAULT_BLOCK_SHADOW,
                G1.Profile.REDISCOVER_291_LOOPS, G1.Feature.DEFAULT_BLOCK_AFTER_LOOP,
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

    private static final Pattern RECORD = Pattern.compile("(?m)^    record (R\\d)\\((.*)\\) \\{}$");

    /** Whether the program declares two standalone records with the same components in different orders (#209's steer). */
    static boolean mirrored(String source) {
        var records = new ArrayList<List<String>>();
        Matcher record = RECORD.matcher(source);
        while (record.find()) records.add(List.of(record.group(2).split(", ")));
        return records.size() == 2 && !records.get(0).equals(records.get(1)) && records.get(0).size() > 1
                && records.get(0).stream().sorted().toList().equals(records.get(1).stream().sorted().toList());
    }

    /** The share of programs with a break, a loop, a zero-parameter helper and mirrored records. */
    private static double[] shares(G1.Profile profile) {
        var random = new Random(20261039);
        double[] counts = new double[4];
        int plans = 4000;
        for (int i = 0; i < plans; i++) {
            var program = G1.decode(plan(random), profile);
            if (program.features().contains(G1.Feature.BREAK)) counts[0]++;
            if (program.features().contains(G1.Feature.LOOP)) counts[1]++;
            if (ZERO_ARG.matcher(program.source()).find()) counts[2]++;
            if (mirrored(program.source())) counts[3]++;
        }
        for (int k = 0; k < counts.length; k++) counts[k] /= plans;
        return counts;
    }

    private static final Pattern ZERO_ARG = Pattern.compile("(?m)^    static \\w+ \\w+\\(\\) \\{$");

    /**
     * A gate alone only allows its construct (review of M2, F65): turning it on keeps the default distribution of
     * unrelated shapes, so that D6's one-line change after a fix does not narrow the default campaign. The rediscovery
     * profiles' steering is what changes it.
     */
    @Test
    void aGateAloneKeepsTheDefaultDistribution() {
        double[] plain = shares(G1.Profile.DEFAULT);
        String[] names = {"break", "loop", "zero-parameter helper", "mirrored records"};
        // DEFAULT_BLOCK_SCOPE is on in the default profile (G1 r8): opening it kept G1 r7's distribution.
        double[] closed = shares(ALL_GATES_OFF);
        for (int k = 0; k < names.length; k++) {
            assertTrue(Math.abs(closed[k] - plain[k]) < 0.02, "DEFAULT_BLOCK_SCOPE moves the share of " + names[k] + " from "
                    + closed[k] + " to " + plain[k]);
        }
        for (G1.Gate gate : List.of(G1.Gate.SHADOW_IN_LOOP_BRANCH, G1.Gate.ZERO_ARG_HELPERS, G1.Gate.TRACE_IN_COMPARISON,
                G1.Gate.LOOP_BREAK_SHAPES, G1.Gate.ASSIGN_PATTERN_BINDING)) {
            double[] gated = shares(G1.Profile.DEFAULT.with(gate));
            for (int k = 0; k < names.length; k++) {
                // A zero-parameter helper is the ZERO_ARG_HELPERS construct itself.
                if (k == 2 && gate == G1.Gate.ZERO_ARG_HELPERS) continue;
                assertTrue(Math.abs(gated[k] - plain[k]) < 0.02, gate + " moves the share of " + names[k] + " from " + plain[k]
                        + " to " + gated[k]);
            }
        }
        double[] steered = shares(G1.Profile.REDISCOVER_209);
        assertTrue(steered[3] > 0.9 && steered[0] < plain[0] / 2, "the #209 steer: " + steered[3] + " mirrored, " + steered[0] + " break");
        assertTrue(shares(G1.Profile.REDISCOVER_280)[2] > shares(G1.Profile.DEFAULT.with(G1.Gate.ZERO_ARG_HELPERS))[2],
                "the #280 steer generates more zero-parameter helpers than the gate alone");
        assertNotEquals(G1.Profile.REDISCOVER_209, G1.Profile.DEFAULT.with(G1.Gate.SHADOW_IN_LOOP_BRANCH));
    }

    /** The switches of M3 are defined, printed and parsed, and change no program until M3. */
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

    /** The source reading sees a trace in a {@code signum} receiver as well as in a {@code compareTo} operand. */
    @Test
    void theSourceReadingCoversSignumReceivers() {
        String traced = "    public static boolean m(BigInteger a) {\n        return t(\"k0\", a).signum() > 0;\n    }\n";
        assertEquals(Set.of(G1.Feature.TRACE_IN_COMPARISON), GatedShapes.in(traced));
        assertEquals(Set.of(), GatedShapes.in(traced.replace("t(\"k0\", a)", "a")));
    }

    @Test
    void profilesRoundTripAsText() {
        assertEquals("G1 r8 -GUARDS -RECORD_PATTERNS -CROSS_TYPE_EQUALS -SHARED_VARIANT -ASSIGN_PATTERN_BINDING "
                + "-ZERO_ARG_HELPERS -SHADOW_IN_LOOP_BRANCH -LOOP_BREAK_SHAPES -TRACE_IN_COMPARISON +DEFAULT_BLOCK_SCOPE",
                G1.Profile.DEFAULT.text());
        for (var profile : List.of(G1.Profile.DEFAULT, G1.Profile.REDISCOVER_209, G1.Profile.REDISCOVER_280, G1.Profile.REDISCOVER_162,
                G1.Profile.REDISCOVER_291, G1.Profile.REDISCOVER_291_LOOPS,
                G1.Profile.REDISCOVER_146, ALL_GATES_OFF, new G1.Profile(EnumSet.allOf(G1.Gate.class)))) {
            assertEquals(profile, G1.Profile.parse(profile.text()));
        }
        String text = G1.Profile.DEFAULT.text();
        assertTrue(G1.Profile.REDISCOVER_209.text().endsWith("+SHADOW_IN_LOOP_BRANCH -LOOP_BREAK_SHAPES -TRACE_IN_COMPARISON "
                        + "+DEFAULT_BLOCK_SCOPE steer=209"),
                G1.Profile.REDISCOVER_209.text());
        assertThrows(HarnessException.class, () -> G1.Profile.DEFAULT.steered(G1.Steer.ISSUE_209));
        for (String bad : List.of("G0 r2 -GUARDS", text.replace("G1 r8", "G1 r7"), text + " steer=999", text + " steer=209", text.replace("-GUARDS ", ""), text + " -GUARDS",
                text.replace("-GUARDS", "-WHATEVER"), text.replace("-GUARDS", "GUARDS"), "G1 r1")) {
            assertThrows(HarnessException.class, () -> G1.Profile.parse(bad), bad);
        }
    }

    /**
     * The gated constructs as read from a printed program, independently of the generator's markers. The reading is
     * line-based: it assumes that a condition is on one line, which holds because no test contains a {@code switch}
     * ({@code G1SwitchShapesTest} checks that with javac's parser, and reads the switch constructs that way too).
     */
    static final class GatedShapes {

        private static final Pattern ZERO_ARG = Pattern.compile("(?m)^    static \\w+ \\w+\\(\\) \\{$");
        private static final Pattern FIELD = Pattern.compile("(?m)^    static final \\w+ (\\w+) = ");
        private static final Pattern HELPER = Pattern.compile("(?m)^    static \\w+ (\\w+)\\(([^)]*)\\) \\{\\n        return (.*);$");
        private static final Pattern LOOP = Pattern.compile("^( *)(while \\(|for \\(var ).*\\{$");
        private static final Pattern BLOCK_ARM = Pattern.compile("^( *)case \\w+ (\\w+) -> \\{$");
        private static final Pattern DEFAULT_BLOCK = Pattern.compile("^( *)default -> \\{$");
        private static final Pattern DECLARATION = Pattern.compile("^\\w+ (\\w+) = ");

        static Set<G1.Feature> in(String source) {
            var found = EnumSet.noneOf(G1.Feature.class);
            if (ZERO_ARG.matcher(source).find()) found.add(G1.Feature.ZERO_ARG_HELPER);
            if (assignsPatternBinding(source)) found.add(G1.Feature.ASSIGN_PATTERN_BINDING);
            var fields = new HashSet<String>();
            Matcher field = FIELD.matcher(source);
            while (field.find()) fields.add(field.group(1));
            var lines = source.split("\n");
            if (shadowsInDefaultBlock(lines, fields)) found.add(G1.Feature.DEFAULT_BLOCK_SHADOW);
            // Whether a default block's switch is inside a call argument needs the syntax tree, not lines.
            if (source.contains("default -> {") && G1Source.read(source).features().contains(G1.Feature.DEFAULT_BLOCK_AFTER_LOOP)) {
                found.add(G1.Feature.DEFAULT_BLOCK_AFTER_LOOP);
            }
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

        /** Whether a block arm of a switch assigns its case-pattern binding (#162's construct). */
        static boolean assignsPatternBinding(String source) {
            var lines = source.split("\n");
            for (int i = 0; i < lines.length; i++) {
                Matcher arm = BLOCK_ARM.matcher(lines[i]);
                if (!arm.matches()) continue;
                String inside = arm.group(1) + "    ";
                for (int j = i + 1; j < lines.length && lines[j].startsWith(inside); j++) {
                    if (lines[j].trim().startsWith(arm.group(2) + " = ")) return true;
                }
            }
            return false;
        }

        /** Whether a statement directly in a {@code default} arm block declares a local named like a field (#291). */
        static boolean shadowsInDefaultBlock(String[] lines, Set<String> fields) {
            for (int i = 0; i < lines.length; i++) {
                Matcher arm = DEFAULT_BLOCK.matcher(lines[i]);
                if (!arm.matches()) continue;
                String inside = arm.group(1) + "    ";
                for (int j = i + 1; j < lines.length && lines[j].startsWith(inside); j++) {
                    if (lines[j].charAt(inside.length()) == ' ') continue;
                    Matcher declaration = DECLARATION.matcher(lines[j].substring(inside.length()));
                    if (declaration.find() && fields.contains(declaration.group(1))) return true;
                }
            }
            return false;
        }

        private static boolean traces(String expression, Set<String> tracing) {
            for (String name : tracing) if (Pattern.compile("(?<![\\w.])" + name + "\\(").matcher(expression).find()) return true;
            return false;
        }

        /**
         * The receiver and argument of every {@code .compareTo(…)}, and the receiver of every {@code .signum()}: the
         * operations whose lowering evaluates an operand more than once (#146). G1 r2 emits no {@code signum}; it is
         * read here so that a grammar that starts to emit it stays gated.
         */
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
            at = source.indexOf(".signum()");
            while (at >= 0) {
                operands.add(source.substring(receiverStart(source, at), at));
                at = source.indexOf(".signum()", at + 1);
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
