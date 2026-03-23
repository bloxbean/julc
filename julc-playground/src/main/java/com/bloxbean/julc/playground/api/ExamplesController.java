package com.bloxbean.julc.playground.api;

import com.bloxbean.julc.playground.model.ExampleDto;
import io.javalin.http.Context;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * GET /api/examples — List all bundled .jrl examples.
 * GET /api/examples/{name} — Load a specific example by name.
 */
public class ExamplesController {

    private final Map<String, String> examples;

    public ExamplesController() {
        this.examples = loadExamples();
    }

    public void list(Context ctx) {
        var list = examples.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> new ExampleDto(e.getKey(), e.getValue()))
                .toList();
        ctx.json(list);
    }

    public void get(Context ctx) {
        String name = ctx.pathParam("name");
        String source = examples.get(name);
        if (source == null) {
            ctx.status(404).json(Map.of("error", "Example not found: " + name));
            return;
        }
        ctx.json(new ExampleDto(name, source));
    }

    private Map<String, String> loadExamples() {
        var map = new LinkedHashMap<String, String>();
        String[] names = {
                "SimpleTransfer.jrl", "Vesting.jrl", "TimeLock.jrl",
                "MultiSigTreasury.jrl", "MultiSigMinting.jrl", "HTLC.jrl", "OutputCheck.jrl"
        };
        for (String name : names) {
            try (var is = getClass().getResourceAsStream("/examples/" + name)) {
                if (is != null) {
                    map.put(name, new String(is.readAllBytes(), StandardCharsets.UTF_8));
                }
            } catch (IOException ignored) {
            }
        }
        return Collections.unmodifiableMap(map);
    }
}
