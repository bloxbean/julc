package org.julclang.wasm;

import org.julclang.tools.service.ServiceResult;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Method registry shared by the two entry points; no compiler references. */
public final class ApiRegistry {
    private final Map<String, Function<String, ServiceResult<?>>> methods = new LinkedHashMap<>();
    private final Map<String, Object> schemas = new LinkedHashMap<>();

    /**
     * Registers {@code name} ({@code group.operation}, both JavaScript identifiers). The request type is a record;
     * in a Web Image it and every record its fields and result contain must be registered for reflection.
     */
    public <T> void add(String name, Class<T> type, Function<T, ServiceResult<?>> operation) {
        int dot = name.indexOf('.');
        if (dot < 0 || !EngineInfo.IDENTIFIER.matcher(name.substring(0, dot)).matches()
                || !EngineInfo.IDENTIFIER.matcher(name.substring(dot + 1)).matches()
                || EngineInfo.RESERVED_GROUPS.contains(name.substring(0, dot))
                || name.substring(dot + 1).equals("__proto__")) {
            throw new IllegalArgumentException("Operation name must be group.operation with unreserved JavaScript "
                    + "identifiers: " + name);
        }
        if (methods.containsKey(name)) throw new IllegalArgumentException("Operation already registered: " + name);
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

    /** The groups of the registered operations, in registration order. */
    Set<String> groups() {
        var groups = new LinkedHashSet<String>();
        for (String name : methods.keySet()) groups.add(name.substring(0, name.indexOf('.')));
        return groups;
    }
}
