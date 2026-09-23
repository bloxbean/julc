package org.julclang.playground.api;

import org.julclang.playground.scenario.ScenarioRegistry;

import org.julclang.tools.service.ToolsService;
import io.javalin.http.Context;

/**
 * GET /api/scenarios/{purpose} — List test scenario templates for a given purpose.
 */
public class ScenariosController {

    public void handle(Context ctx) {
        var result = ScenarioRegistry.scenarios(ctx.pathParam("purpose"));
        ctx.status(result.status()).json(result.body());
    }
}
