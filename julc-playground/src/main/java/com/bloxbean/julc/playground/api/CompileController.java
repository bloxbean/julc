package com.bloxbean.julc.playground.api;

import com.bloxbean.cardano.julc.blueprint.BlueprintConfig;
import com.bloxbean.cardano.julc.blueprint.BlueprintGenerator;
import com.bloxbean.cardano.julc.compiler.CompileResult;
import com.bloxbean.cardano.julc.compiler.CompilerException;
import com.bloxbean.cardano.julc.compiler.JulcCompiler;
import com.bloxbean.cardano.julc.compiler.LibrarySource;
import com.bloxbean.cardano.julc.compiler.LibrarySourceResolver;
import com.bloxbean.julc.playground.model.*;

import java.util.Map;
import com.bloxbean.julc.playground.sandbox.CompilationSandbox;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * POST /api/compile — Full Pipeline to UPLC (2-10s).
 * Runs inside CompilationSandbox with timeout.
 */
public class CompileController {

    private record PlaygroundCompilation(
            CompileResult result,
            com.bloxbean.cardano.julc.compiler.schema.ContractSchema contractSchema) {}

    private static final Logger log = LoggerFactory.getLogger(CompileController.class);

    private final JulcCompiler julcCompiler;
    private final CompilationSandbox sandbox;
    private final Map<String, LibrarySource> cachedLibSources;

    public CompileController(JulcCompiler julcCompiler, CompilationSandbox sandbox, Map<String, LibrarySource> cachedLibSources) {
        this.julcCompiler = julcCompiler;
        this.sandbox = sandbox;
        this.cachedLibSources = cachedLibSources;
    }

    public void handle(Context ctx) {
        var req = ctx.bodyAsClass(CompileRequest.class);
        String err = InputValidator.validateSource(req.source());
        if (err != null) { ctx.json(errorResponse(err)); return; }
        err = InputValidator.validateLibrary(req.librarySource());
        if (err != null) { ctx.json(errorResponse(err)); return; }

        handleJavaCompile(ctx, req);
    }

    private void handleJavaCompile(Context ctx, CompileRequest req) {
        long start = System.currentTimeMillis();
        try {
            var compiled = sandbox.run(() -> {
                var resolvedLibs = new ArrayList<>(
                        LibrarySourceResolver.resolve(req.source(), cachedLibSources));
                if (req.librarySource() != null && !req.librarySource().isBlank()) {
                    resolvedLibs.add(req.librarySource());
                }
                if (req.blueprintEnabled()) {
                    var result = julcCompiler.compileContractWithDetails(req.source(), resolvedLibs);
                    return new PlaygroundCompilation(
                            result.compileResult(), result.contractSchema());
                }
                return new PlaygroundCompilation(
                        julcCompiler.compileWithDetails(req.source(), resolvedLibs), null);
            });
            var cr = compiled.result();
            var diagnostics = cr.diagnostics().stream().map(DiagnosticDto::from).toList();

            if (cr.hasErrors() || cr.program() == null) {
                ctx.json(new CompileResponse(null, null, null, null, null, null,
                        0, null, List.of(), diagnostics));
                return;
            }

            var params = cr.params().stream()
                    .map(p -> new FieldDto(p.name(), p.type()))
                    .toList();

            String blueprintJson = null;
            if (req.blueprintEnabled()) {
                try {
                    blueprintJson = generateBlueprint(
                            "Playground", cr, compiled.contractSchema());
                } catch (IllegalArgumentException e) {
                    log.info("Blueprint error in {}ms: {}",
                            System.currentTimeMillis() - start, e.getMessage());
                    ctx.status(422).json(errorResponse(
                            InputValidator.sanitizeError(e.getMessage())));
                    return;
                }
            }
            String compiledCode = BlueprintGenerator.compiledCode(cr.program());
            String scriptHash = BlueprintGenerator.scriptHash(cr.program());

            log.info("Compile OK: {}B in {}ms", cr.scriptSizeBytes(), System.currentTimeMillis() - start);
            ctx.json(new CompileResponse(
                    cr.uplcFormatted(),
                    null,
                    cr.pirPretty(),
                    blueprintJson,
                    compiledCode,
                    scriptHash,
                    cr.scriptSizeBytes(),
                    cr.scriptSizeFormatted(),
                    params,
                    diagnostics
            ));
        } catch (CompilationSandbox.CompilationTimeoutException e) {
            log.warn("Compile timeout after {}ms", System.currentTimeMillis() - start);
            ctx.status(408).json(errorResponse("Compilation timed out (30s limit)"));
        } catch (CompilationSandbox.SandboxFullException e) {
            log.warn("Compile rejected: sandbox full");
            ctx.status(429).json(errorResponse("Too many concurrent compilations. Please try again."));
        } catch (CompilerException e) {
            log.info("Compile error in {}ms: {}", System.currentTimeMillis() - start, e.getMessage());
            var diagnostics = e.diagnostics().stream().map(DiagnosticDto::from).toList();
            ctx.json(new CompileResponse(null, null, null, null, null, null,
                    0, null, List.of(), diagnostics));
        } catch (Exception e) {
            log.error("Compile failed in {}ms", System.currentTimeMillis() - start, e);
            ctx.status(500).json(errorResponse(InputValidator.sanitizeError("Compilation failed")));
        }
    }

    /** Generate a validated CIP-57 blueprint or fail the request. */
    private String generateBlueprint(
            String name,
            CompileResult cr,
            com.bloxbean.cardano.julc.compiler.schema.ContractSchema contractSchema) {
        var config = new BlueprintConfig(name, "1.0.0");
        var compiled = new BlueprintGenerator.CompiledValidator(name, cr, contractSchema);
        var blueprint = BlueprintGenerator.generate(config, List.of(compiled));
        return blueprint.toJson();
    }

    private CompileResponse errorResponse(String message) {
        return new CompileResponse(null, null, null, null, null, null, 0, null, List.of(),
                List.of(new DiagnosticDto("ERROR", "JULC000", message, null, null, null, null, null)));
    }
}
