package org.julclang.wasm;

import org.julclang.tools.model.MockTransaction.DataInput;
import org.julclang.tools.model.UplcModels.ScriptInput;
import org.julclang.tools.model.VmModels.Request;
import org.julclang.tools.model.VmModels.Target;
import org.julclang.tools.model.VmModels.CostModel;
import org.julclang.vm.OptimizationCostProfiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

/** Fixed cross-runtime corpus, including repository benchmark and conformance inputs. */
public final class VmParityFixtures {
    private VmParityFixtures() {}

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        var results = JsMarshalling.JSON.createArrayNode();
        var api = JulcVmWasm.create();
        var scripts = new LinkedHashMap<String, String>();
        for (String fixture : List.of("divideInteger/divideInteger-neg-pos", "divideInteger/divideInteger-zero",
                "addInteger/addInteger-01", "trace")) {
            String name = fixture.substring(fixture.lastIndexOf('/') + 1);
            scripts.put(name, Files.readString(root.resolve("julc-vm/src/test/resources/conformance/builtin/semantics/"
                    + fixture + "/" + name + ".uplc")));
        }
        scripts.put("auction", HexFormat.of().formatHex(Files.readAllBytes(
                root.resolve("julc-benchmark/src/jmh/resources/data/auction_1-2.flat"))));
        scripts.put("budget", "(program 1.1.0 (con unit ()))");
        scripts.put("large-data", "(program 1.1.0 (lam x x))");
        scripts.put("explicit-costs", "(program 1.1.0 (con unit ()))");
        for (var entry : scripts.entrySet()) {
            var request = new Request(new ScriptInput(entry.getValue(), null, null, null),
                    entry.getKey().equals("large-data") ? List.of(new DataInput("json",
                            "{\"constructor\":18446744073709551615,\"fields\":[{\"int\":1208925819614629174706177}]}")) : List.of(),
                    new Target(null, 11), entry.getKey().equals("explicit-costs")
                            ? new CostModel(null, new Target("V3", 11), Arrays.stream(
                                    OptimizationCostProfiles.PLUTUS_V3_PV11_COSTS_V1.costModelParameters()).boxed().toList())
                            : null, entry.getKey().equals("budget") ? 0L : null, null);
            var row = results.addObject();
            row.put("name", entry.getKey());
            row.set("request", JsMarshalling.JSON.readTree(JsMarshalling.envelope(200, request)));
            String json = JsMarshalling.JSON.writeValueAsString(request);
            row.set("evaluate", JsMarshalling.JSON.readTree(api.invoke("vm.evaluate", json)));
            var opened = JsMarshalling.JSON.readTree(api.invoke("vm.debug", json));
            String sessionId = opened.at("/body/sessionId").asText();
            if (sessionId.isEmpty()) throw new IllegalStateException(opened.toString());
            row.set("debug", JsMarshalling.JSON.readTree(api.invoke("debug.act",
                    "{\"sessionId\":\"" + sessionId + "\",\"action\":\"continue\",\"step\":0}")));
            api.invoke("debug.close", "{\"sessionId\":\"" + sessionId + "\"}");
        }
        Files.writeString(Path.of(args[1]), results.toPrettyString());
    }
}
