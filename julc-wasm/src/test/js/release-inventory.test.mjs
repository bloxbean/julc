// ADR-077 D8a, R23-F8: the release's lists of artifacts and files, written in three places, agree, so renaming an
// artifact or a file in the workflow without the runbook fails the build:
// - the ten release-* artifacts the build jobs of native-image-release.yml upload (expanding their matrix labels);
// - the 14-file inventory github-release requires;
// - the runbook's manual completion (its preparation script and its final check), adr/evidence/077-m4/RUNBOOK.md.
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import {fileURLToPath} from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..', '..', '..');
const workflow = readFileSync(path.join(root, '.github', 'workflows', 'native-image-release.yml'), 'utf8');
const runbook = readFileSync(path.join(root, 'adr', 'evidence', '077-m4', 'RUNBOOK.md'), 'utf8');

/** The workflow's jobs, as text, by name. */
const jobs = Object.fromEntries([...workflow.matchAll(/^ {2}([a-z][a-z-]*):\n([\s\S]*?)(?=^ {2}[a-z][a-z-]*:\n|(?![\s\S]))/gm)]
  .map(([, name, body]) => [name, body]));
const labelsOf = body => [...body.matchAll(/^\s+label: (\S+)$/gm)].map(match => match[1]);
const sorted = values => [...values].sort();

/** The release-* artifacts the build jobs upload, with ${{ matrix.label }} expanded. */
function workflowArtifacts() {
  const names = [];
  for (const body of Object.values(jobs)) {
    for (const [, name] of body.matchAll(/uses: actions\/upload-artifact@\S+[^\n]*\n\s+with:\n\s+name: (release-[^\n]+)/g)) {
      if (name.includes('${{ matrix.label }}')) {
        for (const label of labelsOf(body)) names.push(name.replace('${{ matrix.label }}', label));
      } else {
        names.push(name);
      }
    }
  }
  return sorted(names);
}

/** A bash list in the form the workflow and the runbook write it: a `for label in …` loop of echo templates plus a
 *  printf of fixed names, in the text that follows `marker`. */
function bashList(text, marker, variable) {
  const start = text.indexOf(marker);
  assert.ok(start >= 0, `no ${JSON.stringify(marker)}`);
  const block = text.slice(start);
  const loop = /for label in ([^;]+); do\n((?:\s+echo "[^"]+";?[^\n]*\n)+?)\s+done/.exec(block);
  assert.ok(loop, `no label loop after ${JSON.stringify(marker)}`);
  const labels = loop[1].trim().split(/\s+/);
  const templates = [...loop[2].matchAll(/echo "([^"]+)"/g)].map(match => match[1]);
  const printf = /printf '%s\\n' ((?:"?[^\s"]+"?\s*\\?\s*)+?)(?:>>)/.exec(block.slice(loop.index));
  assert.ok(printf, `no printf after ${JSON.stringify(marker)}`);
  const fixed = [...printf[1].matchAll(/"?([^\s"\\]+)"?/g)].map(match => match[1]);
  const names = [...labels.flatMap(label => templates.map(template => template.replaceAll('${label}', label))), ...fixed];
  return {labels, names: sorted(names.map(name => name.replaceAll(`\${${variable}}`, 'V')))};
}

test('the ten release-* artifacts: the workflow uploads exactly the ones the runbook downloads', () => {
  const uploaded = workflowArtifacts();
  assert.equal(uploaded.length, 10, JSON.stringify(uploaded));
  const prep = bashList(runbook, 'version=<version>\n    run=<original run id>', 'version');
  assert.deepEqual(prep.labels, labelsOf(jobs['native-image']), 'the runbook loops over the CLI matrix labels');
  assert.deepEqual(labelsOf(jobs['native-image-playground']), labelsOf(jobs['native-image']), 'one set of labels');
  assert.deepEqual(prep.names, uploaded);
});

test('the 14-file inventory: github-release and both runbook checks list the same files', () => {
  const release = bashList(jobs['github-release'], "The release's inventory (14 files)", 'VERSION');
  assert.equal(release.names.length, 14, JSON.stringify(release.names));
  assert.deepEqual(release.labels, labelsOf(jobs['native-image']));
  const inventoryStart = runbook.indexOf('# The 14-file inventory, each name once.');
  assert.deepEqual(bashList(runbook.slice(inventoryStart), 'for label in', 'version').names, release.names,
    'the preparation script');
  const checkStart = runbook.indexOf('version=<version>\n    if command -v sha256sum');
  assert.deepEqual(bashList(runbook.slice(checkStart), 'for label in', 'version').names, release.names,
    'the final check');
  // The files the build jobs package under those names.
  for (const [job, file] of [['native-image', 'julc-${{ env.VERSION }}-${{ matrix.label }}.zip'],
    ['native-image-playground', 'julc-playground-${{ env.VERSION }}-${{ matrix.label }}.zip']]) {
    assert.ok(jobs[job].includes(`path: ${file}`), `${job} uploads ${file}`);
  }
  for (const file of ['julc-wasm-${{ env.VERSION }}.tar.gz', 'julc-vm-wasm-${{ env.VERSION }}.tar.gz',
    'julc-playground-static-${{ env.VERSION }}.tar.gz', 'julc-wasm-${{ env.VERSION }}-SHA256SUMS.txt']) {
    assert.ok(jobs.wasm.includes(file), `wasm uploads ${file}`);
  }
  assert.match(jobs['shadow-jars'], /julc-compiler-all-\*-all\.jar \S*julc-vm-java-all-\*-all\.jar shadow-dist\//);
});
