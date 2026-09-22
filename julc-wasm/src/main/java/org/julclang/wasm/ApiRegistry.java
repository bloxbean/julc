package org.julclang.wasm;

import org.julclang.tools.service.ServiceResult;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/** Method registry shared by the two entry points; no compiler references. */
public final class ApiRegistry {
    private final Map<String, Function<String, ServiceResult<?>>> methods = new LinkedHashMap<>();
    private final Map<String, Object> schemas = new LinkedHashMap<>();

    public <T> void add(String name, Class<T> type, Function<T, ServiceResult<?>> operation) {
        schemas.put(name, JsMarshalling.schema(type));
        methods.put(name, body -> {
            try {
                return operation.apply(JsMarshalling.JSON.readValue(body, type));
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                return ServiceResult.status(400, Map.of("error", "Invalid request body: " + e.getOriginalMessage()));
            }
        });
    }

    public String invoke(String name, String body) {
        try {
            var operation = methods.get(name);
            var result = operation == null ? ServiceResult.status(404, Map.of("error", "Unknown operation: " + name))
                    : operation.apply(body);
            return JsMarshalling.envelope(result.status(), result.body());
        } catch (IllegalArgumentException e) {
            return JsMarshalling.envelope(400, Map.of("error", e.getMessage() == null ? "Invalid request" : e.getMessage()));
        } catch (Throwable e) {
            return JsMarshalling.envelope(500, Map.of("error", "Engine error: " + e.getClass().getSimpleName()));
        }
    }

    public String schemas() {
        return JsMarshalling.JSON.valueToTree(schemas).toString();
    }
}
