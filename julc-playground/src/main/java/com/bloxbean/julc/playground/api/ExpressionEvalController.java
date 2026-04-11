package com.bloxbean.julc.playground.api;

import com.bloxbean.julc.playground.model.EvalExpressionRequest;
import com.bloxbean.julc.playground.model.EvalExpressionResponse;
import com.bloxbean.julc.playground.repl.PlaygroundEvaluator;
import com.bloxbean.julc.playground.sandbox.CompilationSandbox;
import io.javalin.http.Context;

import java.util.List;

/**
 * POST /api/eval — Evaluate a standalone expression.
 */
public class ExpressionEvalController {

    private final PlaygroundEvaluator evaluator;
    private final CompilationSandbox sandbox;

    public ExpressionEvalController(CompilationSandbox sandbox) {
        this.evaluator = new PlaygroundEvaluator();
        this.sandbox = sandbox;
    }

    public void handle(Context ctx) {
        var req = ctx.bodyAsClass(EvalExpressionRequest.class);
        String err = InputValidator.validateExpression(req.expression());
        if (err != null) {
            ctx.json(new EvalExpressionResponse(false, null, null, 0, 0, List.of(), err, null));
            return;
        }

        try {
            var result = sandbox.run(() -> evaluator.evaluate(req.expression()));
            ctx.json(new EvalExpressionResponse(
                    result.success(), result.result(), result.type(),
                    result.budgetCpu(), result.budgetMem(),
                    result.traces(), result.error(), result.uplc()
            ));
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
