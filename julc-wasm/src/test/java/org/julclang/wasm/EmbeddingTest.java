package org.julclang.wasm;

import org.julclang.tools.service.ServiceResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
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
    }

    @Test
    void julcImagesAdvertiseTheGroupsTheyRegister() throws Exception {
        // sourceDebugLocals is a capability flag with no operations of its own (ADR-058).
        assertEquals(operationGroups(JulcWasm.create()),
                new TreeSet<>(JulcWasm.GROUPS.stream().filter(g -> !g.equals("sourceDebugLocals")).toList()));
        assertEquals(operationGroups(JulcVmWasm.create()), new TreeSet<>(JulcVmWasm.GROUPS));
    }

    private static TreeSet<String> operationGroups(ApiRegistry api) throws Exception {
        var groups = new TreeSet<String>();
        JsMarshalling.JSON.readTree(api.schemas()).fieldNames()
                .forEachRemaining(method -> groups.add(method.substring(0, method.indexOf('.'))));
        return groups;
    }
}
