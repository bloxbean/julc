// ADR-077 D8a, R23-F8, R23-C33: the release's lists of artifacts and files in native-image-release.yml agree, so
// changing one without the others fails the build:
// - each upload step's `path` entries are exactly the files its artifact is expected to hold (the ten release-*
//   artifacts the build jobs upload, with their matrix labels expanded, and npm-packages);
// - the files of the release-* artifacts are exactly the 14-file inventory github-release requires;
// - the job names npm-run.sh looks for are the names of the attest and github-release jobs (R23-F10, R23-C41), so a
//   rename fails the build instead of blocking every recovery dispatch.
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import {fileURLToPath} from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..', '..', '..');
const workflow = readFileSync(path.join(root, '.github', 'workflows', 'native-image-release.yml'), 'utf8');
const npmRun = readFileSync(path.join(root, 'julc-wasm', 'src', 'ci', 'npm-run.sh'), 'utf8');

/** The workflow's jobs, as text, by name. */
const jobs = Object.fromEntries([...workflow.matchAll(/^ {2}([a-z][a-z-]*):\n([\s\S]*?)(?=^ {2}[a-z][a-z-]*:\n|(?![\s\S]))/gm)]
  .map(([, name, body]) => [name, body]));
const labelsOf = body => [...body.matchAll(/^\s+label: (\S+)$/gm)].map(match => match[1]);
const sorted = values => [...values].sort();

const VERSION = '${{ env.VERSION }}';
const LABEL = '${{ matrix.label }}';
/** What each artifact holds: its upload step's `path` entries, exactly. */
const EXPECTED = {
  [`release-cli-${LABEL}`]: [`julc-${VERSION}-${LABEL}.zip`],
  [`release-playground-${LABEL}`]: [`julc-playground-${VERSION}-${LABEL}.zip`],
  'release-shadow-jars': ['shadow-dist/*.jar'],
  'release-wasm': [`julc-wasm-${VERSION}.tar.gz`, `julc-vm-wasm-${VERSION}.tar.gz`,
    `julc-playground-static-${VERSION}.tar.gz`, `julc-wasm-${VERSION}-SHA256SUMS.txt`],
  'npm-packages': [`julclang-wasm-client-${VERSION}.tgz`, `julclang-julc-vm-wasm-${VERSION}.tgz`,
    `julclang-julc-wasm-${VERSION}.tgz`, `julc-vm-wasm-${VERSION}.tar.gz`, `julc-wasm-${VERSION}.tar.gz`,
    `julc-npm-${VERSION}-SHA256SUMS.txt`],
};
/** The jars shadow-jars copies into shadow-dist/, the only files there. */
const SHADOW_JARS = [`julc-compiler-all-${VERSION}-all.jar`, `julc-vm-java-all-${VERSION}-all.jar`];

/** Every upload-artifact step of the workflow: its job, artifact name and `path` entries (one line, or a block). */
function uploads() {
  const found = [];
  for (const [job, body] of Object.entries(jobs)) {
    for (const [, block] of body.matchAll(/uses: actions\/upload-artifact@\S+[^\n]*\n(\s+)with:\n((?:\1 +\S[^\n]*\n)+)/g)
      .map(match => [match[1], match[2]])) {
      const name = /^\s+name: ([^\n]+)$/m.exec(block)?.[1];
      const single = /^\s+path: ([^|\n][^\n]*)$/m.exec(block);
      const multi = /^(\s+)path: \|\n((?:\1 +\S[^\n]*\n)+)/m.exec(block);
      assert.ok(name && (single || multi), `${job}: an upload step with a name and a path`);
      const paths = single ? [single[1].trim()] : multi[2].split('\n').map(line => line.trim()).filter(Boolean);
      found.push({job, name, paths});
    }
  }
  return found;
}

test('each release-* and npm-packages upload step uploads exactly the files its artifact is expected to hold', () => {
  const steps = uploads().filter(({name}) => name.startsWith('release-') || name === 'npm-packages');
  assert.deepEqual(sorted(steps.map(({name}) => name)), sorted(Object.keys(EXPECTED)), 'the artifacts');
  for (const {job, name, paths} of steps) {
    assert.deepEqual(sorted(paths), sorted(EXPECTED[name]), `${job} uploads ${name}`);
  }
  // shadow-dist/ holds exactly the two shadow jars.
  assert.match(jobs['shadow-jars'],
    /mkdir -p shadow-dist\n\s+cp \S*\/julc-compiler-all-\*-all\.jar \S*\/julc-vm-java-all-\*-all\.jar shadow-dist\/\n/);
  // One set of matrix labels for the CLI and playground builds.
  assert.equal(labelsOf(jobs['native-image']).length, 4);
  assert.deepEqual(labelsOf(jobs['native-image-playground']), labelsOf(jobs['native-image']));
});

test('the files of the ten release-* artifacts are exactly github-release\'s 14-file inventory', () => {
  const labels = labelsOf(jobs['native-image']);
  const delivered = [];
  for (const [name, paths] of Object.entries(EXPECTED)) {
    if (!name.startsWith('release-')) continue;
    const files = name === 'release-shadow-jars' ? SHADOW_JARS : paths;
    const expand = file => name.includes(LABEL) ? labels.map(label => file.replaceAll(LABEL, label)) : [file];
    delivered.push(...files.flatMap(expand));
  }
  assert.equal(delivered.length, 14);
  // github-release's inventory: a `for label in …` loop of echo templates, then a printf of fixed names.
  const block = jobs['github-release'].slice(jobs['github-release'].indexOf("The release's inventory (14 files)"));
  const loop = /for label in ([^;]+); do\n((?:\s+echo "[^"]+";?[^\n]*\n)+?)\s+done/.exec(block);
  assert.ok(loop, 'github-release lists its inventory');
  assert.deepEqual(loop[1].trim().split(/\s+/), labels, 'github-release loops over the matrix labels');
  const templates = [...loop[2].matchAll(/echo "([^"]+)"/g)].map(match => match[1]);
  const printf = /printf '%s\\n' ((?:"?[^\s"]+"?\s*\\?\s*)+?)(?:>>)/.exec(block.slice(loop.index));
  assert.ok(printf, 'github-release lists its fixed files');
  const inventory = [...labels.flatMap(label => templates.map(template => template.replaceAll('${label}', label))),
    ...[...printf[1].matchAll(/"?([^\s"\\]+)"?/g)].map(match => match[1])];
  const plain = file => file.replaceAll('${VERSION}', 'V').replaceAll(VERSION, 'V');
  assert.deepEqual(sorted(delivered.map(plain)), sorted(inventory.map(plain)));
});

test('the job names npm-run.sh looks for are the attest and github-release jobs\' names, each in its role', () => {
  const nameOf = job => /^ {4}name: ([^\n]+)$/m.exec(jobs[job])?.[1];
  const roleOf = kind => new RegExp(`\\.name == "([^"]+)" then "${kind} `).exec(npmRun)?.[1];
  // The name whose success makes a run ATTESTED is attest's; the one that makes it RELEASED is github-release's.
  assert.deepEqual([roleOf('ATTESTED'), roleOf('RELEASED')], [nameOf('attest'), nameOf('github-release')]);
  assert.ok(nameOf('attest') && nameOf('github-release'));
  // And npm-run.sh names no other job.
  const used = sorted(new Set([...npmRun.matchAll(/\.name == "([^"]+)"/g)].map(match => match[1])));
  assert.deepEqual(used, sorted([nameOf('attest'), nameOf('github-release')]));
});
