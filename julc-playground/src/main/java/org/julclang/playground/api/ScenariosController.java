package org.julclang.playground.api;

import org.julclang.playground.service.PlaygroundService;
import io.javalin.http.Context;

/**
 * GET /api/scenarios/{purpose} — List test scenario templates for a given purpose.
 */
public class ScenariosController {

    public void handle(Context ctx) {
        var result = PlaygroundService.scenarios(ctx.pathParam("purpose"));
        ctx.status(result.status()).json(result.body());
    }
}
