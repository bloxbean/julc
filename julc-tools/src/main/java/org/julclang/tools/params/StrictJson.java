package org.julclang.tools.params;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * Reads every JSON input of {@code vm.applyParams} strictly (ADR-077 D6a, "Strict JSON everywhere"): exactly one
 * document with nothing after it, and no object that repeats a member name. Duplicates are refused while parsing,
 * before any tree is built, so a later member can never silently replace an earlier one. Integers are kept exact and
 * numbers with a fraction or exponent stay distinguishable from integer tokens.
 */
final class StrictJson {

    /** Long enough for any integer a script could use; Jackson's default (1000 digits) is lower. */
    private static final int MAX_NUMBER_LENGTH = 100_000;

    /**
     * No practical nesting limit: Jackson builds trees without recursion, and so does every reader of the trees here.
     * A value's depth is bounded by its length only. (Jackson's default is 500 levels, each Data level being two.)
     */
    private static final int MAX_NESTING_DEPTH = Integer.MAX_VALUE;

    private static final ObjectMapper MAPPER = JsonMapper.builder(JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .streamReadConstraints(StreamReadConstraints.builder().maxNumberLength(MAX_NUMBER_LENGTH)
                            .maxNestingDepth(MAX_NESTING_DEPTH).build())
                    .build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    private StrictJson() {}

    /**
     * Parses {@code text} as one strict JSON document.
     *
     * @param what names the input in the error, for example "The blueprint"
     */
    static JsonNode read(String text, String what) {
        JsonNode node;
        try {
            node = MAPPER.readTree(text);
        } catch (JacksonException e) {
            throw ApplyParamsException.invalidRequest(what + " is not strict JSON: " + e.getOriginalMessage());
        }
        if (node == null || node.isMissingNode()) {
            throw ApplyParamsException.invalidRequest(what + " is empty");
        }
        return node;
    }
}
