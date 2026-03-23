package com.bloxbean.julc.playground.api;

import com.bloxbean.cardano.julc.jrl.JrlCompiler;
import com.bloxbean.julc.playground.model.*;
import com.bloxbean.julc.playground.sandbox.CompilationSandbox;
import io.javalin.http.Context;

import java.util.List;

/**
 * POST /api/compile — Full Pipeline to UPLC (2-10s).
 * Runs inside CompilationSandbox with timeout.
 */
public class CompileController {

    private final JrlCompiler compiler;
    private final CompilationSandbox sandbox;

    public CompileController(JrlCompiler compiler, CompilationSandbox sandbox) {
        this.compiler = compiler;
        this.sandbox = sandbox;
    }

    public void handle(Context ctx) {
        var req = ctx.bodyAsClass(CompileRequest.class);
        if (req.source() == null || req.source().isBlank()) {
            ctx.json(errorResponse("Source is required"));
            return;
        }

        try {
            var result = sandbox.run(() -> compiler.compile(req.source(), "playground.jrl"));
            var diagnostics = result.jrlDiagnostics().stream().map(DiagnosticDto::from).toList();

            if (result.hasErrors() || result.compileResult() == null) {
                ctx.json(new CompileResponse(null, result.generatedJavaSource(), 0, null, List.of(), diagnostics));
                return;
            }

            var cr = result.compileResult();
            var params = cr.params().stream()
                    .map(p -> new FieldDto(p.name(), p.type()))
                    .toList();

            ctx.json(new CompileResponse(
                    cr.uplcFormatted(),
                    result.generatedJavaSource(),
                    cr.scriptSizeBytes(),
                    cr.scriptSizeFormatted(),
                    params,
                    diagnostics
            ));
        } catch (CompilationSandbox.CompilationTimeoutException e) {
            ctx.status(408).json(errorResponse("Compilation timed out (30s limit)"));
        } catch (CompilationSandbox.SandboxFullException e) {
            ctx.status(429).json(errorResponse("Too many concurrent compilations. Please try again."));
        } catch (Exception e) {
            ctx.status(500).json(errorResponse("Compilation error: " + e.getMessage()));
        }
    }

    private CompileResponse errorResponse(String message) {
        return new CompileResponse(null, null, 0, null, List.of(),
                List.of(new DiagnosticDto("ERROR", "JRL000", message, null, null, null, null, null)));
    }
}
