package org.julclang.playground.api;

import io.javalin.http.Context;
import org.julclang.compiler.LibrarySource;
import org.julclang.playground.sandbox.CompilationSandbox;
import org.julclang.tools.debug.SourceDebugService;
import org.julclang.tools.model.SourceDebugModels.ActionRequest;
import org.julclang.tools.model.SourceDebugModels.ActionResponse;
import org.julclang.tools.model.SourceDebugModels.CloseRequest;
import org.julclang.tools.model.SourceDebugModels.ChildrenRequest;
import org.julclang.tools.model.SourceDebugModels.ChildrenResponse;
import org.julclang.tools.model.SourceDebugModels.LocalsRequest;
import org.julclang.tools.model.SourceDebugModels.LocalsResponse;
import org.julclang.tools.model.SourceDebugModels.OpenRequest;
import org.julclang.tools.model.SourceDebugModels.OpenResponse;
import org.julclang.tools.service.ServiceResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Function;

/** Experimental Java-source debugger endpoints. */
public final class SourceDebugController {
    private static final Logger log = LoggerFactory.getLogger(SourceDebugController.class);

    private final SourceDebugService service;
    private final CompilationSandbox sandbox;

    public SourceDebugController(CompilationSandbox sandbox, Map<String, LibrarySource> cachedLibSources) {
        this.service = new SourceDebugService(cachedLibSources);
        this.sandbox = sandbox;
    }

    public void open(Context context) {
        var request = context.bodyAsClass(OpenRequest.class);
        respond(context, () -> service.open(request), message -> new OpenResponse(false, message, null, null,
                null, null, null, 0, List.of(), List.of(), List.of(), null, null, null, null,
                SourceDebugService.WARNING));
    }

    public void act(Context context) {
        var request = context.bodyAsClass(ActionRequest.class);
        respond(context, () -> service.act(request), message -> new ActionResponse(false, message, null, null));
    }

    public void close(Context context) {
        var result = service.close(context.bodyAsClass(CloseRequest.class));
        context.status(result.status()).json(result.body());
    }

    public void locals(Context context) {
        var request = context.bodyAsClass(LocalsRequest.class);
        respond(context, () -> service.locals(request), message ->
                new LocalsResponse(false, message, request.stopGeneration(), "unavailable", null, List.of()));
    }

    public void children(Context context) {
        var request = context.bodyAsClass(ChildrenRequest.class);
        respond(context, () -> service.children(request), message ->
                new ChildrenResponse(false, message, request.stopGeneration(), request.handle(),
                        request.start(), null, List.of()));
    }

    private <T> void respond(Context context, Callable<ServiceResult<T>> call, Function<String, T> error) {
        try {
            var result = sandbox.run(call);
            if (result.failure() != null) log.error("Source-debug request failed", result.failure());
            context.status(result.status()).json(result.body());
        } catch (CompilationSandbox.CompilationTimeoutException e) {
            context.status(408).json(error.apply("Source-debug request timed out (30s limit)"));
        } catch (CompilationSandbox.SandboxFullException e) {
            context.status(429).json(error.apply("Too many concurrent requests. Please try again."));
        } catch (Exception e) {
            log.error("Source-debug request failed", e);
            context.status(500).json(error.apply("Source-debug request failed"));
        }
    }
}
