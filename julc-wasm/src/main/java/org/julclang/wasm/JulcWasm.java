package org.julclang.wasm;

public final class JulcWasm {
    private JulcWasm() {}

    public static ApiRegistry create() {
        var api = new ApiRegistry();
        var vm = new VmApi();
        vm.register(api);
        FullApi.register(api, vm);
        return api;
    }

    public static void main(String[] args) {
        JsApi.install(create(), "full");
    }
}
