package org.julclang.playground.api;

import org.julclang.playground.service.ExampleCatalog;
import io.javalin.http.Context;

/**
 * GET /api/examples — List all bundled Java examples.
 * GET /api/examples/{name} — Load a specific example by name.
 */
public class ExamplesController {

    private final ExampleCatalog catalog = new ExampleCatalog();

    public void list(Context ctx) {
        var result = catalog.list(ctx.queryParam("language"));
        ctx.status(result.status()).json(result.body());
    }

    public void get(Context ctx) {
        var result = catalog.get(ctx.pathParam("name"));
        ctx.status(result.status()).json(result.body());
    }
}
