package org.julclang.wasm;

import java.util.List;

public final class JulcVmWasm {
    /** The groups of the VM image; build.gradle writes the same list to its engine.json. */
    public static final List<String> GROUPS = List.of("vm", "debug");

    private JulcVmWasm() {}

    public static ApiRegistry create() {
        var api = new ApiRegistry();
        new VmApi().register(api);
        return api;
    }

    public static void main(String[] args) {
        JsApi.install(create(), new EngineInfo("julc", "vm", JsApi.julcVersion(), GROUPS));
    }
}
