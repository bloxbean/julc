package org.julclang.playground.api;

import org.julclang.playground.model.CheckRequest;
import org.julclang.playground.service.PlaygroundService;
import io.javalin.http.Context;

/**
 * POST /api/check — Parse + Type Check (~50ms).
 * Returns diagnostics and AST metadata for the test panel.
 */
public class CheckController {

    public void handle(Context ctx) {
        var req = ctx.bodyAsClass(CheckRequest.class);
        var result = PlaygroundService.check(req);
        ctx.status(result.status()).json(result.body());
    }
}
