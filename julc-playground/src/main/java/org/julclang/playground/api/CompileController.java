package org.julclang.playground.api;

import org.julclang.tools.api.InputValidator;

import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.LibrarySource;
import org.julclang.tools.model.CompileRequest;
import org.julclang.tools.model.CompileResponse;
import org.julclang.tools.model.DiagnosticDto;
import org.julclang.playground.sandbox.CompilationSandbox;
import org.julclang.tools.service.ToolsService;
import org.julclang.vm.JulcVm;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * POST /api/compile — Full Pipeline to UPLC (2-10s).
 * Runs inside CompilationSandbox with timeout.
 */
public class CompileController {

    private static final Logger log = LoggerFactory.getLogger(CompileController.class);

    private final ToolsService service;
    private final CompilationSandbox sandbox;

    public CompileController(JulcCompiler julcCompiler, CompilationSandbox sandbox, Map<String, LibrarySource> cachedLibSources) {
        this.service = new ToolsService(julcCompiler, cachedLibSources, JulcVm::create);
        this.sandbox = sandbox;
    }

    public void handle(Context ctx) {
        var req = ctx.bodyAsClass(CompileRequest.class);
        var invalid = ToolsService.validateCompile(req);
        if (invalid != null) {
            ctx.status(invalid.status()).json(invalid.body());
            return;
        }

        long start = System.currentTimeMillis();
        try {
            var result = sandbox.run(() -> service.compile(req));
            if (result.failure() != null) {
                log.error("Compile failed in {}ms", System.currentTimeMillis() - start, result.failure());
            }
            ctx.status(result.status()).json(result.body());
        } catch (CompilationSandbox.CompilationTimeoutException e) {
            log.warn("Compile timeout after {}ms", System.currentTimeMillis() - start);
            ctx.status(408).json(errorResponse("Compilation timed out (30s limit)"));
        } catch (CompilationSandbox.SandboxFullException e) {
            log.warn("Compile rejected: sandbox full");
            ctx.status(429).json(errorResponse("Too many concurrent compilations. Please try again."));
        } catch (Exception e) {
            log.error("Compile failed in {}ms", System.currentTimeMillis() - start, e);
            ctx.status(500).json(errorResponse(InputValidator.sanitizeError("Compilation failed")));
        }
    }

    private CompileResponse errorResponse(String message) {
        return new CompileResponse(null, null, null, null, null, null, 0, null, List.of(),
                List.of(new DiagnosticDto("ERROR", "JULC000", message, null, null, null, null, null)));
    }
}
