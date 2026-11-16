// Shared by the client tests: the staged client files, the shipped Node bridge, and engines that install themselves
// through JsApi's own JavaScript (its @JS text), so the tests run the code an image runs.
import {copyFileSync, mkdtempSync, readFileSync, writeFileSync} from 'node:fs';
import {tmpdir} from 'node:os';
import path from 'node:path';
import {fileURLToPath, pathToFileURL} from 'node:url';
import {Worker} from 'node:worker_threads';

const build = new URL('../../../build/', import.meta.url);
export const archiveDir = new URL('wasm-client/archive/', build);
export const packageDir = new URL('wasm-client/package/', build);
export const npmDir = new URL('npm/', build);
export const shimUrl = new URL('node-worker.js', packageDir);
export const CLIENT_VERSION = /CLIENT_VERSION = "([^"]+)"/.exec(readFileSync(new URL('client-version.js', archiveDir), 'utf8'))[1];
export const runtimeText = readFileSync(new URL('julc-runtime.js', archiveDir), 'utf8');

/** The stamped runtime with another stamp, as another build's file would be. */
export function runtimeStamped(version) {
  const stamp = 'const julcVersion = ' + JSON.stringify(CLIENT_VERSION) + ';';
  if (!runtimeText.includes(stamp)) throw new Error('The staged runtime is not stamped with ' + CLIENT_VERSION);
  return runtimeText.replace(stamp, version === null ? '' : 'const julcVersion = ' + JSON.stringify(version) + ';')
    .replace('globalThis.JulcRuntime = { julcVersion,', version === null ? 'globalThis.JulcRuntime = {' : 'globalThis.JulcRuntime = { julcVersion,');
}

/** JsApi.install's JavaScript, as the Web Image runs it: the body of a function of the @JS arguments. */
export const jsApiInstall = (() => {
  const java = readFileSync(new URL('../../main/java/org/julclang/wasm/JsApi.java', import.meta.url), 'utf8');
  const match = /@JS\(args = \{([^}]*)\}, value = """\n([\s\S]*?)"""\)/.exec(java);
  if (!match) throw new Error('JsApi.install\'s @JS text not found');
  const args = match[1].split(',').map(arg => JSON.parse(arg.trim()));
  // A Java text block's escapes, as javac reads them.
  const escapes = {'\\': '\\', "'": "'", '"': '"', n: '\n', t: '\t'};
  return {args, body: match[2].replace(/\\(.)/g, (all, c) => {
    if (!(c in escapes)) throw new Error('Unexpected escape in JsApi\'s @JS text: ' + all);
    return escapes[c];
  })};
})();

/** The text of a launcher whose engine echoes each request, installed through JsApi's JavaScript. */
export function launcherText({schemas = {'vm.echo': {}}, groups = ['vm'], variant = 'vm', version = CLIENT_VERSION,
  julcVersion = CLIENT_VERSION, global = 'julc', source = runtimeText, before = ''} = {}) {
  return `${before}
const install = function (${jsApiInstall.args.join(', ')}) {\n${jsApiInstall.body}\n};
install(argv => JSON.stringify({status: 200, body: {method: argv[0], body: JSON.parse(argv[1])}, integers: []}),
  ${JSON.stringify(JSON.stringify(schemas))}, ${JSON.stringify(global)}, ${JSON.stringify(variant)},
  ${JSON.stringify(version)}, ${JSON.stringify(JSON.stringify(groups))}, ${JSON.stringify(julcVersion)},
  ${JSON.stringify(source)});
`;
}

/** The parent half of the bridge the npm package ships, driving the shipped shim. */
export function workerFactory(url) {
  const worker = new Worker(shimUrl, {workerData: {workerUrl: url.href}});
  const bridge = {postMessage: value => worker.postMessage(value), terminate: () => worker.terminate()};
  worker.on('message', data => bridge.onmessage?.({data}));
  worker.on('error', error => bridge.onerror?.(error));
  return bridge;
}

export const LAUNCHER = 'lang-0123456789ab.js';
/**
 * An archive directory: the staged client files, a runtime (the staged one unless given) and a launcher. Returns the
 * options for createJulc in archive mode.
 */
export function archiveBundle({launcher = launcherText(), runtime = runtimeText, rest = true, manifest = {},
  variant = 'vm', groups = ['vm']} = {}) {
  const dir = mkdtempSync(path.join(tmpdir(), 'julc-archive-'));
  copyFileSync(fileURLToPath(new URL('julc-worker.js', archiveDir)), path.join(dir, 'julc-worker.js'));
  if (runtime !== null) writeFileSync(path.join(dir, 'julc-runtime.js'), runtime);
  if (rest) copyFileSync(fileURLToPath(new URL('rest-adapter.js', archiveDir)), path.join(dir, 'rest-adapter.js'));
  writeFileSync(path.join(dir, LAUNCHER), launcher);
  writeFileSync(path.join(dir, LAUNCHER + '.wasm'), '');
  return {dir, baseUrl: pathToFileURL(dir + '/'), manifest: {api: 0, version: '0123456789ab', variant, launcher: LAUNCHER,
    groups, julcVersion: CLIENT_VERSION, ...manifest}};
}
