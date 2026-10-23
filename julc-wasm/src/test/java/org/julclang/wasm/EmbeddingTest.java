package org.julclang.wasm;

import org.julclang.tools.service.ServiceResult;
import org.julclang.wasm.fixture.DemoLangWasm;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/** The contract an image another language builds on julc-wasm relies on (ADR-064). */
class EmbeddingTest {
    public record Sources(Map<String, String> sources, String module) {}

    @Test
    void anotherLanguageComposesItsOperationsWithTheVmOperations() throws Exception {
        var api = new ApiRegistry();
        new VmApi().register(api);
        api.add("lang.compile", Sources.class,
                request -> ServiceResult.ok(Map.of("module", request.module(), "files", request.sources().size())));

        var compiled = JsMarshalling.JSON.readTree(api.invoke("lang.compile",
                "{\"sources\":{\"Main.elm\":\"module Main\"},\"module\":\"Main\"}"));
        assertEquals(200, compiled.get("status").intValue());
        assertEquals("Main", compiled.at("/body/module").asText());
        assertEquals(1, compiled.at("/body/files").intValue());

        // The VM operations behave exactly as in JuLC's own VM image.
        String request = "{\"script\":{\"script\":\"(program 1.1.0 (con integer 42))\"},\"args\":[]}";
        assertEquals(JulcVmWasm.create().invoke("vm.evaluate", request), api.invoke("vm.evaluate", request));

        var schemas = JsMarshalling.JSON.readTree(api.schemas());
        assertTrue(schemas.has("vm.evaluate"));
        assertEquals("string", schemas.at("/lang.compile/sources/$map").asText());
        assertFalse(schemas.has("compiler.compile"), "the JuLC compiler is registered only by FullApi");
    }

    @Test
    void malformedRequestsAndUnknownOperationsAreEnvelopesNotExceptions() throws Exception {
        var api = new ApiRegistry();
        api.add("lang.compile", Sources.class, request -> ServiceResult.ok(Map.of()));
        assertEquals(400, JsMarshalling.JSON.readTree(api.invoke("lang.compile", "{")).get("status").intValue());
        assertEquals(404, JsMarshalling.JSON.readTree(api.invoke("lang.missing", "{}")).get("status").intValue());
    }

    @Test
    void theDemoLanguageImageRunsItsOperationsBesideTheVmOperations() throws Exception {
        var api = DemoLangWasm.create();
        var sum = JsMarshalling.JSON.readTree(api.invoke("demo.sum",
                "{\"terms\":[{\"name\":\"a\",\"value\":\"1208925819614629174706177\"},"
                        + "{\"name\":\"b\",\"value\":\"-1\"}],\"scale\":\"1099511627776\"}"));
        assertEquals(200, sum.get("status").intValue());
        assertEquals("1329227995784915872903807060280344576", sum.at("/body/total").asText()); // 2^120
        assertEquals("[[\"total\"],[\"count\"]]", sum.get("integers").toString());
        assertEquals(400, JsMarshalling.JSON.readTree(api.invoke("demo.sum", "{}")).get("status").intValue());

        String request = "{\"script\":{\"script\":\"(program 1.1.0 (con integer 42))\"},\"args\":[]}";
        assertEquals(JulcVmWasm.create().invoke("vm.evaluate", request), api.invoke("vm.evaluate", request));
        var engine = new EngineInfo("demolang", "demo", DemoLangWasm.VERSION, DemoLangWasm.GROUPS);
        engine.requireAdvertises(api);
        assertEquals(new TreeSet<>(DemoLangWasm.GROUPS), operationGroups(api));
        var missing = new EngineInfo("demolang", "demo", DemoLangWasm.VERSION, List.of("vm", "debug"));
        var error = assertThrows(IllegalArgumentException.class, () -> missing.requireAdvertises(api));
        assertTrue(error.getMessage().contains("group demo"), error.getMessage());
    }

    @Test
    void operationNamesAreGroupDotOperationAndRegisteredOnce() {
        var api = new ApiRegistry();
        for (String name : List.of("compile", "lang.", ".compile", "lang.compile.x", "1lang.compile",
                "lang.com-pile", "rest.call", "version.get", "constructor.x", "toString.x", "lang.__proto__")) {
            assertThrows(IllegalArgumentException.class,
                    () -> api.add(name, Sources.class, request -> ServiceResult.ok(Map.of())), name);
        }
        api.add("lang.compile", Sources.class, request -> ServiceResult.ok(Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> api.add("lang.compile", Sources.class, request -> ServiceResult.ok(Map.of())));
        assertEquals(Set.of("lang"), api.groups());
    }

    @Test
    void engineInfoAcceptsOnlyNamesJavascriptCanUse() {
        var groups = List.of("vm", "debug", "lang");
        var info = new EngineInfo("elci", "full", "0.1.0", groups);
        assertEquals(groups, info.groups());
        assertThrows(IllegalArgumentException.class, () -> new EngineInfo("el ci", "full", "1", groups));
        assertThrows(IllegalArgumentException.class, () -> new EngineInfo("1elci", "full", "1", groups));
        assertThrows(IllegalArgumentException.class, () -> new EngineInfo("elci", "Full", "1", groups));
        assertThrows(IllegalArgumentException.class, () -> new EngineInfo("elci", "full", " ", groups));
        assertThrows(IllegalArgumentException.class, () -> new EngineInfo("elci", "full", "1", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new EngineInfo("elci", "full", "1", List.of("a.b")));
        assertThrows(NullPointerException.class, () -> new EngineInfo("elci", "full", "1", null));
        // Names the client, the runtime or every JavaScript object already uses.
        for (String group : List.of("rest", "dispose", "version", "features", "__schemas", "__proto__", "constructor",
                "toString", "hasOwnProperty")) {
            assertThrows(IllegalArgumentException.class, () -> new EngineInfo("elci", "elci", "1", List.of(group)),
                    group);
        }
        for (String global : List.of("JulcRuntime", "JulcRestAdapter", "onJulcReady", "onJulcExit", "JULC_WASM_PATH",
                "GraalVM", "__proto__")) {
            assertThrows(IllegalArgumentException.class, () -> new EngineInfo(global, "elci", "1", groups), global);
        }
    }

    @Test
    void julcImagesAdvertiseTheGroupsTheyRegister() throws Exception {
        new EngineInfo("julc", "full", "test", JulcWasm.GROUPS).requireAdvertises(JulcWasm.create());
        new EngineInfo("julc", "vm", "test", JulcVmWasm.GROUPS).requireAdvertises(JulcVmWasm.create());
        // sourceDebugLocals is a capability flag with no operations of its own (ADR-058).
        assertEquals(operationGroups(JulcWasm.create()),
                new TreeSet<>(JulcWasm.GROUPS.stream().filter(g -> !g.equals("sourceDebugLocals")).toList()));
        assertEquals(operationGroups(JulcVmWasm.create()), new TreeSet<>(JulcVmWasm.GROUPS));
    }

    @Test
    void julcCostModelFieldsAreTheRecordTheProfileShorthandNeeds() throws Exception {
        // julc-runtime.js expands a profile name only where costModel is a record with a profile field (ADR-064 D2b).
        var withCostModel = new TreeSet<String>();
        var schemas = JsMarshalling.JSON.readTree(JulcWasm.create().schemas());
        schemas.fieldNames().forEachRemaining(method -> {
            var costModel = schemas.get(method).get("costModel");
            if (costModel == null) return;
            withCostModel.add(method);
            assertTrue(costModel.isObject() && "string".equals(costModel.path("profile").asText()), method);
        });
        assertTrue(withCostModel.containsAll(Set.of("vm.evaluate", "uplc.evaluateTransaction")), withCostModel::toString);
    }

    private static TreeSet<String> operationGroups(ApiRegistry api) throws Exception {
        var groups = new TreeSet<String>();
        JsMarshalling.JSON.readTree(api.schemas()).fieldNames()
                .forEachRemaining(method -> groups.add(method.substring(0, method.indexOf('.'))));
        return groups;
    }
}
