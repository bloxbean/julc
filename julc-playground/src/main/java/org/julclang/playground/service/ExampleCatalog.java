package org.julclang.playground.service;

import org.julclang.tools.service.ServiceResult;

import org.julclang.playground.model.ExampleDto;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bundled example contracts ({@code GET /api/examples}, {@code GET /api/examples/{name}}).
 */
public final class ExampleCatalog {

    private static final String[] JAVA_EXAMPLES = {
            "SimpleSpending.java", "VestingValidator.java", "MintingPolicy.java"
    };

    private final List<ExampleDto> examples;
    private final Map<String, ExampleDto> examplesByName;

    public ExampleCatalog() {
        this.examples = loadExamples();
        this.examplesByName = new LinkedHashMap<>();
        for (var ex : examples) {
            examplesByName.put(ex.name(), ex);
        }
    }

    /** All examples, optionally filtered by language (case-insensitive). */
    public ServiceResult<List<ExampleDto>> list(String languageFilter) {
        if (languageFilter != null && !languageFilter.isBlank()) {
            return ServiceResult.ok(examples.stream()
                    .filter(e -> languageFilter.equalsIgnoreCase(e.language()))
                    .toList());
        }
        return ServiceResult.ok(examples);
    }

    /** One example by file name; 404 with an {@code error} body when unknown. */
    public ServiceResult<Object> get(String name) {
        var ex = examplesByName.get(name);
        if (ex == null) {
            return ServiceResult.status(404, Map.of("error", "Example not found: " + name));
        }
        return ServiceResult.ok(ex);
    }

    private static List<ExampleDto> loadExamples() {
        var list = new ArrayList<ExampleDto>();
        for (String name : JAVA_EXAMPLES) {
            try (var is = ExampleCatalog.class.getResourceAsStream("/examples/" + name)) {
                if (is != null) {
                    list.add(new ExampleDto(name, new String(is.readAllBytes(), StandardCharsets.UTF_8), "java"));
                }
            } catch (IOException ignored) {
            }
        }
        return Collections.unmodifiableList(list);
    }
}
