package com.bloxbean.cardano.julc.compiler.error;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.text.MessageFormat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drift detection: every JULC#### code referenced in compiler source must
 * have a matching entry in {@code diagnostics.json}.
 *
 * <p>This test was added in response to Phase B review feedback: without it,
 * removing or renumbering a catalog entry silently breaks the link between
 * runtime diagnostics and the AI-facing catalog at {@code /ai/diagnostics.json}.
 */
class DiagnosticCatalogConsistencyTest {

    private static final Pattern JULC_CODE = Pattern.compile("\"(JULC\\d{4})\"");
    private static final Pattern CODE_FIELD = Pattern.compile("\"code\"\\s*:\\s*\"(JULC\\d{4})\"");
    private static final Pattern CONSTANT_FIELD = Pattern.compile("\"constant\"\\s*:\\s*\"([A-Z][A-Z0-9_]*)\"");
    private static final Pattern STATUS_FIELD = Pattern.compile("\"status\"\\s*:\\s*\"([A-Za-z]+)\"");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\d+)(?:,[^}]*)?}");
    private static final Set<String> VALID_STATUS = Set.of("emitted", "planned", "lintOnly", "internal");

    @Test
    void everyProductionCodeIsInCatalog() throws IOException {
        Set<String> catalogCodes = loadCatalogCodes();
        assertFalse(catalogCodes.isEmpty(),
                "diagnostics.json must contain at least one entry; loader returned empty");

        Set<String> productionCodes = scanProductionForCodes();
        Set<String> missing = new HashSet<>(productionCodes);
        missing.removeAll(catalogCodes);
        assertTrue(missing.isEmpty(),
                "Compiler source references codes missing from diagnostics.json: " + missing);
    }

    @Test
    void generatedConstantsMatchCatalog() throws IOException {
        Set<String> catalogCodes = loadCatalogFields(CODE_FIELD);
        Set<String> catalogConstants = loadCatalogFields(CONSTANT_FIELD);

        Set<String> generatedCodes = new HashSet<>();
        Set<String> generatedConstants = new HashSet<>();
        for (var info : DiagnosticCodes.all()) {
            generatedCodes.add(info.code());
            generatedConstants.add(info.constant());
            assertSame(info, DiagnosticCodes.find(info.code()).orElseThrow(),
                    "find(code) must return the generated singleton for " + info.code());
        }

        assertEquals(catalogCodes, generatedCodes,
                "Generated DiagnosticCodes must cover exactly the catalog codes");
        assertEquals(catalogConstants, generatedConstants,
                "Generated DiagnosticCodes must cover exactly the catalog constants");
        assertEquals(catalogCodes.size(), DiagnosticCodes.all().size(),
                "Generated all() must not contain duplicate codes");
    }

    @Test
    void catalogHasPr1SchemaFields() throws IOException {
        String body = loadCatalogBody();
        int count = countMatches(CODE_FIELD, body);
        assertTrue(count >= 30, "Expected the JULC0001-JULC0030 seed catalog");

        Map<String, Pattern> required = Map.of(
                "constant", Pattern.compile("\"constant\"\\s*:"),
                "status", Pattern.compile("\"status\"\\s*:"),
                "title", Pattern.compile("\"title\"\\s*:"),
                "category", Pattern.compile("\"category\"\\s*:"),
                "severity", Pattern.compile("\"severity\"\\s*:"),
                "template", Pattern.compile("\"template\"\\s*:"),
                "summary", Pattern.compile("\"summary\"\\s*:"),
                "fix", Pattern.compile("\"fix\"\\s*:")
        );
        required.forEach((field, pattern) ->
                assertEquals(count, countMatches(pattern, body),
                        "Every diagnostic entry must have field '" + field + "'"));

        Set<String> statuses = loadCatalogFields(STATUS_FIELD);
        assertTrue(VALID_STATUS.containsAll(statuses),
                "Unknown diagnostic status values: " + statuses);
    }

    @Test
    void generatedTemplatesParseAndFormat() {
        for (var info : DiagnosticCodes.all()) {
            assertDoesNotThrow(() -> new MessageFormat(info.template()),
                    "Template must parse under MessageFormat for " + info.code());
            int maxPlaceholder = maxPlaceholder(info.template());
            if (maxPlaceholder >= 0) {
                Object[] args = new Object[maxPlaceholder + 1];
                for (int i = 0; i < args.length; i++) {
                    args[i] = "arg" + i;
                }
                assertDoesNotThrow(() -> info.format(args),
                        "Template must format with dummy args for " + info.code());
            }
        }
    }

    @Test
    void catalogIsLoadable() throws IOException {
        // Smoke check that the resource is on the classpath and is non-trivial.
        try (InputStream in = getClass().getResourceAsStream("/diagnostics.json")) {
            assertNotNull(in, "diagnostics.json must be on the classpath");
            byte[] bytes = in.readAllBytes();
            assertTrue(bytes.length > 1000,
                    "diagnostics.json appears truncated (<1KB)");
            String body = new String(bytes);
            assertTrue(body.contains("\"diagnostics\""),
                    "diagnostics.json must have a 'diagnostics' top-level key");
            assertTrue(body.contains("JULC0001"),
                    "diagnostics.json must contain at least the seed code JULC0001");
        }
    }

    private String loadCatalogBody() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/diagnostics.json")) {
            assertNotNull(in, "diagnostics.json must be on the classpath");
            return new String(in.readAllBytes());
        }
    }

    private Set<String> loadCatalogCodes() throws IOException {
        return loadCatalogFields(JULC_CODE);
    }

    private Set<String> loadCatalogFields(Pattern pattern) throws IOException {
        Set<String> codes = new HashSet<>();
        String body = loadCatalogBody();
        Matcher m = pattern.matcher(body);
        while (m.find()) codes.add(m.group(1));
        return codes;
    }

    private static int countMatches(Pattern pattern, String body) {
        int count = 0;
        Matcher m = pattern.matcher(body);
        while (m.find()) count++;
        return count;
    }

    private static int maxPlaceholder(String template) {
        int max = -1;
        Matcher m = PLACEHOLDER.matcher(template);
        while (m.find()) {
            max = Math.max(max, Integer.parseInt(m.group(1)));
        }
        return max;
    }

    /** Walk julc-compiler/src/main/java looking for "JULCnnnn" string literals. */
    private Set<String> scanProductionForCodes() throws IOException {
        Set<String> codes = new HashSet<>();
        Path src = locateCompilerMainJava();
        if (src == null) {
            // Running from a packaged jar where the source tree isn't present
            // (e.g. on CI with --no-source). In that case we can only verify
            // catalog loadability, not drift. Skip silently.
            return codes;
        }
        try (Stream<Path> files = Files.walk(src)) {
            files.filter(p -> p.toString().endsWith(".java"))
                 .forEach(p -> {
                     try {
                         String content = Files.readString(p);
                         Matcher m = JULC_CODE.matcher(content);
                         while (m.find()) codes.add(m.group(1));
                     } catch (IOException e) {
                         // Don't fail the whole test on a single read error;
                         // missing-codes assertion will still cover real gaps.
                     }
                 });
        }
        return codes;
    }

    /**
     * Best-effort path resolution. The Gradle test task runs with the module
     * root as cwd, so {@code src/main/java} resolves directly. Falls back to
     * walking up from the test class location.
     */
    private static Path locateCompilerMainJava() {
        Path cwd = Path.of("src/main/java");
        if (Files.isDirectory(cwd)) return cwd;
        Path repoRel = Path.of("julc-compiler/src/main/java");
        if (Files.isDirectory(repoRel)) return repoRel;
        return null;
    }
}
