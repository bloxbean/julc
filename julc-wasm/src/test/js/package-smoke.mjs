// ADR-077 M1 with the real engines: @julclang/wasm-client installed from its packed tarball loads JuLC's VM and full
// images in package mode, in Node.js with no flags, on the main thread and in an application worker, with string
// code generation disallowed, and in every extra Node.js binary given (I6, I8). The engine directories stand in for
// M3's engine packages: engine/ holds the archive's launcher and module unchanged, and engine.js names them.
//   node package-smoke.mjs <tarball> <vm bundle> <full bundle> <vm.json> [<node binary>...]
import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {copyFileSync, mkdirSync, mkdtempSync, readFileSync, writeFileSync} from 'node:fs';
import {tmpdir} from 'node:os';
import path from 'node:path';

const [tarball, vmBundle, fullBundle, vmFile, ...nodes] = process.argv.slice(2);
const app = mkdtempSync(path.join(tmpdir(), 'julc-package-smoke-'));
writeFileSync(path.join(app, 'package.json'), JSON.stringify({name: 'smoke', private: true, type: 'module'}));
execFileSync('npm', ['install', path.resolve(tarball), '--offline', '--ignore-scripts', '--no-audit', '--no-fund'],
  {cwd: app, stdio: 'inherit'});
for (const [name, bundle] of [['vm', vmBundle], ['full', fullBundle]]) {
  const manifest = JSON.parse(readFileSync(path.join(bundle, 'engine.json'), 'utf8'));
  const dir = path.join(app, name);
  mkdirSync(path.join(dir, 'engine'), {recursive: true});
  writeFileSync(path.join(dir, 'engine', 'package.json'), '{"type": "commonjs"}\n');
  for (const file of [manifest.launcher, manifest.launcher + '.wasm', 'engine.json']) {
    copyFileSync(path.join(bundle, file), path.join(dir, 'engine', file));
  }
  writeFileSync(path.join(dir, 'engine.js'), `export const manifest = ${JSON.stringify(manifest)};
export const launcherUrl = new URL('./engine/${manifest.launcher}', import.meta.url);
export const moduleUrl = new URL('./engine/${manifest.launcher}.wasm', import.meta.url);
export const version = ${JSON.stringify(manifest.julcVersion)};
`);
}
writeFileSync(path.join(app, 'vm.json'), readFileSync(vmFile));
writeFileSync(path.join(app, 'main.mjs'), `import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {createJulc, isFullClient} from '@julclang/wasm-client';
const decodeEnvelope = envelope => {
  let body = envelope.body;
  for (const p of envelope.integers) {
    if (p.length === 0) { body = BigInt(body); continue; }
    let parent = body;
    for (let i = 0; i < p.length - 1; i++) parent = parent[p[i]];
    parent[p[p.length - 1]] = BigInt(parent[p[p.length - 1]]);
  }
  return body;
};
const fixtures = JSON.parse(readFileSync(new URL('./vm.json', import.meta.url)));
const report = [];
for (const variant of ['vm', 'full']) {
  const {manifest, launcherUrl, moduleUrl, version} = await import('./' + variant + '/engine.js');
  const started = performance.now();
  const client = await createJulc({manifest, launcherUrl, moduleUrl, expectedVersion: version});
  try {
    assert.equal(client.version(), version);
    assert.equal(client.features().julcVersion, version);
    assert.equal(client.rest, undefined);
    assert.equal(isFullClient(client), variant === 'full');
    const decoded = await client.vm.decode({script: {script: '46450101002499'}});
    assert.equal(decoded.info.scriptHash, '186e32faa80a26810392fda6d559c7ed4721a65ce1c9d4ef3e1c87b4');
    for (const fixture of fixtures) {
      const request = decodeEnvelope(fixture.request);
      assert.deepEqual(await client.vm.evaluate(request), decodeEnvelope(fixture.evaluate), fixture.name);
    }
    if (variant === 'full') {
      const checked = await client.compiler.check({source: 'class A {}'});
      assert.equal(typeof checked.valid, 'boolean');
    }
    report.push(variant + ' ' + Math.round(performance.now() - started) + ' ms');
  } finally { client.dispose(); }
}
console.log(JSON.stringify({node: process.version, ok: true, report}));
`);
writeFileSync(path.join(app, 'in-worker.mjs'), `import {Worker} from 'node:worker_threads';
const worker = new Worker(new URL('./main.mjs', import.meta.url));
worker.on('error', error => { console.error(error); process.exitCode = 1; });
`);
const runs = [];
for (const binary of [process.execPath, ...nodes]) {
  const scripts = [['main.mjs'], ['in-worker.mjs']];
  if (binary === process.execPath) scripts.push(['--disallow-code-generation-from-strings', 'main.mjs']);
  for (const args of scripts) {
    // No flag for exception references: the client enables them where needed (D3).
    const output = execFileSync(binary, args, {cwd: app, encoding: 'utf8', timeout: 300000}).trim().split('\n').pop();
    const result = JSON.parse(output);
    assert.equal(result.ok, true);
    runs.push(`${result.node} ${args.join(' ')}: ${result.report.join(', ')}`);
    console.log('ok', runs.at(-1));
  }
}
console.log(`ok @julclang/wasm-client from ${path.basename(tarball)}: ${runs.length} runs`);
