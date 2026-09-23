package org.julclang.playground.api;

import org.julclang.tools.api.InputValidator;

import org.julclang.tools.model.EvalExpressionRequest;
import org.julclang.tools.model.EvalExpressionResponse;
import org.julclang.tools.repl.ExpressionEvaluator;
import org.julclang.playground.sandbox.CompilationSandbox;
import org.julclang.tools.service.ToolsService;
import io.javalin.http.Context;

import java.util.List;

/**
 * POST /api/eval — Evaluate a standalone expression.
 */
public class ExpressionEvalController {

    private final ExpressionEvaluator evaluator;
    private final CompilationSandbox sandbox;

    public ExpressionEvalController(CompilationSandbox sandbox) {
        this.evaluator = new ExpressionEvaluator();
        this.sandbox = sandbox;
    }

    public void handle(Context ctx) {
        var req = ctx.bodyAsClass(EvalExpressionRequest.class);
        var invalid = ToolsService.validateEvalExpression(req);
        if (invalid != null) {
            ctx.status(invalid.status()).json(invalid.body());
            return;
        }

        try {
            var result = sandbox.run(() -> ToolsService.evalExpression(evaluator, req));
            ctx.status(result.status()).json(result.body());
        } catch (CompilationSandbox.CompilationTimeoutException e) {
            ctx.status(408).json(new EvalExpressionResponse(false, null, null, 0, 0, List.of(),
                    "Evaluation timed out (30s limit)", null));
        } catch (CompilationSandbox.SandboxFullException e) {
            ctx.status(429).json(new EvalExpressionResponse(false, null, null, 0, 0, List.of(),
                    "Too many concurrent evaluations", null));
        } catch (Exception e) {
            ctx.json(new EvalExpressionResponse(false, null, null, 0, 0, List.of(),
                    InputValidator.sanitizeError("Evaluation failed"), null));
        }
    }
}
