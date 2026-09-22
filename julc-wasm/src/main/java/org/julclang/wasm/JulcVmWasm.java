package org.julclang.wasm;

public final class JulcVmWasm {
    private JulcVmWasm() {}

    public static ApiRegistry create() {
        var api = new ApiRegistry();
        new VmApi().register(api);
        return api;
    }

    public static void main(String[] args) {
        JsApi.install(create(), "vm");
    }
}
