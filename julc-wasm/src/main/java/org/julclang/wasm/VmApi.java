package org.julclang.wasm;

import org.julclang.core.text.UplcPrettyPrinter;
import org.julclang.tools.model.UplcModels;
import org.julclang.tools.model.VmModels;
import org.julclang.tools.service.ServiceResult;
import org.julclang.tools.uplc.EvaluationPreparation;
import org.julclang.tools.uplc.EvaluationPreparation.Prepared;
import org.julclang.tools.uplc.ScriptDecoder;
import org.julclang.tools.uplc.UplcToolsService;

import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Language-independent operations. Full-only operations are registered elsewhere. */
public final class VmApi {
    public record Empty() {}
    public record PrintRequest(UplcModels.ScriptInput script, Integer width) {}
    public record Action(String sessionId, String action, Long step, UplcModels.Breakpoints breakpoints) {}
    public record Close(String sessionId) {}

    private record Session(UplcToolsService service, Prepared prepared) {}
    private final Map<String, Session> sessions = new LinkedHashMap<>();
    private long nextSession;

    public void register(ApiRegistry api) {
        var tools = new UplcToolsService();
        api.add("vm.decode", UplcModels.DecodeRequest.class, tools::decode);
        api.add("vm.hash", UplcModels.DecodeRequest.class, req -> ServiceResult.ok(
                Map.of("scriptHash", HexFormat.of().formatHex(ScriptDecoder.decode(req.script()).scriptHash()))));
        api.add("vm.prettyPrint", PrintRequest.class, req -> ServiceResult.ok(Map.of("uplcText",
                UplcPrettyPrinter.print(ScriptDecoder.decode(req.script()).program(),
                        new UplcPrettyPrinter.Options(req.width() == null ? 100 : req.width(), 2, 0)).text())));
        api.add("vm.evaluate", VmModels.Request.class, req -> evaluate(EvaluationPreparation.raw(req)));
        api.add("vm.debug", VmModels.Request.class, req -> open(EvaluationPreparation.raw(req)));
        api.add("debug.act", Action.class, req -> {
            var session = sessions.get(req.sessionId());
            if (session == null) return missing();
            var result = session.service().act(req.action(), req.step(), req.breakpoints());
            return ServiceResult.status(result.status(), withTarget(result.body(), session.prepared()));
        });
        api.add("debug.close", Close.class, req -> {
            var session = sessions.remove(req.sessionId());
            if (session == null) return missing();
            session.service().close();
            return ServiceResult.ok(Map.of("closed", true));
        });
    }

    public ServiceResult<?> evaluate(Prepared prepared) {
        var result = new UplcToolsService().evaluatePrepared(prepared);
        return ServiceResult.ok(withTarget(result.body(), prepared));
    }

    public ServiceResult<?> open(Prepared prepared) {
        var service = new UplcToolsService();
        var opened = service.open(prepared, false);
        String id = Long.toString(++nextSession);
        sessions.put(id, new Session(service, prepared));
        var result = withTarget(opened.body(), prepared);
        result.put("sessionId", id);
        return ServiceResult.ok(result);
    }

    private static Map<String, Object> withTarget(Object body, Prepared prepared) {
        // Keep the original typed fields until the lossless envelope encoder runs.
        var result = new LinkedHashMap<String, Object>();
        for (var component : body.getClass().getRecordComponents()) {
            try {
                result.put(component.getName(), component.getAccessor().invoke(body));
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Cannot marshal VM result", e);
            }
        }
        result.put("target", EvaluationPreparation.targetOf(prepared));
        result.put("costModelId", prepared.costModelId());
        return result;
    }

    private static ServiceResult<?> missing() {
        return ServiceResult.status(404, Map.of("error", "Debug session is closed or belongs to a previous worker"));
    }
}
