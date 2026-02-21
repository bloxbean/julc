package com.bloxbean.cardano.julc.analysis.ai;

import com.bloxbean.cardano.julc.analysis.Category;
import com.bloxbean.cardano.julc.analysis.Finding;
import com.bloxbean.cardano.julc.analysis.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses LLM JSON responses into {@link Finding} objects.
 * <p>
 * Uses simple regex-based parsing to avoid external JSON library dependencies.
 * Handles malformed responses gracefully — partial results are returned.
 */
public final class ResponseParser {

    private ResponseParser() {}

    /**
     * Parse a JSON array response from the LLM into Finding objects.
     *
     * @param jsonResponse the raw response text (expected to be a JSON array)
     * @return parsed findings (may be partial if response is malformed)
     */
    public static List<Finding> parse(String jsonResponse) {
        if (jsonResponse == null || jsonResponse.isBlank()) {
            return List.of();
        }

        // Strip markdown code fences if present
        String cleaned = jsonResponse.strip();
        if (cleaned.startsWith("```")) {
            int firstNewline = cleaned.indexOf('\n');
            int lastFence = cleaned.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                cleaned = cleaned.substring(firstNewline + 1, lastFence).strip();
            }
        }

        // Find the JSON array
        int arrayStart = cleaned.indexOf('[');
        int arrayEnd = cleaned.lastIndexOf(']');
        if (arrayStart < 0 || arrayEnd <= arrayStart) {
            return List.of();
        }
        String arrayContent = cleaned.substring(arrayStart + 1, arrayEnd);

        // Split into individual objects — find matching { ... } pairs
        var findings = new ArrayList<Finding>();
        int depth = 0;
        int objStart = -1;

        for (int i = 0; i < arrayContent.length(); i++) {
            char c = arrayContent.charAt(i);
            if (c == '{') {
                if (depth == 0) objStart = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && objStart >= 0) {
                    String objStr = arrayContent.substring(objStart, i + 1);
                    Finding finding = parseObject(objStr);
                    if (finding != null) {
                        findings.add(finding);
                    }
                    objStart = -1;
                }
            }
        }

        return findings;
    }

    private static Finding parseObject(String json) {
        try {
            String severityStr = extractField(json, "severity");
            String categoryStr = extractField(json, "category");
            String title = extractField(json, "title");
            String description = extractField(json, "description");
            String location = extractField(json, "location");
            String recommendation = extractField(json, "recommendation");

            if (title == null || title.isEmpty()) return null;

            Severity severity = parseSeverity(severityStr);
            Category category = parseCategory(categoryStr);

            return new Finding(
                    severity,
                    category,
                    title,
                    description != null ? description : "",
                    location != null ? location : "",
                    recommendation != null ? recommendation : ""
            );
        } catch (Exception e) {
            return null;
        }
    }

    private static final Pattern FIELD_PATTERN = Pattern.compile(
            "\"(\\w+)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    static String extractField(String json, String fieldName) {
        Matcher m = FIELD_PATTERN.matcher(json);
        while (m.find()) {
            if (m.group(1).equals(fieldName)) {
                return m.group(2)
                        .replace("\\n", "\n")
                        .replace("\\\"", "\"")
                        .replace("\\\\", "\\");
            }
        }
        return null;
    }

    private static Severity parseSeverity(String s) {
        if (s == null) return Severity.INFO;
        try {
            return Severity.valueOf(s.toUpperCase().strip());
        } catch (IllegalArgumentException e) {
            return Severity.INFO;
        }
    }

    private static Category parseCategory(String s) {
        if (s == null) return Category.GENERAL;
        try {
            return Category.valueOf(s.toUpperCase().strip());
        } catch (IllegalArgumentException e) {
            return Category.GENERAL;
        }
    }
}
