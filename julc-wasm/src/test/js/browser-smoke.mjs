// ADR-077 M1 in Chromium: @julclang/wasm-client loads both real engines in package mode, and a release archive in
// archive mode, from pages and workers served with Content-Security-Policy: script-src 'self' 'wasm-unsafe-eval' on
// every response; string evaluation is blocked inside a worker under that policy; without 'wasm-unsafe-eval' loading
// fails promptly with its cause; package mode refuses another origin, data: and blob: URLs (I10); and a page that
// cached one release's archive loads the next release's archive (M1-C1: every script it loads is versioned).
//   node browser-smoke.mjs <package dir> <vm bundle> <full bundle> <playwright module directory>
import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {cpSync, mkdtempSync, readFileSync, statSync, writeFileSync} from 'node:fs';
import {tmpdir} from 'node:os';
import path from 'node:path';
import {pathToFileURL} from 'node:url';

const [packageDir, vmBundle, fullBundle, playwrightDir] = process.argv.slice(2).map(arg => path.resolve(arg));
const {chromium} = await import(pathToFileURL(path.join(playwrightDir, 'index.mjs')).href);
const POLICIES = {strict: "script-src 'self' 'wasm-unsafe-eval'", nowasm: "script-src 'self'",
  cache: "script-src 'self' 'wasm-unsafe-eval'"};
// Release A of the VM archive, as an earlier deploy served it: its own client version, runtime stamp and engine.json.
const OLD = '0.0.1-previous-release';
const releaseA = mkdtempSync(path.join(tmpdir(), 'julc-release-a-'));
{
  cpSync(vmBundle, releaseA, {recursive: true});
  const manifest = JSON.parse(readFileSync(path.join(vmBundle, 'engine.json'), 'utf8'));
  const swap = (file, from, to) => {
    const text = readFileSync(path.join(releaseA, file), 'utf8');
    assert.equal(text.split(from).length, 2, file);
    writeFileSync(path.join(releaseA, file), text.replace(from, to));
  };
  swap('julc-wasm.js', 'const CLIENT_VERSION = ' + JSON.stringify(manifest.julcVersion) + ';', 'const CLIENT_VERSION = ' + JSON.stringify(OLD) + ';');
  swap('julc-runtime.js', 'const julcVersion = ' + JSON.stringify(manifest.julcVersion) + ';', 'const julcVersion = ' + JSON.stringify(OLD) + ';');
  writeFileSync(path.join(releaseA, 'engine.json'), JSON.stringify({...manifest, version: 'aaaaaaaaaaaa', julcVersion: OLD}));
}
let deployed = releaseA;
const engineJs = (bundle, folder) => {
  const manifest = JSON.parse(readFileSync(path.join(bundle, 'engine.json'), 'utf8'));
  return `export const manifest = ${JSON.stringify(manifest)};
export const launcherUrl = new URL('./${folder}/${manifest.launcher}', import.meta.url);
export const moduleUrl = new URL('./${folder}/${manifest.launcher}.wasm', import.meta.url);
export const version = ${JSON.stringify(manifest.julcVersion)};
`;
};
const generated = {
  'index.html': '<!doctype html><meta charset="utf-8"><script type="module" src="page.js"></script>',
  'vm/engine.js': engineJs(vmBundle, 'engine'),
  'full/engine.js': engineJs(fullBundle, 'engine'),
  'probe-worker.js': `let result;
try { result = String((0, eval)('1 + 1')); } catch (error) { result = 'blocked: ' + error.name; }
try { new Function('return 1')(); result += '; Function allowed'; } catch (error) { result += '; Function blocked'; }
postMessage(result);`,
  // As the playground loads an archive: engine.json uncached, then the client named with ?engine=<hash>.
  'upgrade.html': '<!doctype html><meta charset="utf-8"><script type="module" src="upgrade.js"></script>',
  'upgrade.js': `const base = new URL('./archive/', import.meta.url);
const manifest = await (await fetch(new URL('engine.json', base), {cache: 'no-store'})).json();
try {
  const {createJulc} = await import(new URL('julc-wasm.js?engine=' + manifest.version, base).href);
  const client = await createJulc({baseUrl: base, manifest});
  const decoded = await client.vm.decode({script: {script: '46450101002499'}});
  window.results = {ok: true, engine: manifest.version, julcVersion: client.features().julcVersion,
    hash: decoded.info.scriptHash};
  client.dispose();
} catch (error) {
  window.results = {ok: false, engine: manifest.version, error: String(error.message)};
}
`,
  'page.js': `import {createJulc} from './node_modules/@julclang/wasm-client/index.js';
import {createJulc as createArchiveClient} from './archive-vm/julc-wasm.js';
const results = {};
const timed = async (name, start) => {
  const started = performance.now();
  try {
    const client = await start();
    const decoded = await client.vm.decode({script: {script: '46450101002499'}});
    results[name] = {ok: true, hash: decoded.info.scriptHash, version: client.version(),
      julcVersion: client.features().julcVersion, rest: typeof client.rest, ms: Math.round(performance.now() - started)};
    client.dispose();
  } catch (error) {
    results[name] = {ok: false, error: String(error.message), ms: Math.round(performance.now() - started)};
  }
};
for (const variant of ['vm', 'full']) {
  const {manifest, launcherUrl, moduleUrl, version} = await import('./' + variant + '/engine.js');
  await timed(variant, () => createJulc({manifest, launcherUrl, moduleUrl, expectedVersion: version}));
}
await timed('archive', () => createArchiveClient({baseUrl: new URL('./archive-vm/', import.meta.url)}));
const {manifest, launcherUrl, moduleUrl} = await import('./vm/engine.js');
const other = new URL(launcherUrl); other.port = String(Number(location.port) + 1);
const blob = URL.createObjectURL(new Blob(['1'], {type: 'text/javascript'})) + '#x.js';
for (const [name, change] of [['otherOrigin', {launcherUrl: other.href}], ['data', {launcherUrl: 'data:text/javascript,1//x.js'}],
  ['blob', {launcherUrl: blob}], ['credentials', {moduleUrl: moduleUrl.href.replace('//', '//user:pw@')}]]) {
  await timed(name, () => createJulc({manifest, launcherUrl, moduleUrl, ...change}));
}
results.probe = await new Promise(resolve => {
  const worker = new Worker(new URL('./probe-worker.js', import.meta.url));
  worker.onmessage = event => resolve(event.data);
  worker.onerror = event => resolve('probe failed: ' + event.message);
});
window.results = results;
`,
};
const files = (relative, policy) => {
  if (generated[relative] !== undefined) return generated[relative];
  if (policy === 'cache') {
    const file = path.join(deployed, relative.slice('archive/'.length));
    return relative.startsWith('archive/') && statSync(file, {throwIfNoEntry: false})?.isFile() ? readFileSync(file) : null;
  }
  for (const [prefix, dir] of [['node_modules/@julclang/wasm-client/', packageDir], ['vm/engine/', vmBundle],
    ['full/engine/', fullBundle], ['archive-vm/', vmBundle]]) {
    if (relative.startsWith(prefix)) {
      const file = path.join(dir, relative.slice(prefix.length));
      if (file.startsWith(dir) && statSync(file, {throwIfNoEntry: false})?.isFile()) return readFileSync(file);
    }
  }
  return null;
};
const types = {'.html': 'text/html', '.js': 'text/javascript', '.wasm': 'application/wasm', '.json': 'application/json'};
const server = createServer((request, response) => {
  const url = new URL(request.url, 'http://localhost');
  const [, policy, ...rest] = url.pathname.split('/');
  const body = POLICIES[policy] ? files(rest.join('/'), policy) : null;
  if (body === null) { response.writeHead(404).end(); return; }
  // The policy is on every response: the page, each worker script, the runtime and the launcher.
  // The upgrade case caches every file for an hour, as a static host may, except engine.json.
  const cache = policy === 'cache' && !url.pathname.endsWith('/engine.json') && !url.pathname.endsWith('.html')
    ? 'public, max-age=3600' : 'no-store';
  response.writeHead(200, {'Content-Type': types[path.extname(url.pathname)] ?? 'application/octet-stream',
    'Content-Security-Policy': POLICIES[policy], 'Cache-Control': cache});
  response.end(body);
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const port = server.address().port;
const browser = await chromium.launch();
const run = async policy => {
  const page = await browser.newPage();
  const violations = [];
  page.on('console', message => { if (/Content Security Policy|CSP/i.test(message.text())) violations.push(message.text()); });
  await page.goto(`http://127.0.0.1:${port}/${policy}/index.html`);
  await page.waitForFunction(() => window.results, null, {timeout: 240000});
  const results = await page.evaluate(() => window.results);
  await page.close();
  return {results, violations};
};
try {
  const {results, violations} = await run('strict');
  console.log(JSON.stringify(results, null, 2));
  const version = JSON.parse(readFileSync(path.join(vmBundle, 'engine.json'), 'utf8')).julcVersion;
  for (const name of ['vm', 'full', 'archive']) {
    assert.equal(results[name].ok, true, name + ': ' + results[name].error);
    assert.equal(results[name].hash, '186e32faa80a26810392fda6d559c7ed4721a65ce1c9d4ef3e1c87b4');
    assert.equal(results[name].julcVersion, version);
  }
  assert.equal(results.vm.rest, 'undefined');
  assert.equal(results.archive.rest, 'function');
  assert.match(results.probe, /^blocked: EvalError; Function blocked$/, 'string evaluation is blocked in a worker');
  for (const [name, pattern] of [['otherOrigin', /^Refused launcher URL .*is not on the worker's origin http:\/\/127\.0\.0\.1:/],
    ['data', /^Refused launcher URL .*has the refused scheme data:/], ['blob', /^Refused launcher URL .*has the refused scheme blob:/],
    ['credentials', /^Refused module URL .*has a user name or password/]]) {
    assert.equal(results[name].ok, false, name);
    assert.match(results[name].error, pattern, name);
  }
  console.log('ok CSP strict: both engines and the archive load; eval is blocked; violations reported:', violations.length);
  // The negative control: without 'wasm-unsafe-eval' the module cannot compile, and the client says so promptly.
  const control = (await run('nowasm')).results;
  for (const name of ['vm', 'full', 'archive']) {
    assert.equal(control[name].ok, false, name);
    assert.ok(control[name].ms < 30000, name + ' failed after ' + control[name].ms + ' ms');
    console.log(`ok CSP without 'wasm-unsafe-eval': ${name} refused in ${control[name].ms} ms: ${control[name].error}`);
  }
  // M1-C1: release A's files in the HTTP cache, then release B deployed in place; one browser context throughout.
  const context = await browser.newContext();
  const upgrade = async () => {
    const page = await context.newPage();
    await page.goto(`http://127.0.0.1:${port}/cache/upgrade.html`);
    await page.waitForFunction(() => window.results, null, {timeout: 240000});
    const results = await page.evaluate(() => window.results);
    await page.close();
    return results;
  };
  const first = await upgrade();
  assert.equal(first.engine, 'aaaaaaaaaaaa');
  assert.equal(first.ok, false, 'release A pairs its client with a runtime and engine of another build');
  deployed = vmBundle;
  const second = await upgrade();
  assert.equal(second.ok, true, 'after the upgrade: ' + second.error);
  assert.equal(second.julcVersion, version);
  assert.equal(second.hash, '186e32faa80a26810392fda6d559c7ed4721a65ce1c9d4ef3e1c87b4');
  await context.close();
  console.log(`ok cached release A, then release B loads: ${second.julcVersion}; A's refusal: ${first.error}`);
} finally {
  await browser.close();
  server.close();
}
