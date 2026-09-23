package org.julclang.playground.api;

import org.julclang.tools.api.InputValidator;

import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.LibrarySource;
import org.julclang.tools.model.DiagnosticDto;
import org.julclang.tools.model.EvaluateRequest;
import org.julclang.tools.model.EvaluateResponse;
import org.julclang.playground.sandbox.CompilationSandbox;
import org.julclang.tools.service.ToolsService;
import org.julclang.vm.JulcVm;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * POST /api/evaluate — Compile + Apply Params + Build Context + VM Evaluate.
 */
public class EvaluateController {

    private static final Logger log = LoggerFactory.getLogger(EvaluateController.class);

    private final ToolsService service;
    private final CompilationSandbox sandbox;

    public EvaluateController(JulcCompiler julcCompiler, CompilationSandbox sandbox, Map<String, LibrarySource> cachedLibSources) {
        this.service = new ToolsService(julcCompiler, cachedLibSources, JulcVm::create);
        this.sandbox = sandbox;
    }

    public void handle(Context ctx) {
        var req = ctx.bodyAsClass(EvaluateRequest.class);
        var invalid = ToolsService.validateEvaluate(req);
        if (invalid != null) {
            ctx.status(invalid.status()).json(invalid.body());
            return;
        }

        long start = System.currentTimeMillis();
        try {
            var result = sandbox.run(() -> service.evaluate(req));
            if (result.failure() != null) {
                log.error("Evaluate failed in {}ms", System.currentTimeMillis() - start, result.failure());
            }
            ctx.status(result.status()).json(result.body());
        } catch (CompilationSandbox.CompilationTimeoutException e) {
            log.warn("Evaluate timeout after {}ms", System.currentTimeMillis() - start);
            ctx.status(408).json(errorResponse("Compilation timed out (30s limit)"));
        } catch (CompilationSandbox.SandboxFullException e) {
            log.warn("Evaluate rejected: sandbox full");
            ctx.status(429).json(errorResponse("Too many concurrent compilations"));
        } catch (Exception e) {
            log.error("Evaluate failed in {}ms", System.currentTimeMillis() - start, e);
            ctx.status(500).json(errorResponse(InputValidator.sanitizeError("Evaluation failed")));
        }
    }

    private EvaluateResponse errorResponse(String message) {
        return new EvaluateResponse(false, 0, 0, List.of(), message,
                List.of(new DiagnosticDto("ERROR", "JULC000", message, null, null, null, null, null)));
    }
}
