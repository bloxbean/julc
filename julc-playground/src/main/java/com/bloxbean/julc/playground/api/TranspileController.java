package com.bloxbean.julc.playground.api;

import io.javalin.http.Context;

/**
 * TranspileController is disabled (JRL support removed from playground).
 * Kept as a stub to avoid breaking any references.
 */
public class TranspileController {

    public void handle(Context ctx) {
        ctx.status(404).json(java.util.Map.of("error", "Transpile endpoint is not available"));
    }
}
