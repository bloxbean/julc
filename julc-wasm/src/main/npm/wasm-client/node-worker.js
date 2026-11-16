/* The worker half of the Node bridge (ADR-077 D3). It gives julc-worker.js a classic worker's globals, marks the
 * worker as Node's (self.JULC_NODE), and loads the Web Image launcher's unchanged bytes as a CommonJS function, so the
 * module type of the launcher's directory does not matter. A plain file without import statements: Node's builtins
 * come from process.getBuiltinModule (Node 22.3 or later), and bundlers copy it as it is. */
const {parentPort, workerData} = process.getBuiltinModule('node:worker_threads');
const {readFileSync} = process.getBuiltinModule('node:fs');
const vm = process.getBuiltinModule('node:vm');
const path = process.getBuiltinModule('node:path');
const {fileURLToPath} = process.getBuiltinModule('node:url');
const {createRequire} = process.getBuiltinModule('node:module');

/** A file: URL as a path; the worker refuses other URLs first. */
function filename(url) {
  if (!String(url).startsWith('file:')) throw new Error('Only file: URLs load under Node.js: ' + url);
  return fileURLToPath(url);
}
globalThis.self = globalThis;
globalThis.postMessage = value => parentPort.postMessage(value);
globalThis.addEventListener = (name, callback) => {
  if (name === 'unhandledrejection') process.on('unhandledRejection', reason => callback({reason}));
};
globalThis.importScripts = (...urls) => {
  for (const url of urls) {
    const file = filename(url);
    vm.runInThisContext(readFileSync(file, 'utf8'), {filename: file});
  }
};
globalThis.JULC_NODE = Object.freeze({
  /** The launcher, with require, module, exports, __filename and __dirname injected; its module from a path. */
  loadLauncher(url) {
    const file = filename(url);
    globalThis.JULC_WASM_PATH = filename(globalThis.JULC_WASM_PATH);
    const launcher = vm.compileFunction(readFileSync(file, 'utf8'),
      ['exports', 'require', 'module', '__filename', '__dirname'], {filename: file});
    const module = {exports: {}};
    launcher.call(module.exports, module.exports, createRequire(file), module, file, path.dirname(file));
  },
});
const workerFile = filename(workerData.workerUrl);
vm.runInThisContext(readFileSync(workerFile, 'utf8'), {filename: workerFile});
parentPort.on('message', data => globalThis.onmessage({data}));
