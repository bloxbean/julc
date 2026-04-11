package com.bloxbean.julc.playground.api;

import com.bloxbean.cardano.julc.compiler.CompilerException;
import com.bloxbean.cardano.julc.compiler.JulcCompiler;
import com.bloxbean.cardano.julc.compiler.LibrarySourceResolver;
import com.bloxbean.cardano.julc.core.PlutusData;

import java.util.ArrayList;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.julc.vm.JulcVm;
import com.bloxbean.julc.playground.java.JavaMetadataExtractor;
import com.bloxbean.julc.playground.model.*;
import com.bloxbean.julc.playground.sandbox.CompilationSandbox;
import com.bloxbean.julc.playground.scenario.ScenarioContextBuilder;
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

    private final JulcCompiler julcCompiler;
    private final CompilationSandbox sandbox;
    private final java.util.Map<String, String> cachedLibSources;

    public EvaluateController(JulcCompiler julcCompiler, CompilationSandbox sandbox, java.util.Map<String, String> cachedLibSources) {
        this.julcCompiler = julcCompiler;
        this.sandbox = sandbox;
        this.cachedLibSources = cachedLibSources;
    }

    public void handle(Context ctx) {
        var req = ctx.bodyAsClass(EvaluateRequest.class);
        String err = InputValidator.validateSource(req.source());
        if (err != null) { ctx.json(errorResponse(err)); return; }
        err = InputValidator.validateLibrary(req.librarySource());
        if (err != null) { ctx.json(errorResponse(err)); return; }

        handleJavaEvaluate(ctx, req);
    }

    private void handleJavaEvaluate(Context ctx, EvaluateRequest req) {
        long start = System.currentTimeMillis();
        try {
            // 1. Extract metadata for context building
            var metadata = JavaMetadataExtractor.extract(req.source());
            if (!metadata.valid()) {
                ctx.json(new EvaluateResponse(false, 0, 0, List.of(), "Check failed", metadata.diagnostics()));
                return;
            }

            // 2. Compile Java → UPLC
            var cr = sandbox.run(() -> {
                var resolvedLibs = new ArrayList<>(
                        LibrarySourceResolver.resolve(req.source(), cachedLibSources));
                if (req.librarySource() != null && !req.librarySource().isBlank()) {
                    resolvedLibs.add(req.librarySource());
                }
                return julcCompiler.compile(req.source(), resolvedLibs);
            });
            if (cr.hasErrors() || cr.program() == null) {
                var diagnostics = cr.diagnostics().stream().map(DiagnosticDto::from).toList();
                ctx.json(new EvaluateResponse(false, 0, 0, List.of(), "Compilation failed", diagnostics));
                return;
            }

            var program = cr.program();

            // 3. Apply params if present
            if (req.paramValues() != null && !req.paramValues().isEmpty() && !cr.params().isEmpty()) {
                var paramData = new java.util.ArrayList<PlutusData>();
                for (var param : cr.params()) {
                    String value = req.paramValues().get(param.name());
                    if (value == null || value.isBlank()) {
                        throw new IllegalArgumentException("Missing param value: " + param.name());
                    }
                    paramData.add(ScenarioContextBuilder.convertValue(value, param.type()));
                }
                program = program.applyParams(paramData.toArray(PlutusData[]::new));
            }

            // 4. Build ScriptContext
            PlutusData datum = ScenarioContextBuilder.buildDatumFromFields(
                    metadata.datumFields(),
                    req.datum() != null ? req.datum() : Map.of());
            PlutusData redeemer = ScenarioContextBuilder.buildRedeemerFromMetadata(
                    metadata.redeemerVariants(), metadata.redeemerFields(), req.redeemer());

            PlutusData scriptContext = ScenarioContextBuilder.buildContext(
                    metadata.purpose() != null ? metadata.purpose() : "SPENDING",
                    req.scenario(), datum, redeemer);

            // 5. Evaluate
            evaluateAndRespond(ctx, program, scriptContext, start);

        } catch (CompilationSandbox.CompilationTimeoutException e) {
            log.warn("Evaluate timeout after {}ms", System.currentTimeMillis() - start);
            ctx.status(408).json(errorResponse("Compilation timed out (30s limit)"));
        } catch (CompilationSandbox.SandboxFullException e) {
            log.warn("Evaluate rejected: sandbox full");
            ctx.status(429).json(errorResponse("Too many concurrent compilations"));
        } catch (CompilerException e) {
            log.info("Evaluate compile error in {}ms: {}", System.currentTimeMillis() - start, e.getMessage());
            var diagnostics = e.diagnostics().stream().map(DiagnosticDto::from).toList();
            ctx.json(new EvaluateResponse(false, 0, 0, List.of(), "Compilation failed", diagnostics));
        } catch (IllegalArgumentException e) {
            ctx.status(400).json(errorResponse(e.getMessage()));
        } catch (Exception e) {
            log.error("Evaluate failed in {}ms", System.currentTimeMillis() - start, e);
            ctx.status(500).json(errorResponse(InputValidator.sanitizeError("Evaluation failed")));
        }
    }

    private void evaluateAndRespond(Context ctx, com.bloxbean.cardano.julc.core.Program program,
                                     PlutusData scriptContext, long start) {
        var vm = JulcVm.create();
        var result = vm.evaluateWithArgs(program, List.of(scriptContext));
        long duration = System.currentTimeMillis() - start;

        switch (result) {
            case EvalResult.Success s -> {
                log.info("Evaluate OK: cpu={} mem={} in {}ms", s.consumed().cpuSteps(), s.consumed().memoryUnits(), duration);
                ctx.json(new EvaluateResponse(
                    true, s.consumed().cpuSteps(), s.consumed().memoryUnits(),
                    s.traces(), null, List.of()));
            }
            case EvalResult.Failure f -> {
                log.info("Evaluate FAIL: {} in {}ms", f.error(), duration);
                ctx.json(new EvaluateResponse(
                    false, f.consumed().cpuSteps(), f.consumed().memoryUnits(),
                    f.traces(), f.error(), List.of()));
            }
            case EvalResult.BudgetExhausted b -> {
                log.warn("Evaluate budget exhausted in {}ms", duration);
                ctx.json(new EvaluateResponse(
                    false, b.consumed().cpuSteps(), b.consumed().memoryUnits(),
                    b.traces(), "Budget exhausted", List.of()));
            }
        }
    }

    private EvaluateResponse errorResponse(String message) {
        return new EvaluateResponse(false, 0, 0, List.of(), message,
                List.of(new DiagnosticDto("ERROR", "JULC000", message, null, null, null, null, null)));
    }
}
