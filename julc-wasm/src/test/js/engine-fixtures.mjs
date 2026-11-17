// ADR-077 M3 (I6, I8): the engine packages @julclang/julc-vm-wasm and @julclang/julc-wasm, installed with
// @julclang/wasm-client from their packed tarballs (never linked), in the environments the packages claim. Every
// fixture runs src/test/engine-fixtures/shared/check.js through the packages' entry points: vm.decode, vm.evaluate,
// vm.applyParams on the fixed vector and, for the full engine, a compile. The Node.js and plain-browser fixtures also
// run the VM parity fixtures, and point the packages' exported URLs at the other engine before creating each client.
// Each fixture states which launcher bytes it must ship (on disk and as served), and fails otherwise.
//
// The tools (Vite, webpack, esbuild, Next.js) are locked: src/test/engine-fixtures/tooling/<app|next|vite7> hold a
// package.json and package-lock.json each. A fixture directory is installed with npm ci --ignore-scripts from them,
// then the three tarballs are added without saving, and the installed tree is checked against the lock. To move a
// tool to another version, edit its package.json there and regenerate the lock:
//   npm install --package-lock-only --ignore-scripts   (in that tooling directory), then commit both files.
//
//   node engine-fixtures.mjs --npm <dir of the three tarballs> --version <JuLC version> --work <empty work dir>
//     [--parity <vm.json>] [--playwright <playwright package dir>] [--nodes <node binary>,...] [--only <fixture>,...]
//
// Fixtures: node, plain, vite, webpack, webpack-keep, extension, webpack-node, esbuild, next, next-dev,
// next-webpack, vite7-dev. All run by default except vite7-dev; the browser fixtures need --playwright.
import assert from 'node:assert/strict';
import {spawn, spawnSync} from 'node:child_process';
import {createHash} from 'node:crypto';
import {cpSync, existsSync, mkdirSync, readFileSync, readdirSync, rmSync, statSync, writeFileSync} from 'node:fs';
import {createServer} from 'node:http';
import {createServer as createNetServer} from 'node:net';
import path from 'node:path';
import {fileURLToPath, pathToFileURL} from 'node:url';

const args = Object.fromEntries(process.argv.slice(2).reduce((pairs, arg, i, all) =>
  arg.startsWith('--') ? [...pairs, [arg.slice(2), all[i + 1]]] : pairs, []));
for (const required of ['npm', 'version', 'work']) if (!args[required]) throw new Error('Missing --' + required);
const version = args.version;
const work = path.resolve(args.work);
const fixturesDir = fileURLToPath(new URL('../engine-fixtures/', import.meta.url));
const nodes = [process.execPath, ...(args.nodes ? args.nodes.split(',').filter(Boolean) : [])];
const ALL = ['node', 'plain', 'vite', 'webpack', 'webpack-keep', 'extension', 'webpack-node', 'esbuild', 'next',
  'next-dev', 'next-webpack'];
const only = new Set(args.only ? args.only.split(',') : ALL);
for (const name of only) if (![...ALL, 'vite7-dev'].includes(name)) throw new Error('Unknown fixture ' + name);
const BROWSER = ['plain', 'vite', 'webpack', 'webpack-keep', 'extension', 'vite7-dev'];
if (BROWSER.some(name => only.has(name)) && !args.playwright) throw new Error('The browser fixtures need --playwright');
const tarballs = ['wasm-client', 'julc-vm-wasm', 'julc-wasm'].map(name => {
  const file = path.resolve(args.npm, `julclang-${name}-${version}.tgz`);
  if (!existsSync(file)) throw new Error('Missing ' + file);
  return file;
});
const results = [];
const env = {...process.env, NEXT_TELEMETRY_DISABLED: '1', NO_COLOR: '1'};

// --- helpers ---
const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');
function run(command, commandArgs, cwd, {timeout = 600000, extraEnv = {}} = {}) {
  const result = spawnSync(command, commandArgs, {cwd, env: {...env, ...extraEnv}, encoding: 'utf8', timeout,
    maxBuffer: 64 * 1024 * 1024});
  if (result.status !== 0) {
    throw new Error(`${command} ${commandArgs.join(' ')} in ${cwd} failed (${result.status ?? result.signal}):\n`
      + (result.stdout + result.stderr).slice(-4000));
  }
  return result.stdout;
}
/** The last JSON line a program printed. */
const lastJson = text => JSON.parse(text.trim().split('\n').filter(line => line.startsWith('{')).pop());
const NPM = ['--ignore-scripts', '--no-audit', '--no-fund'];
/** The locked tools of src/test/engine-fixtures/tooling/<name>, installed with npm ci, unchanged by what follows. */
function installTooling(dir, name) {
  mkdirSync(dir, {recursive: true});
  for (const file of ['package.json', 'package-lock.json']) {
    cpSync(path.join(fixturesDir, 'tooling', name, file), path.join(dir, file));
  }
  run('npm', ['ci', ...NPM], dir);
}
/** Every package the lock names is installed at its locked version (platform packages of other systems aside). */
function checkLocked(dir, name) {
  const lock = JSON.parse(readFileSync(path.join(fixturesDir, 'tooling', name, 'package-lock.json'), 'utf8'));
  for (const [where, entry] of Object.entries(lock.packages)) {
    if (!where) continue;
    const manifest = path.join(dir, where, 'package.json');
    if (!existsSync(manifest)) {
      assert.ok(entry.optional, `${name}: ${where} ${entry.version} is locked but not installed`);
      continue;
    }
    assert.equal(JSON.parse(readFileSync(manifest, 'utf8')).version, entry.version, `${name}: ${where} moved`);
  }
}
/** A fixture directory: the locked tools, then the three tarballs, added without re-resolving the tools. */
function install(dir, name) {
  installTooling(dir, name);
  run('npm', ['install', ...tarballs, '--no-save', ...NPM], dir);
  checkLocked(dir, name);
  for (const name of ['wasm-client', 'julc-vm-wasm', 'julc-wasm']) {
    const installed = path.join(dir, 'node_modules', '@julclang', name);
    assert.ok(!statSync(installed, {throwIfNoEntry: false})?.isSymbolicLink(), name + ' must be installed, not linked');
    assert.equal(JSON.parse(readFileSync(path.join(installed, 'package.json'), 'utf8')).version, version);
  }
  cpSync(path.join(fixturesDir, 'shared'), path.join(dir, 'shared'), {recursive: true});
}
const launchers = dir => Object.fromEntries(['julc-vm-wasm', 'julc-wasm'].map(name => {
  const engine = path.join(dir, 'node_modules', '@julclang', name, 'engine');
  const manifest = JSON.parse(readFileSync(path.join(engine, 'engine.json'), 'utf8'));
  return [name, sha256(readFileSync(path.join(engine, manifest.launcher)))];
}));
const walk = dir => readdirSync(dir, {withFileTypes: true}).flatMap(entry => entry.isDirectory()
  ? walk(path.join(dir, entry.name)) : [path.join(dir, entry.name)]);
/** Whether a bundler's output holds each launcher byte for byte. */
function launcherIdentity(appDir, outputDir) {
  const digests = new Map(walk(outputDir).filter(file => /\.(c|m)?js$/.test(file))
    .map(file => [sha256(readFileSync(file)), path.relative(outputDir, file)]));
  return Object.fromEntries(Object.entries(launchers(appDir)).map(([name, digest]) =>
    [name, digests.has(digest) ? 'identical (' + digests.get(digest) + ')' : 'changed']));
}
/** Whether the browser was served each launcher byte for byte (works for development servers too). */
function servedIdentity(appDir, outcome) {
  if (!outcome.served) return undefined;
  return Object.fromEntries(Object.entries(launchers(appDir)).map(([name, digest]) =>
    [name, outcome.served.get(digest) ?? 'changed or not seen']));
}
/**
 * Why the launcher bytes break the rule, or null. Rules: 'identical' (byte for byte), 'dev' (identical, or the same
 * code with a development server's source-map comment appended). launcher: on disk; servedLauncher: as served.
 */
function identityProblem(entry, rules) {
  const allowed = {identical: value => value.startsWith('identical'),
    dev: value => value.startsWith('identical') || value === 'same code + source-map comment'};
  for (const [kind, rule, values] of [['on disk', rules.disk, entry.launcher], ['served', rules.served, entry.servedLauncher]]) {
    if (!rule) continue;
    if (!values) return `launcher ${kind}: not measured`;
    for (const [name, value] of Object.entries(values)) {
      if (!allowed[rule](value)) return `launcher ${kind} for ${name}: ${value}, expected ${rule}`;
    }
  }
  return null;
}
/**
 * A run's result entry: its outcome, what was measured, and the verdict. extra.expect ({disk, served}) is the launcher
 * identity the fixture must show; a run that works but ships other launcher bytes fails with launcherProblem set.
 */
function classify(fixture, environment, outcome, extra = {}) {
  const {served, ...rest} = outcome;
  const {expect, ...measured} = extra;
  const entry = {fixture, environment, ...rest, ...measured, ...(expect ? {expectedLauncher: expect} : {})};
  const problem = entry.ok && expect ? identityProblem(entry, expect) : null;
  if (problem) Object.assign(entry, {ok: false, error: problem, launcherProblem: true});
  return entry;
}
/** Records a run, classified as above. */
function record(fixture, environment, outcome, extra = {}) {
  const entry = classify(fixture, environment, outcome, extra);
  results.push(entry);
  console.log(`${entry.ok ? 'PASS' : 'FAIL'} ${fixture} | ${environment}`
    + (entry.ok ? ' | ' + summary(entry.report) : ' | ' + String(entry.error).slice(0, 2000))
    + (entry.launcher ? ' | launcher on disk ' + JSON.stringify(entry.launcher) : '')
    + (entry.servedLauncher ? ' | launcher served ' + JSON.stringify(entry.servedLauncher) : ''));
}
const summary = report => (report ?? []).map(r => `${r.variant} ready ${r.ready} ms, total ${r.total} ms`
  + (r.parity ? `, parity ${r.parity}` : '') + (r.compiled ? ', compiled' : '')
  + (r.forwardingRefused ? ', forwarding adapter refused' : '')).join('; ');
/** The expected report: both engines, this version, the compile done. */
function verify(outcome, parity, {forwarding = false} = {}) {
  if (!outcome.ok) return outcome;
  try {
    assert.deepEqual(outcome.report.map(r => r.variant), ['vm', 'full']);
    for (const r of outcome.report) assert.equal(r.version, version, r.variant + ' version');
    assert.match(outcome.report[1].compiled ?? '', /^[0-9a-f]{56}$/);
    for (const r of outcome.report) assert.equal(r.redirected, true, r.variant + ' created with redirected exports');
    if (forwarding) for (const r of outcome.report) assert.equal(r.forwardingRefused, true, r.variant + ' forwarding');
    if (parity) for (const r of outcome.report) assert.equal(r.parity, parity, r.variant + ' parity fixtures run');
    return outcome;
  } catch (error) {
    return {ok: false, error: error.message, report: outcome.report, served: outcome.served};
  }
}
/** Runs a fixture, which records its own results; a fixture that throws (a failed install or build) is a failure. */
async function guard(fixture, environment, body) {
  try {
    await body();
  } catch (error) {
    record(fixture, environment, {ok: false, error: String(error?.stack ?? error)});
  }
}
const freePort = () => new Promise((resolve, reject) => {
  const server = createNetServer();
  server.on('error', reject);
  server.listen(0, '127.0.0.1', () => { const {port} = server.address(); server.close(() => resolve(port)); });
});
/** Starts a long-running program and waits until url answers. */
async function startProgram(command, commandArgs, cwd, url, extraEnv = {}) {
  const child = spawn(command, commandArgs, {cwd, env: {...env, ...extraEnv}, stdio: ['ignore', 'pipe', 'pipe'],
    detached: process.platform !== 'win32'});
  let log = '';
  child.stdout.on('data', data => { log += data; });
  child.stderr.on('data', data => { log += data; });
  // Ends the program's whole process group, and waits for it, so it writes nothing afterwards.
  const stop = async () => {
    if (child.exitCode !== null || child.signalCode !== null) return;
    const exited = new Promise(resolve => child.once('exit', resolve));
    try { process.kill(-child.pid, 'SIGTERM'); } catch { child.kill('SIGTERM'); }
    const timer = setTimeout(() => { try { process.kill(-child.pid, 'SIGKILL'); } catch { child.kill('SIGKILL'); } }, 10000);
    await exited;
    clearTimeout(timer);
  };
  const deadline = Date.now() + 180000;
  while (Date.now() < deadline) {
    if (child.exitCode !== null) throw new Error(`${command} exited with ${child.exitCode}:\n${log.slice(-3000)}`);
    try {
      await fetch(url, {signal: AbortSignal.timeout(2000)});
      return {stop, log: () => log};
    } catch {
      await new Promise(resolve => setTimeout(resolve, 500));
    }
  }
  await stop();
  throw new Error(`${command} did not answer at ${url}:\n${log.slice(-3000)}`);
}
/** Serves a directory, with the policy on every response when given. */
async function serveStatic(root, policy) {
  const types = {'.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript',
    '.wasm': 'application/wasm', '.json': 'application/json'};
  const server = createServer((request, response) => {
    const file = path.join(root, decodeURIComponent(new URL(request.url, 'http://localhost').pathname));
    const stat = file.startsWith(root) ? statSync(file, {throwIfNoEntry: false}) : undefined;
    if (!stat?.isFile()) { response.writeHead(404).end(); return; }
    response.writeHead(200, {'Content-Type': types[path.extname(file)] ?? 'application/octet-stream',
      'Cache-Control': 'no-store', ...(policy ? {'Content-Security-Policy': policy} : {})});
    response.end(readFileSync(file));
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  return {url: `http://127.0.0.1:${server.address().port}`, close: () => server.close()};
}

// --- browsers ---
let playwright, browser;
async function chromium() {
  playwright ??= await import(pathToFileURL(path.join(path.resolve(args.playwright), 'index.mjs')).href);
  browser ??= await playwright.chromium.launch();
  return browser;
}
/** Opens url in a page and returns window.__result, with the page's errors and failed requests. */
async function inPage(context, url) {
  const page = await context.newPage();
  const log = [];
  page.on('pageerror', error => log.push('pageerror: ' + error.message));
  page.on('console', message => { if (message.type() === 'error') log.push('console: ' + message.text()); });
  page.on('requestfailed', request => log.push('requestfailed: ' + request.url()));
  // The digests of the scripts the page and its workers were served, to find the launchers among them.
  const served = [];
  page.on('response', response => {
    if (response.status() >= 400) log.push(response.status() + ': ' + response.url());
    if (/\.m?js$/.test(new URL(response.url()).pathname)) {
      served.push(response.body().then(body => {
        // A development server may append a source-map comment to a script it otherwise serves unchanged.
        const text = body.toString('latin1');
        const comment = /\n\/\/# sourceMappingURL=[^\n]*\n?$/.exec(text);
        return [[sha256(body), 'identical'],
          ...(comment ? [[sha256(Buffer.from(text.slice(0, comment.index), 'latin1')), 'same code + source-map comment']] : [])];
      }, () => []));
    }
  });
  try {
    await page.goto(url);
    await page.waitForFunction(() => window.__result !== undefined, null, {timeout: 300000});
    const outcome = await page.evaluate(() => window.__result);
    if (!outcome.ok) outcome.error += '\n' + log.join('\n');
    outcome.served = new Map((await Promise.all(served)).flat());
    return outcome;
  } catch (error) {
    return {ok: false, error: error.message.split('\n')[0] + '\n' + log.join('\n')};
  } finally {
    await page.close();
  }
}
async function browserFixture(fixture, root, pagePath, {policy, appDir, outputDir, parity = false, expect} = {}) {
  const b = await chromium();
  const server = await serveStatic(root, policy);
  try {
    const query = parity ? '?parity=' + encodeURIComponent('/parity/vm.json') : '';
    const outcome = verify(await inPage(b, server.url + pagePath + query), parity ? parityCount : 0);
    const measured = {launcher: outputDir ? launcherIdentity(appDir, outputDir) : undefined,
      servedLauncher: servedIdentity(app, outcome)};
    record(fixture, `Chromium ${b.version()}` + (policy ? `, CSP ${policy}` : ''), outcome, {...measured, expect});
    return {outcome, measured};
  } finally {
    server.close();
  }
}

// --- the fixtures ---
const parityFile = args.parity ? path.resolve(args.parity) : null;
const parityCount = parityFile ? JSON.parse(readFileSync(parityFile, 'utf8')).length : 0;
rmSync(work, {recursive: true, force: true});
mkdirSync(work, {recursive: true});
const app = path.join(work, 'app');
const needsApp = [...only].some(name => !name.startsWith('next'));
if (needsApp) {
  install(app, 'app');
  for (const dir of ['node', 'browser', 'server', 'webpack', 'extension']) {
    cpSync(path.join(fixturesDir, dir), path.join(app, dir), {recursive: true});
  }
  for (const file of ['vite.config.mjs', 'vite.exclude.config.mjs', 'vite.extension.config.mjs']) {
    cpSync(path.join(fixturesDir, file), path.join(app, file));
  }
  if (parityFile) cpSync(parityFile, path.join(app, 'parity', 'vm.json'));
}
const bin = (dir, name) => path.join(dir, 'node_modules', '.bin', name);

if (only.has('node')) {
  for (const binary of nodes) {
    for (const script of ['main.mjs', 'in-worker.mjs']) {
      const label = `${run(binary, ['--version'], app).trim()} ${script === 'main.mjs' ? 'main thread' : 'application worker'}`;
      await guard('node', label, async () => record('node', label, verify(lastJson(run(binary,
        [path.join('node', script), ...(parityFile ? [path.join(app, 'parity', 'vm.json')] : [])], app)), parityCount,
        {forwarding: true})));
    }
  }
}
if (only.has('plain')) {
  // No bundler: an import map names the installed packages. The policy allows the import map by its hash.
  const html = readFileSync(path.join(app, 'browser', 'plain.html'), 'utf8');
  const importMap = /<script type="importmap">([\s\S]*?)<\/script>/.exec(html)[1];
  const hash = createHash('sha256').update(importMap).digest('base64');
  await guard('plain', 'setup', async () => {
    await browserFixture('plain <script type="module">', app, '/browser/plain.html',
      {policy: `script-src 'self' 'wasm-unsafe-eval' 'sha256-${hash}'`, parity: Boolean(parityFile),
        expect: {served: 'identical'}});
  });
}
async function viteDev(fixture, config, viteBin = bin(app, 'vite')) {
  const port = await freePort();
  const viteVersion = run(viteBin, ['--version'], app).trim();
  const server = await startProgram(viteBin, ['--config', config, '--port', String(port), '--strictPort',
    '--host', '127.0.0.1'], app, `http://127.0.0.1:${port}/`);
  try {
    const b = await chromium();
    const outcome = verify(await inPage(b, `http://127.0.0.1:${port}/index.html`));
    record(fixture, `${viteVersion} dev (${config}), Chromium ${b.version()}`, outcome,
      {servedLauncher: servedIdentity(app, outcome), expect: {served: 'dev'}});
  } finally {
    await server.stop();
  }
}
if (only.has('vite')) {
  await guard('vite', 'dev', () => viteDev('vite dev', 'vite.config.mjs'));
  await guard('vite', 'build', async () => {
    run(bin(app, 'vite'), ['build', '--config', 'vite.config.mjs'], app);
    await browserFixture('vite build', path.join(app, 'dist', 'vite'), '/index.html',
      {policy: "script-src 'self' 'wasm-unsafe-eval'", appDir: app, outputDir: path.join(app, 'dist', 'vite'),
        expect: {disk: 'identical', served: 'identical'}});
  });
}
if (only.has('vite7-dev')) {
  // The README's optimizeDeps.exclude for the development servers of Vite 5 to 7, with Vite 7.
  const vite7 = path.join(work, 'vite7');
  installTooling(vite7, 'vite7');
  checkLocked(vite7, 'vite7');
  await guard('vite7-dev', 'dev', () => viteDev('vite 7 dev', 'vite.exclude.config.mjs', bin(vite7, 'vite'))
    );
}
for (const [fixture, config, output] of [['webpack', 'webpack.config.cjs', 'webpack'],
  ['webpack-keep', 'webpack.keep.cjs', 'webpack-keep']]) {
  if (!only.has(fixture)) continue;
  await guard(fixture, 'build', async () => {
    run(bin(app, 'webpack'), ['--config', path.join('webpack', config)], app);
    const dist = path.join(app, 'dist', output);
    writeFileSync(path.join(dist, 'index.html'), '<!doctype html><meta charset="utf-8"><script src="main.js"></script>');
    const webpackVersion = JSON.parse(readFileSync(path.join(app, 'node_modules/webpack/package.json'))).version;
    // With no configuration Terser rewrites the launcher, which still works: recorded, not required. The README's
    // configuration must ship it unchanged.
    const rules = fixture === 'webpack-keep' ? {disk: 'identical', served: 'identical'} : undefined;
    await browserFixture(`webpack ${webpackVersion} production (${config})`, dist, '/index.html',
      {policy: "script-src 'self' 'wasm-unsafe-eval'", appDir: app, outputDir: dist, expect: rules});
    if (!rules) return;
    // The negative control: the same output with each launcher changed by one appended comment, which still loads,
    // must fail the identity rule, on disk and as served.
    const tampered = path.join(app, 'dist', output + '-tampered');
    rmSync(tampered, {recursive: true, force: true});
    cpSync(dist, tampered, {recursive: true});
    for (const value of Object.values(launcherIdentity(app, dist))) {
      writeFileSync(path.join(tampered, /^identical \((.*)\)$/.exec(value)[1]), '\n// changed\n', {flag: 'a'});
    }
    const server = await serveStatic(tampered, "script-src 'self' 'wasm-unsafe-eval'");
    try {
      // Classified exactly as the real fixture is, once per rule: each verdict must be a failure of the launcher
      // identity, on a run that otherwise passed.
      const outcome = verify(await inPage(await chromium(), server.url + '/index.html'));
      const measured = {launcher: launcherIdentity(app, tampered), servedLauncher: servedIdentity(app, outcome)};
      const name = `webpack ${webpackVersion} production (${config}), launchers changed`;
      const verdicts = [{disk: 'identical'}, {served: 'identical'}, rules]
        .map(expect => classify(name, 'tampered', outcome, {...measured, expect}));
      const attributed = outcome.ok && verdicts.every(v => !v.ok && v.launcherProblem
        && /^launcher (on disk|served) for /.test(v.error));
      record(name + ' (negative control)', 'each identity rule must fail', attributed ? {ok: true}
        : {ok: false, error: `loaded: ${outcome.ok} ${outcome.error ?? ''}; verdicts: `
          + JSON.stringify(verdicts.map(v => [v.ok, v.error]))}, {verdicts: verdicts.map(v => v.error)});
    } finally {
      server.close();
    }
  });
}
if (only.has('extension')) {
  // A Chrome extension page (chrome-extension: origin, MV3's own policy: script-src 'self' 'wasm-unsafe-eval').
  await guard('extension', 'Chromium', async () => {
    run(bin(app, 'vite'), ['build', '--config', 'vite.extension.config.mjs'], app);
    const dist = path.join(app, 'dist', 'extension');
    await chromium();
    const context = await playwright.chromium.launchPersistentContext('', {channel: 'chromium',
      args: [`--disable-extensions-except=${dist}`, `--load-extension=${dist}`]});
    try {
      const worker = context.serviceWorkers()[0] ?? await context.waitForEvent('serviceworker', {timeout: 30000});
      const id = new URL(worker.url()).host;
      const outcome = verify(await inPage(context, `chrome-extension://${id}/index.html`));
      if (outcome.ok) assert.equal(outcome.origin, `chrome-extension://${id}`);
      record('chrome extension', `Chromium ${context.browser()?.version() ?? ''} (unpacked, MV3)`.trim(), outcome,
        {launcher: launcherIdentity(app, dist), servedLauncher: servedIdentity(app, outcome),
          expect: {disk: 'identical', served: 'identical'}});
    } finally {
      await context.close();
    }
    // The negative control: MV3's default policy has no 'wasm-unsafe-eval', and loading fails promptly, saying so.
    const manifest = JSON.parse(readFileSync(path.join(dist, 'manifest.json'), 'utf8'));
    delete manifest.content_security_policy;
    const strict = path.join(app, 'dist', 'extension-default-csp');
    cpSync(dist, strict, {recursive: true});
    writeFileSync(path.join(strict, 'manifest.json'), JSON.stringify(manifest, null, 2));
    const control = await playwright.chromium.launchPersistentContext('', {channel: 'chromium',
      args: [`--disable-extensions-except=${strict}`, `--load-extension=${strict}`]});
    try {
      const worker = control.serviceWorkers()[0] ?? await control.waitForEvent('serviceworker', {timeout: 30000});
      const started = Date.now();
      const refused = await inPage(control, `chrome-extension://${new URL(worker.url()).host}/index.html`);
      const ms = Date.now() - started;
      const expected = !refused.ok && /Content Security Policy/.test(refused.error) && ms < 30000;
      record('chrome extension, MV3 default policy (negative control)', `refused in ${ms} ms`,
        expected ? {ok: true} : {ok: false, error: 'expected a prompt CSP refusal: ' + JSON.stringify(refused).slice(0, 1000)});
    } finally {
      await control.close();
    }
  });
}
/** Copies a server bundle to a directory of its own, with the given package.json, and runs it on every Node. */
function runServerBundle(fixture, dist, entry, packageJson) {
  const deployed = path.join(work, 'deploy', fixture);
  rmSync(deployed, {recursive: true, force: true});
  cpSync(dist, deployed, {recursive: true});
  if (packageJson) writeFileSync(path.join(deployed, 'package.json'), JSON.stringify(packageJson));
  for (const binary of nodes) {
    const outcome = verify(lastJson(run(binary, [entry], deployed)));
    record(fixture, `${run(binary, ['--version'], deployed).trim()}, copied out of the app`
      + (packageJson ? ' (package.json ' + JSON.stringify(packageJson) + ')' : ' (no package.json)'), outcome,
    {launcher: launcherIdentity(app, dist)});
  }
}
if (only.has('webpack-node')) {
  await guard('webpack-node', 'build', async () => {
    run(bin(app, 'webpack'), ['--config', path.join('webpack', 'webpack.node.cjs')], app);
    runServerBundle('webpack target node (CommonJS)', path.join(app, 'dist', 'webpack-node-cjs'), 'server.js', null);
    run(bin(app, 'webpack'), ['--config', path.join('webpack', 'webpack.node-esm.cjs')], app);
    runServerBundle('webpack target node (ES module)', path.join(app, 'dist', 'webpack-node-esm'), 'server.mjs',
      {type: 'module'});
  });
}
if (only.has('esbuild')) {
  // esbuild does not copy files named by new URL: the README keeps the packages external.
  await guard('esbuild', 'build', async () => {
    run(bin(app, 'esbuild'), ['server/server.mjs', '--bundle', '--platform=node', '--format=esm',
      '--packages=external', '--outfile=dist/esbuild/server.mjs'], app);
    for (const binary of nodes) {
      record('esbuild --packages=external', run(binary, ['--version'], app).trim(),
        verify(lastJson(run(binary, ['dist/esbuild/server.mjs'], app))));
    }
  });
}

// --- Next.js ---
const nextDir = path.join(work, 'next');
if ([...only].some(name => name.startsWith('next'))) {
  install(nextDir, 'next');
  cpSync(path.join(fixturesDir, 'next', 'app'), path.join(nextDir, 'app'), {recursive: true});
  const nextVersion = JSON.parse(readFileSync(path.join(nextDir, 'node_modules/next/package.json'), 'utf8')).version;
  const nextConfig = name => cpSync(path.join(fixturesDir, 'next', name), path.join(nextDir, 'next.config.mjs'));
  const check = async (fixture, environment, command, commandArgs, cwd, extraEnv = {}, outputDir) => {
    const port = await freePort();
    const server = await startProgram(command, [...commandArgs, ...(command === process.execPath ? [] : ['-p', String(port)])],
      cwd, `http://127.0.0.1:${port}/`, {PORT: String(port), HOSTNAME: '127.0.0.1', ...extraEnv});
    try {
      const response = await fetch(`http://127.0.0.1:${port}/api/check`, {signal: AbortSignal.timeout(300000)});
      // Turbopack copies the launchers into its output unchanged; with external packages they are read in place.
      record(fixture, environment, verify(await response.json()), outputDir
        ? {launcher: launcherIdentity(nextDir, outputDir), expect: {disk: 'identical'}} : {});
    } finally {
      await server.stop();
    }
  };
  if (only.has('next')) {
    await guard('next', 'build', async () => {
      nextConfig('next.config.mjs');
      run(bin(nextDir, 'next'), ['build'], nextDir);
      await check(`Next.js ${nextVersion} Turbopack: next build, next start`, process.version, bin(nextDir, 'next'),
        ['start'], nextDir, {}, path.join(nextDir, '.next', 'server'));
      // The standalone output, copied away from the application, as it is deployed.
      const standalone = path.join(work, 'deploy', 'next-standalone');
      rmSync(standalone, {recursive: true, force: true});
      cpSync(path.join(nextDir, '.next', 'standalone'), standalone, {recursive: true});
      await check(`Next.js ${nextVersion} Turbopack: output standalone, copied`, process.version, process.execPath,
        ['server.js'], standalone, {}, path.join(standalone, '.next', 'server'));
    });
  }
  if (only.has('next-dev')) {
    await guard('next-dev', 'dev', async () => {
      nextConfig('next.config.mjs');
      rmSync(path.join(nextDir, '.next'), {recursive: true, force: true});
      await check(`Next.js ${nextVersion} Turbopack: next dev`, process.version, bin(nextDir, 'next'), ['dev'], nextDir,
        {}, path.join(nextDir, '.next', 'dev'));
    });
  }
  if (only.has('next-webpack')) {
    await guard('next-webpack', 'build', async () => {
      nextConfig('next.external.config.mjs');
      rmSync(path.join(nextDir, '.next'), {recursive: true, force: true});
      run(bin(nextDir, 'next'), ['build', '--webpack'], nextDir);
      await check(`Next.js ${nextVersion} --webpack with serverExternalPackages`, process.version, bin(nextDir, 'next'),
        ['start'], nextDir);
    });
  }
}
await browser?.close();

writeFileSync(path.join(work, 'results.json'), JSON.stringify(results, null, 2));
const failed = results.filter(r => !r.ok);
console.log(`\n${results.length - failed.length} of ${results.length} fixture runs passed; details in ${path.join(work, 'results.json')}`);
if (failed.length || results.length === 0) process.exit(1);
