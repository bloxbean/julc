package org.julclang.vm.java.ndc;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scores a uplc-conformance report against the scenario's expectations, by the rule the site applies
 * ({@code scenarios/uplc-conformance/build.py}): an entry passes when its Plutus and protocol versions match, its
 * evaluation equals the expected one up to the pretty-printer's whitespace, and, where a budget is expected, its budget
 * is the expected budget.
 */
final class UplcConformanceScore {

    /** One expectation the report does not meet. */
    record Mismatch(String key, String group, String expectedEvaluation, JsonNode expectedBudget,
                    String actualEvaluation, JsonNode actualBudget, boolean evaluationPassed, boolean budgetPassed) {
    }

    /**
     * @param total        the number of expectations
     * @param passed       how many the report meets
     * @param budgeted     how many expect a budget
     * @param budgetPassed how many of those the report's budget meets
     */
    record Result(int total, int passed, int budgeted, int budgetPassed, List<Mismatch> mismatches) {
    }

    private static final Pattern TOKEN = Pattern.compile(
            "\"(?:\\\\.|[^\"\\\\])*\"|[()\\[\\],{}]|[^\\s()\\[\\],{}]+",
            Pattern.DOTALL | Pattern.UNICODE_CHARACTER_CLASS);

    private UplcConformanceScore() {
    }

    /** @return the key the scenario identifies an entry by */
    static String key(JsonNode entry) {
        return entry.path("id").asText() + " " + entry.path("plutus_version").asText() + "/"
                + entry.path("protocol_version").asText();
    }

    /**
     * @param entries      the report's {@code scenarios}
     * @param expectations the expectations' {@code scenarios}
     */
    static Result score(JsonNode entries, JsonNode expectations) {
        Map<String, JsonNode> actual = new HashMap<>();
        for (JsonNode entry : entries) {
            if (actual.put(key(entry), entry) != null) {
                throw new IllegalStateException("the report has two entries for " + key(entry));
            }
        }
        int passed = 0;
        int budgeted = 0;
        int budgetPassed = 0;
        List<Mismatch> mismatches = new ArrayList<>();
        for (JsonNode expected : expectations) {
            JsonNode entry = actual.get(key(expected));
            String expectedEvaluation = expected.path("evaluation").asText();
            JsonNode expectedBudget = expected.get("budget");
            boolean hasBudget = expectedBudget != null && !expectedBudget.isNull();
            boolean evaluation = entry != null
                    && normalize(entry.path("evaluation").asText()).equals(normalize(expectedEvaluation));
            boolean budget = !hasBudget || entry != null && sameBudget(expectedBudget, entry.get("budget"));
            if (hasBudget) {
                budgeted++;
                if (budget) {
                    budgetPassed++;
                }
            }
            if (evaluation && budget) {
                passed++;
            } else {
                mismatches.add(new Mismatch(key(expected), expected.path("group").asText(), expectedEvaluation,
                        expectedBudget, entry == null ? null : entry.path("evaluation").asText(),
                        entry == null ? null : entry.get("budget"), evaluation, budget));
            }
        }
        return new Result(expectations.size(), passed, budgeted, budgetPassed, mismatches);
    }

    /** Pretty-printer whitespace is ignored; string constants are kept whole ({@code normalize_evaluation}). */
    static String normalize(String value) {
        List<String> tokens = new ArrayList<>();
        Matcher matcher = TOKEN.matcher(value);
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        String normalized = String.join(" ", tokens);
        normalized = normalized.replaceAll("(?U)([\\[({])\\s+", "$1");
        normalized = normalized.replaceAll("(?U)\\s+([)\\]}])", "$1");
        normalized = normalized.replaceAll("(?U)\\s*,\\s*", ",");
        return normalized.strip();
    }

    private static boolean sameBudget(JsonNode expected, JsonNode actual) {
        return actual != null && actual.isObject() && Set.of("cpu", "mem").equals(fieldNames(actual))
                && actual.get("cpu").isIntegralNumber() && actual.get("mem").isIntegralNumber()
                && expected.path("cpu").asLong() == actual.get("cpu").asLong()
                && expected.path("mem").asLong() == actual.get("mem").asLong();
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
