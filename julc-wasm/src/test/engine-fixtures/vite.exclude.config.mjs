// The README's configuration for the development servers of Vite 5 to 7: no dependency pre-bundling of the packages.
export default {root: 'browser', logLevel: 'warn', build: {outDir: '../dist/vite', emptyOutDir: true},
  optimizeDeps: {exclude: ['@julclang/wasm-client', '@julclang/julc-vm-wasm', '@julclang/julc-wasm']}};
