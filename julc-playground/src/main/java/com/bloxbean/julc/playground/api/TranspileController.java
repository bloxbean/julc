package com.bloxbean.julc.playground.api;

import com.bloxbean.cardano.julc.jrl.JrlCompiler;
import com.bloxbean.julc.playground.model.*;
import io.javalin.http.Context;

/**
 * POST /api/transpile — Parse + Check + Transpile to Java (~100ms).
 */
public class TranspileController {

    private final JrlCompiler compiler;

    public TranspileController(JrlCompiler compiler) {
        this.compiler = compiler;
    }

    public void handle(Context ctx) {
        var req = ctx.bodyAsClass(TranspileRequest.class);
        if (req.source() == null || req.source().isBlank()) {
            ctx.json(new TranspileResponse(null,
                    java.util.List.of(new DiagnosticDto("ERROR", "JRL000", "Source is required",
                            null, null, null, null, null))));
            return;
        }

        var result = compiler.transpile(req.source(), "playground.jrl");
        var diagnostics = result.diagnostics().stream().map(DiagnosticDto::from).toList();
        ctx.json(new TranspileResponse(result.javaSource(), diagnostics));
    }
}
