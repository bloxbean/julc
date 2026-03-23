package com.bloxbean.julc.playground.api;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.jrl.JrlCompiler;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.julc.vm.JulcVm;
import com.bloxbean.julc.playground.model.*;
import com.bloxbean.julc.playground.sandbox.CompilationSandbox;
import com.bloxbean.julc.playground.scenario.ScenarioContextBuilder;
import io.javalin.http.Context;

import java.util.List;
import java.util.Map;

/**
 * POST /api/evaluate — Compile + Apply Params + Build Context + VM Evaluate.
 */
public class EvaluateController {

    private final JrlCompiler compiler;
    private final CompilationSandbox sandbox;

    public EvaluateController(JrlCompiler compiler, CompilationSandbox sandbox) {
        this.compiler = compiler;
        this.sandbox = sandbox;
    }

    public void handle(Context ctx) {
        var req = ctx.bodyAsClass(EvaluateRequest.class);
        if (req.source() == null || req.source().isBlank()) {
            ctx.json(errorResponse("Source is required"));
            return;
        }

        try {
            // 1. Compile JRL → UPLC
            var compileResult = sandbox.run(() -> compiler.compile(req.source(), "playground.jrl"));
            if (compileResult.hasErrors() || compileResult.compileResult() == null) {
                var diagnostics = compileResult.jrlDiagnostics().stream().map(DiagnosticDto::from).toList();
                ctx.json(new EvaluateResponse(false, 0, 0, List.of(), "Compilation failed", diagnostics));
                return;
            }

            var program = compileResult.compileResult().program();
            var ast = compileResult.ast();

            // 2. Apply params if present
            if (req.paramValues() != null && !req.paramValues().isEmpty() && !ast.params().isEmpty()) {
                var paramData = ScenarioContextBuilder.buildParamValues(ast.params(), req.paramValues());
                program = program.applyParams(paramData.toArray(PlutusData[]::new));
            }

            // 3. Build ScriptContext
            PlutusData scriptContext = ScenarioContextBuilder.buildContext(
                    ast, req.scenario(),
                    req.datum() != null ? req.datum() : Map.of(),
                    req.redeemer());

            // 4. Evaluate
            var vm = JulcVm.create();
            var result = vm.evaluateWithArgs(program, List.of(scriptContext));

            switch (result) {
                case EvalResult.Success s -> ctx.json(new EvaluateResponse(
                        true, s.consumed().cpuSteps(), s.consumed().memoryUnits(),
                        s.traces(), null, List.of()));
                case EvalResult.Failure f -> ctx.json(new EvaluateResponse(
                        false, f.consumed().cpuSteps(), f.consumed().memoryUnits(),
                        f.traces(), f.error(), List.of()));
                case EvalResult.BudgetExhausted b -> ctx.json(new EvaluateResponse(
                        false, b.consumed().cpuSteps(), b.consumed().memoryUnits(),
                        b.traces(), "Budget exhausted", List.of()));
            }

        } catch (CompilationSandbox.CompilationTimeoutException e) {
            ctx.status(408).json(errorResponse("Compilation timed out (30s limit)"));
        } catch (CompilationSandbox.SandboxFullException e) {
            ctx.status(429).json(errorResponse("Too many concurrent compilations"));
        } catch (IllegalArgumentException e) {
            ctx.status(400).json(errorResponse(e.getMessage()));
        } catch (Exception e) {
            ctx.status(500).json(errorResponse("Evaluation error: " + e.getMessage()));
        }
    }

    private EvaluateResponse errorResponse(String message) {
        return new EvaluateResponse(false, 0, 0, List.of(), message,
                List.of(new DiagnosticDto("ERROR", "JRL000", message, null, null, null, null, null)));
    }
}
