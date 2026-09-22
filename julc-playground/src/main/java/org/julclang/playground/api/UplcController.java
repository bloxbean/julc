package org.julclang.playground.api;

import org.julclang.playground.model.UplcModels.DebugRequest;
import org.julclang.playground.model.UplcModels.DebugResponse;
import org.julclang.playground.model.UplcModels.DecodeRequest;
import org.julclang.playground.model.UplcModels.DecodeResponse;
import org.julclang.playground.model.UplcModels.DecompileRequest;
import org.julclang.playground.model.UplcModels.DecompileResponse;
import org.julclang.playground.model.UplcModels.EvaluateRequest;
import org.julclang.playground.model.UplcModels.EvaluateResponse;
import org.julclang.playground.sandbox.CompilationSandbox;
import org.julclang.playground.service.ServiceResult;
import org.julclang.playground.uplc.UplcToolsService;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Function;

/**
 * POST /api/uplc/decode, /api/uplc/decompile, /api/uplc/evaluate, /api/uplc/debug — tools for compiled scripts.
 * Runs inside CompilationSandbox with timeout.
 */
public class UplcController {

    private static final Logger log = LoggerFactory.getLogger(UplcController.class);

    private final UplcToolsService service = new UplcToolsService();
    private final CompilationSandbox sandbox;

    public UplcController(CompilationSandbox sandbox) {
        this.sandbox = sandbox;
    }

    public void decode(Context ctx) {
        var req = ctx.bodyAsClass(DecodeRequest.class);
        respond(ctx, () -> service.decode(req), message -> new DecodeResponse(false, message, null, null));
    }

    public void decompile(Context ctx) {
        var req = ctx.bodyAsClass(DecompileRequest.class);
        respond(ctx, () -> service.decompile(req), message -> new DecompileResponse(false, message, null, null));
    }

    public void evaluate(Context ctx) {
        var req = ctx.bodyAsClass(EvaluateRequest.class);
        respond(ctx, () -> service.evaluate(req), message -> new EvaluateResponse(false, message, null, false, null,
                0, 0, List.of(), null, null, List.of(), null, null, null, null));
    }

    public void debug(Context ctx) {
        var req = ctx.bodyAsClass(DebugRequest.class);
        respond(ctx, () -> service.debug(req), message -> new DebugResponse(false, message, null, null));
    }

    private <T> void respond(Context ctx, Callable<ServiceResult<T>> call, Function<String, T> error) {
        try {
            var result = sandbox.run(call);
            if (result.failure() != null) {
                log.error("UPLC request {} failed", ctx.path(), result.failure());
            }
            ctx.status(result.status()).json(result.body());
        } catch (CompilationSandbox.CompilationTimeoutException e) {
            ctx.status(408).json(error.apply("Request timed out (30s limit)"));
        } catch (CompilationSandbox.SandboxFullException e) {
            ctx.status(429).json(error.apply("Too many concurrent requests. Please try again."));
        } catch (Exception e) {
            log.error("UPLC request {} failed", ctx.path(), e);
            ctx.status(500).json(error.apply("Request failed"));
        }
    }
}
