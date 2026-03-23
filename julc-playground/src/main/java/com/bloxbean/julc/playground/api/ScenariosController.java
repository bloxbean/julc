package com.bloxbean.julc.playground.api;

import com.bloxbean.julc.playground.scenario.ScenarioRegistry;
import io.javalin.http.Context;

import java.util.Map;

/**
 * GET /api/scenarios/{purpose} — List test scenario templates for a given purpose.
 */
public class ScenariosController {

    public void handle(Context ctx) {
        String purpose = ctx.pathParam("purpose");
        var scenarios = ScenarioRegistry.getScenarios(purpose);
        if (scenarios.isEmpty()) {
            ctx.json(Map.of("purpose", purpose.toUpperCase(), "scenarios", java.util.List.of(),
                    "message", "No scenario templates available for purpose: " + purpose));
            return;
        }
        ctx.json(Map.of("purpose", purpose.toUpperCase(), "scenarios", scenarios));
    }
}
