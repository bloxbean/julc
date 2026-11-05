// Inserted into a Web Image launcher after `const config = new GraalVM.Config();` (julc-wasm README, step 2). The
// worker says where the module is, and hears when main stops with an error before the engine is ready.
if (globalThis.JULC_WASM_PATH) config.wasm_path = globalThis.JULC_WASM_PATH;
{
  const run = GraalVM.run;
  GraalVM.run = (...args) => run(...args).then(vm => {
    if (vm.exitCode !== 0) globalThis.onJulcExit?.(vm.exitCode);
    return vm;
  });
}
