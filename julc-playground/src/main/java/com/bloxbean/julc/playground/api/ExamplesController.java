package com.bloxbean.julc.playground.api;

import com.bloxbean.julc.playground.model.ExampleDto;
import io.javalin.http.Context;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * GET /api/examples — List all bundled Java examples.
 * GET /api/examples/{name} — Load a specific example by name.
 */
public class ExamplesController {

    private final List<ExampleDto> examples;
    private final Map<String, ExampleDto> examplesByName;

    public ExamplesController() {
        this.examples = loadExamples();
        this.examplesByName = new LinkedHashMap<>();
        for (var ex : examples) {
            examplesByName.put(ex.name(), ex);
        }
    }

    public void list(Context ctx) {
        String languageFilter = ctx.queryParam("language");
        if (languageFilter != null && !languageFilter.isBlank()) {
            ctx.json(examples.stream()
                    .filter(e -> languageFilter.equalsIgnoreCase(e.language()))
                    .toList());
        } else {
            ctx.json(examples);
        }
    }

    public void get(Context ctx) {
        String name = ctx.pathParam("name");
        var ex = examplesByName.get(name);
        if (ex == null) {
            ctx.status(404).json(Map.of("error", "Example not found: " + name));
            return;
        }
        ctx.json(ex);
    }

    private List<ExampleDto> loadExamples() {
        var list = new ArrayList<ExampleDto>();

        // Java examples
        String[] javaNames = {
                "SimpleSpending.java", "VestingValidator.java", "MintingPolicy.java"
        };
        for (String name : javaNames) {
            loadExample(list, name, "java");
        }

        return Collections.unmodifiableList(list);
    }

    private void loadExample(List<ExampleDto> list, String name, String language) {
        try (var is = getClass().getResourceAsStream("/examples/" + name)) {
            if (is != null) {
                list.add(new ExampleDto(name, new String(is.readAllBytes(), StandardCharsets.UTF_8), language));
            }
        } catch (IOException ignored) {
        }
    }
}
