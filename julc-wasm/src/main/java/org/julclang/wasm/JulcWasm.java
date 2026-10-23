package org.julclang.wasm;

import java.util.List;

public final class JulcWasm {
    /** The groups of the full image; build.gradle writes the same list to its engine.json. */
    public static final List<String> GROUPS = List.of("vm", "debug", "compiler", "uplc", "sourceDebug",
            "sourceDebugLocals");

    private JulcWasm() {}

    public static ApiRegistry create() {
        var api = new ApiRegistry();
        var vm = new VmApi();
        vm.register(api);
        FullApi.register(api, vm);
        return api;
    }

    public static void main(String[] args) {
        JsApi.install(create(), new EngineInfo("julc", "full", JsApi.julcVersion(), GROUPS));
    }
}
