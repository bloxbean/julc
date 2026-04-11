package com.bloxbean.julc.playground.api;

import com.bloxbean.cardano.julc.blueprint.BlueprintConfig;
import com.bloxbean.cardano.julc.blueprint.BlueprintGenerator;
import com.bloxbean.cardano.julc.compiler.CompileResult;
import com.bloxbean.cardano.julc.compiler.CompilerException;
import com.bloxbean.cardano.julc.compiler.JulcCompiler;
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

    private static final Logger log = LoggerFactory.getLogger(CompileController.class);

    private final JulcCompiler julcCompiler;
    private final CompilationSandbox sandbox;
    private final Map<String, String> cachedLibSources;

    public CompileController(JulcCompiler julcCompiler, CompilationSandbox sandbox, Map<String, String> cachedLibSources) {
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
            var cr = sandbox.run(() -> {
                var resolvedLibs = new ArrayList<>(
                        LibrarySourceResolver.resolve(req.source(), cachedLibSources));
                if (req.librarySource() != null && !req.librarySource().isBlank()) {
                    resolvedLibs.add(req.librarySource());
                }
                return julcCompiler.compileWithDetails(req.source(), resolvedLibs);
            });
            var diagnostics = cr.diagnostics().stream().map(DiagnosticDto::from).toList();

            if (cr.hasErrors() || cr.program() == null) {
                ctx.json(new CompileResponse(null, null, null, null, null, null,
                        0, null, List.of(), diagnostics));
                return;
            }

            var params = cr.params().stream()
                    .map(p -> new FieldDto(p.name(), p.type()))
                    .toList();

            String blueprintJson = generateBlueprint("Playground", req.source(), cr);
            var scriptInfo = extractScriptInfo(blueprintJson);

            log.info("Compile OK: {}B in {}ms", cr.scriptSizeBytes(), System.currentTimeMillis() - start);
            ctx.json(new CompileResponse(
                    cr.uplcFormatted(),
                    null,
                    cr.pirPretty(),
                    blueprintJson,
                    scriptInfo[0],
                    scriptInfo[1],
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

    /**
     * Generate CIP-57 blueprint JSON from compile result. Best-effort — returns null on failure.
     */
    private String generateBlueprint(String name, String source, CompileResult cr) {
        try {
            var config = new BlueprintConfig(name, "1.0.0");
            var compiled = new BlueprintGenerator.CompiledValidator(name, source, cr);
            var blueprint = BlueprintGenerator.generate(config, List.of(compiled));
            return blueprint.toJson();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Extract CBOR hex and script hash from blueprint JSON. Returns [cborHex, hash].
     */
    private String[] extractScriptInfo(String blueprintJson) {
        if (blueprintJson == null) return new String[]{ null, null };
        try {
            String compiledCode = extractJsonField(blueprintJson, "compiledCode");
            String hash = extractJsonField(blueprintJson, "hash");
            return new String[]{ compiledCode, hash };
        } catch (Exception e) {
            return new String[]{ null, null };
        }
    }

    private String extractJsonField(String json, String field) {
        String pattern = "\"" + field + "\": \"";
        int start = json.indexOf(pattern);
        if (start < 0) return null;
        start += pattern.length();
        int end = json.indexOf("\"", start);
        return end > start ? json.substring(start, end) : null;
    }

    private CompileResponse errorResponse(String message) {
        return new CompileResponse(null, null, null, null, null, null, 0, null, List.of(),
                List.of(new DiagnosticDto("ERROR", "JULC000", message, null, null, null, null, null)));
    }
}
