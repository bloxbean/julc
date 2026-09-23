package org.julclang.playground.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.julclang.playground.scenario.ScenarioRegistry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Build-time static catalogue. The engine no longer owns playground examples. */
public final class CatalogueExport {
    private CatalogueExport() {}

    public static void main(String[] args) throws Exception {
        var scenarios = new LinkedHashMap<String, Object>();
        ScenarioRegistry.getAllScenarios().keySet().stream().sorted()
                .forEach(p -> scenarios.put(p, ScenarioRegistry.scenarios(p).body()));
        var output = Path.of(args[0]);
        Files.createDirectories(output.getParent());
        new ObjectMapper().writeValue(output.toFile(), Map.of("examples", new ExampleCatalog().list(null).body(),
                "scenarios", scenarios));
    }
}
