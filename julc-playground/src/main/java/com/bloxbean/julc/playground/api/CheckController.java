package com.bloxbean.julc.playground.api;

import com.bloxbean.julc.playground.java.JavaMetadataExtractor;
import com.bloxbean.julc.playground.model.*;
import io.javalin.http.Context;

import java.util.List;

/**
 * POST /api/check — Parse + Type Check (~50ms).
 * Returns diagnostics and AST metadata for the test panel.
 */
public class CheckController {

    public void handle(Context ctx) {
        var req = ctx.bodyAsClass(CheckRequest.class);
        String err = InputValidator.validateSource(req.source());
        if (err != null) {
            ctx.json(errorResponse(err));
            return;
        }

        ctx.json(JavaMetadataExtractor.extract(req.source()));
    }

    private CheckResponse errorResponse(String message) {
        return new CheckResponse(false, null, null, List.of(), null, List.of(), List.of(), List.of(),
                List.of(new DiagnosticDto("ERROR", "JULC000", message, null, null, null, null, null)));
    }
}
