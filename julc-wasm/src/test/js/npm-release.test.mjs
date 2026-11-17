// ADR-077 D8 and D8a: the pure parts of the release workflow's npm scripts (julc-wasm/src/ci/npm-release.mjs).
import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {existsSync, readFileSync, realpathSync} from 'node:fs';
import {createRequire} from 'node:module';
import path from 'node:path';
import test from 'node:test';
import {fileURLToPath} from 'node:url';
import {
  PACKAGES, certificateIdentity, certificateProblems, compareVersions, distTag, finalVersionOf, isLoopbackRegistry,
  isReleaseCandidate, parseDigests, parseSums, releaseIdentity, statementOf, statementProblems, tarballName,
  verifiedProvenance,
} from '../../ci/npm-release.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));

test('dist-tags follow D8: -preN and final versions to latest, -beta to beta', () => {
  for (const [version, tag] of [
    ['0.1.0-pre22', 'latest'], ['0.1.0-pre21', 'latest'], ['0.1.0-pre100', 'latest'], ['0.1.0-pre.22', 'latest'],
    ['0.1.0', 'latest'], ['0.2.3', 'latest'], ['1.0.0', 'latest'], ['2.4.1', 'latest'],
    ['0.1.0-beta1', 'beta'], ['0.1.0-beta.2', 'beta'], ['1.0.0-beta1', 'beta'], ['1.1.0-beta.1', 'beta'],
    ['0.1.0-alpha1', 'latest'],
  ]) {
    assert.equal(distTag(version), tag, version);
  }
});

test('release candidates, snapshots, 1.x prereleases and malformed versions are refused', () => {
  for (const version of ['0.1.0-pre21-rc2', '0.1.0-pre21-rc1', '0.1.0-rc1', '0.1.0-rc.1', '1.0.0-rc1', '1.0.0-RC2',
    '0.1.0-pre22-rc', '1.0.0-beta1-rc1']) {
    assert.throws(() => distTag(version), /release candidate/, version);
    assert.ok(isReleaseCandidate(version), version);
  }
  for (const version of ['0.1.0-pre21-rc2-SNAPSHOT', '0.1.0-SNAPSHOT', '0.1.0-pre21-rc2-c8666d1c-SNAPSHOT']) {
    assert.throws(() => distTag(version), /snapshot/, version);
  }
  for (const version of ['1.0.0-pre1', '1.2.0-alpha1', '2.0.0-pre.3']) assert.throws(() => distTag(version), /from 1\.0 on/);
  for (const version of ['v0.1.0', '0.1', '0.1.0-', '01.0.0', '0.1.0+build', '', ' 0.1.0', '0.1.0-pre..1']) {
    assert.throws(() => distTag(version), /not a semantic version/, JSON.stringify(version));
  }
  // Words that merely contain "rc" are not candidates.
  for (const version of ['0.1.0-source1', '0.1.0-arc2', '0.1.0-pre21']) assert.ok(!isReleaseCandidate(version), version);
});

test('a release candidate dry-runs with its final release tag', () => {
  assert.equal(finalVersionOf('0.1.0-pre21-rc2'), '0.1.0-pre21');
  assert.equal(finalVersionOf('1.0.0-rc.1'), '1.0.0');
  assert.equal(finalVersionOf('1.0.0-rc1'), '1.0.0');
  assert.equal(distTag(finalVersionOf('0.1.0-pre21-rc2')), 'latest');
});

test('version precedence matches semver, including the orders D8 relies on', () => {
  assert.ok(compareVersions('0.1.0-pre21-rc2', '0.1.0-pre21') > 0, 'a candidate sorts above its final release');
  assert.ok(compareVersions('0.1.0-pre100', '0.1.0-pre20') < 0, 'preN compares as text (open question 8)');
  assert.ok(compareVersions('0.1.0-beta1', '0.1.0-pre1') < 0, 'beta sorts below every preN');
  assert.ok(compareVersions('0.1.0', '0.1.0-pre99') > 0);
  assert.ok(compareVersions('0.1.0-pre.10', '0.1.0-pre.9') > 0, 'numeric identifiers compare as numbers');
  assert.equal(compareVersions('0.1.0-pre22', '0.1.0-pre22'), 0);
  // Against npm's own semver, when the npm in use can be found.
  // The npm on PATH is a script in npm's package (bin/npm-cli.js), beside its own node_modules/semver.
  let semver;
  try {
    const npmCli = realpathSync(execFileSync(process.platform === 'win32' ? 'where' : 'which', ['npm'], {encoding: 'utf8'})
      .split('\n')[0].trim());
    const file = path.join(path.dirname(npmCli), '..', 'node_modules', 'semver', 'index.js');
    if (existsSync(file)) semver = createRequire(file)(file);
  } catch { /* npm not found this way */ }
  if (!semver) {
    console.log('# npm\'s own semver was not found; only the fixed cases ran');
    return;
  }
  const versions = ['0.0.0', '0.1.0', '0.1.0-pre1', '0.1.0-pre9', '0.1.0-pre10', '0.1.0-pre20', '0.1.0-pre100',
    '0.1.0-pre21-rc1', '0.1.0-pre21-rc2', '0.1.0-pre21', '0.1.0-beta1', '0.1.0-beta.1', '0.1.0-rc.1', '0.1.0-pre.2',
    '0.1.0-pre.10', '0.1.0-alpha', '0.1.0-alpha.1', '0.1.0-alpha.beta', '1.0.0', '1.0.0-rc1', '0.2.0-pre1', '10.0.0'];
  for (const a of versions) for (const b of versions) assert.equal(compareVersions(a, b), semver.compare(a, b), `${a} vs ${b}`);
});

test('checksum lines and digest arguments are strict', () => {
  const digest = 'a'.repeat(64);
  assert.deepEqual([...parseSums(`${digest}  one.tgz\n${'b'.repeat(64)} *two.tar.gz\n`)],
    [['one.tgz', digest], ['two.tar.gz', 'b'.repeat(64)]]);
  assert.throws(() => parseSums(`${digest}  one.tgz\n${digest}  one.tgz\n`), /twice/);
  assert.throws(() => parseSums(`${digest} one.tgz`), /malformed/);
  assert.throws(() => parseSums(`${'A'.repeat(64)}  one.tgz`), /malformed/);
  assert.throws(() => parseSums(`${digest}  dir/one.tgz`), /malformed/);
  assert.deepEqual([...parseDigests([`x.tgz=${digest}`])], [['x.tgz', digest]]);
  assert.throws(() => parseDigests([`x.tgz=${digest.slice(1)}`]), /malformed/);
  assert.throws(() => parseDigests([`x.tgz=${digest}`, `x.tgz=${digest}`]), /twice/);
});

test('publication order and names', () => {
  assert.deepEqual(PACKAGES, ['@julclang/wasm-client', '@julclang/julc-vm-wasm', '@julclang/julc-wasm']);
  assert.equal(tarballName('@julclang/julc-vm-wasm', '0.1.0-pre22'), 'julclang-julc-vm-wasm-0.1.0-pre22.tgz');
  assert.deepEqual(releaseIdentity('0.1.0-pre22'), {repository: 'bloxbean/julc',
    workflow: '.github/workflows/native-image-release.yml', ref: 'refs/tags/v0.1.0-pre22'});
  assert.ok(isLoopbackRegistry('http://127.0.0.1:4873'));
  assert.ok(isLoopbackRegistry('http://localhost:4873/'));
  assert.ok(!isLoopbackRegistry(undefined), 'the default registry is npmjs');
  assert.ok(!isLoopbackRegistry('https://registry.npmjs.org/'));
  assert.ok(!isLoopbackRegistry('http://127.0.0.1.example.org/'));
});

// A real provenance attestation, as `npm audit signatures --json --include-attestations` (npm 11.21.1) reported it on
// 2026-11-17 for @julclang/joust-runtime@0.1.0-pre1-rc9: published by JOUST's release workflow through trusted
// publishing, in the same organisation and environment as JuLC's will be.
const joust = JSON.parse(readFileSync(path.join(here, 'npm-fixtures', 'joust-runtime-0.1.0-pre1-rc9.audit.json'), 'utf8'));
const joustAudit = {invalid: [], missing: [], verified: [joust]};
const JOUST_NAME = '@julclang/joust-runtime';
const JOUST_VERSION = '0.1.0-pre1-rc9';
// Its dist.integrity on the registry.
const JOUST_SHA512 = Buffer.from('a9hIWKz68CA5li+rAYXI2/IVUelVGEYGtulcU/2KRLvzZbzfSRx2KmbLpGIYVBobCua0OAXmdCVp3AQSGnD8jA==',
  'base64').toString('hex');
const joustIdentity = {repository: 'bloxbean/joust', workflow: '.github/workflows/release.yml', ref: `refs/tags/v${JOUST_VERSION}`};

test('the provenance of a real trusted publication is read from the bundle npm verified', () => {
  const bundle = verifiedProvenance(joustAudit, JOUST_NAME, JOUST_VERSION);
  assert.ok(bundle, 'the SLSA provenance bundle, not the publish attestation');
  assert.deepEqual(certificateIdentity(bundle), {
    issuer: 'https://token.actions.githubusercontent.com',
    buildSignerUri: `https://github.com/bloxbean/joust/.github/workflows/release.yml@refs/tags/v${JOUST_VERSION}`,
    runnerEnvironment: 'github-hosted',
    sourceRepositoryUri: 'https://github.com/bloxbean/joust',
    sourceRepositoryRef: `refs/tags/v${JOUST_VERSION}`,
  });
  const facts = {name: JOUST_NAME, version: JOUST_VERSION, sha512: JOUST_SHA512, identity: joustIdentity};
  assert.deepEqual(statementProblems(statementOf(bundle), facts), []);
  assert.deepEqual(certificateProblems(bundle, joustIdentity), []);
});

test('provenance from another repository, workflow, tag or tarball is refused', () => {
  const bundle = verifiedProvenance(joustAudit, JOUST_NAME, JOUST_VERSION);
  const statement = statementOf(bundle);
  const facts = {name: JOUST_NAME, version: JOUST_VERSION, sha512: JOUST_SHA512, identity: joustIdentity};
  // JuLC's identity: both the statement and the certificate name JOUST's repository and workflow.
  const julc = releaseIdentity(JOUST_VERSION);
  assert.match(statementProblems(statement, {...facts, identity: julc}).join('\n'), /workflow repository.*workflow path/s);
  assert.match(certificateProblems(bundle, julc).join('\n'), /buildSignerUri.*sourceRepositoryUri/s);
  // Another tag.
  const otherTag = {...joustIdentity, ref: 'refs/tags/v0.1.0-pre1-rc8'};
  assert.match(statementProblems(statement, {...facts, identity: otherTag}).join('\n'), /workflow ref/);
  assert.match(certificateProblems(bundle, otherTag).join('\n'), /sourceRepositoryRef/);
  // Another tarball, name or version.
  assert.match(statementProblems(statement, {...facts, sha512: '0'.repeat(128)}).join('\n'), /subject sha512/);
  assert.match(statementProblems(statement, {...facts, name: '@julclang/wasm-client'}).join('\n'), /subject/);
  assert.match(statementProblems(statement, {...facts, version: '0.1.0-pre1-rc8'}).join('\n'), /subject/);
  // A self-hosted runner.
  const selfHosted = structuredClone(statement);
  selfHosted.predicate.runDetails.builder.id = 'https://github.com/actions/runner/self-hosted';
  assert.match(statementProblems(selfHosted, facts).join('\n'), /builder/);
});

test('a version npm audit signatures did not verify, or verified without provenance, has no provenance', () => {
  assert.equal(verifiedProvenance(joustAudit, JOUST_NAME, '0.1.0-pre1-rc8'), null);
  assert.equal(verifiedProvenance(joustAudit, '@julclang/wasm-client', JOUST_VERSION), null);
  const publishOnly = {...joust, attestationBundles: joust.attestationBundles
    .filter(entry => entry.predicateType !== 'https://slsa.dev/provenance/v1')};
  assert.equal(verifiedProvenance({verified: [publishOnly]}, JOUST_NAME, JOUST_VERSION), null);
  assert.equal(verifiedProvenance({verified: [joust, joust]}, JOUST_NAME, JOUST_VERSION), null, 'ambiguous');
  assert.equal(verifiedProvenance({}, JOUST_NAME, JOUST_VERSION), null);
});

test('a bundle without a certificate, or with a non-in-toto payload, is refused', () => {
  const bundle = verifiedProvenance(joustAudit, JOUST_NAME, JOUST_VERSION);
  assert.throws(() => certificateIdentity({...bundle, verificationMaterial: {publicKey: {hint: 'x'}}}), /no signing certificate/);
  assert.throws(() => statementOf({...bundle, dsseEnvelope: {...bundle.dsseEnvelope, payloadType: 'text/plain'}}), /in-toto/);
  const truncated = structuredClone(bundle);
  truncated.verificationMaterial.certificate.rawBytes = Buffer.from(bundle.verificationMaterial.certificate.rawBytes,
    'base64').subarray(0, 200).toString('base64');
  assert.throws(() => certificateIdentity(truncated), /truncated DER|malformed/);
});
