// ADR-077 D8 and D8a: the pure parts of the release workflow's npm scripts (julc-wasm/src/ci/npm-release.mjs).
import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {existsSync, readFileSync, realpathSync} from 'node:fs';
import {createRequire} from 'node:module';
import path from 'node:path';
import test from 'node:test';
import {fileURLToPath} from 'node:url';
import {
  BETA_TAG, CANDIDATE_TAG, LATEST_TAG, PACKAGES, PREVIEW_TAG, candidateTags, d8Tag, hasFinalVersion, tagProblems,
  certificateIdentity, certificateProblems, compareVersions, distTag, finalVersionOf, isLoopbackRegistry,
  isPrereleaseCandidate, parseDigests, validatePackument, versionKind, parseSums, releaseIdentity, signingCertificate, statementOf,
  statementProblems, tarballName, verifiedProvenance,
} from '../../ci/npm-release.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));

test('the longest and largest versions npm allows still parse', () => {
  assert.equal(versionKind('9007199254740991.0.0'), 'final');
  const pre = `0.1.0-pre${'1'.repeat(256 - '0.1.0-pre'.length)}`;
  assert.equal(pre.length, 256);
  assert.equal(versionKind(pre), 'pre');
});

test('the tag names are the constants D8 names', () => {
  assert.deepEqual([LATEST_TAG, PREVIEW_TAG, BETA_TAG, CANDIDATE_TAG], ['latest', 'preview', 'beta', 'next']);
});

test('D8\'s table before the registry: final and -preN latest, -betaN beta, X.Y.Z-rcN next, for any major', () => {
  for (const [version, kind, tag] of [
    ['0.1.0', 'final', 'latest'], ['0.2.3', 'final', 'latest'], ['1.0.0', 'final', 'latest'], ['2.4.1', 'final', 'latest'],
    ['0.1.0-pre22', 'pre', 'latest'], ['0.1.0-pre100', 'pre', 'latest'], ['1.1.0-pre1', 'pre', 'latest'],
    ['0.2.0-beta1', 'beta', 'beta'], ['1.0.0-beta12', 'beta', 'beta'],
    ['0.1.0-rc1', 'rc', 'next'], ['1.0.0-rc2', 'rc', 'next'], ['2.3.4-rc10', 'rc', 'next'],
  ]) {
    assert.equal(versionKind(version), kind, version);
    assert.equal(distTag(version), tag, version);
    assert.ok(!isPrereleaseCandidate(version), version);
  }
});

test('a release candidate of a prerelease (X.Y.Z-preN-rcN) is never published; it dry-runs as its prerelease', () => {
  for (const version of ['0.1.0-pre22-rc1', '0.1.0-pre21-rc2', '1.2.0-pre3-rc10']) {
    assert.equal(versionKind(version), 'pre-rc', version);
    assert.ok(isPrereleaseCandidate(version), version);
    assert.throws(() => distTag(version), /release candidate of a prerelease, which is never published/, version);
  }
  assert.ok(compareVersions('0.1.0-pre21-rc2', '0.1.0-pre21') > 0, 'semver orders it above the prerelease');
  assert.ok(compareVersions('1.0.0-rc1', '1.0.0') < 0, 'but X.Y.Z-rcN below X.Y.Z');
  assert.equal(finalVersionOf('0.1.0-pre21-rc2'), '0.1.0-pre21');
  assert.equal(finalVersionOf('1.2.0-pre3-rc10'), '1.2.0-pre3');
  assert.equal(distTag(finalVersionOf('0.1.0-pre21-rc2')), 'latest');
});

test('snapshots, ambiguous forms and malformed versions are refused', () => {
  for (const version of ['0.1.0-pre21-rc2-SNAPSHOT', '0.1.0-SNAPSHOT', '0.1.0-pre21-rc2-c8666d1c-SNAPSHOT']) {
    assert.throws(() => distTag(version), /snapshot/, version);
  }
  // Only X.Y.Z, -preN, -betaN, -rcN and -preN-rcN, as written (N from 1): nothing is guessed.
  for (const version of ['0.1.0-rc.1', '0.1.0-RC1', '0.1.0-Rc1', '0.1.0-rc', '0.1.0-rc0', '0.1.0-beta1-rc1',
    '0.1.0-rc1-pre2', '0.1.0-pre22-rc', '0.1.0-pre22-rc.1', '0.1.0-beta.2', '0.1.0-beta', '0.1.0-alpha1',
    '0.1.0-pre.22', '0.1.0-pre0', '0.1.0-preview1', '0.1.0-source1', '0.1.0-pre22.1', '0.1.0-pre22-beta1', '1.0.0-PRE1']) {
    assert.throws(() => distTag(version), /publishes only X\.Y\.Z, X\.Y\.Z-preN, X\.Y\.Z-betaN and X\.Y\.Z-rcN/, version);
    assert.throws(() => isPrereleaseCandidate(version), /publishes only/, version);
  }
  // npm's semver limits: 256 characters, safe integers, no leading zeros, no build metadata.
  for (const version of ['v0.1.0', '0.1', '0.1.0-', '01.0.0', '0.01.0', '0.1.0+build', '0.1.0-pre1+x', '', ' 0.1.0',
    '0.1.0-pre..1', '9007199254740992.0.0', '0.1.9007199254740993', `0.1.0-pre${'1'.repeat(250)}`]) {
    assert.throws(() => distTag(version), /not a semantic version/, JSON.stringify(version));
  }
});

test('the tags an older version may take: D8\'s, then release-<major>.<minor>, then release-<version>', () => {
  assert.deepEqual(candidateTags('0.1.0-pre21', 'latest'), ['latest', 'release-0.1', 'release-0.1.0-pre21']);
  assert.deepEqual(candidateTags('1.2.3', 'latest'), ['latest', 'release-1.2', 'release-1.2.3']);
  assert.deepEqual(candidateTags('1.0.0-beta2', 'beta'), ['beta', 'release-1.0', 'release-1.0.0-beta2']);
});

test('D8 per package: -preN on latest until the package has a final version, then on preview', () => {
  const doc = (...versions) => ({versions: Object.fromEntries(versions.map(v => [v, {}]))});
  assert.equal(hasFinalVersion(doc('0.0.0-oidc-bootstrap.0', '0.1.0-pre21')), false, 'the placeholder is a prerelease');
  assert.equal(hasFinalVersion(doc('0.1.0-pre21', '0.1.0')), true);
  assert.equal(hasFinalVersion(doc('0.1.0-rc1')), false);
  assert.equal(hasFinalVersion(null), false);
  assert.equal(d8Tag('0.1.0-pre22', doc('0.0.0-oidc-bootstrap.0', '0.1.0-pre21')), 'latest');
  assert.equal(d8Tag('0.1.0-pre22', doc('0.1.0-rc1')), 'latest', 'a release candidate is not a final version');
  assert.equal(d8Tag('0.2.0-pre1', doc('0.1.0')), 'preview');
  assert.equal(d8Tag('1.1.0-pre1', doc('1.0.0')), 'preview', 'any major');
  assert.equal(d8Tag('0.1.0-pre22', doc('0.1.0')), 'preview', 'D8\'s tag; packageTag then skips it below latest');
  assert.equal(d8Tag('0.2.0', doc('0.1.0', '0.2.0-pre1')), 'latest');
  assert.equal(d8Tag('0.2.0-beta1', doc('0.1.0')), 'beta');
  assert.equal(d8Tag('0.2.0-rc1', doc('0.1.0')), 'next');
  assert.equal(d8Tag('0.1.0-rc1', null), 'next');
  assert.equal(d8Tag('1.0.0', null), 'latest');
  assert.throws(() => d8Tag('0.2.0-pre1-rc1', doc('0.1.0')), /release candidate of a prerelease/);
});

test('a registry document is validated before use; anything incomplete fails, only a 404 means unpublished', () => {
  const name = '@julclang/wasm-client';
  const good = {name, 'dist-tags': {latest: '0.1.0-pre21', bootstrap: '0.0.0-oidc-bootstrap.0'},
    versions: {'0.1.0-pre21': {name, version: '0.1.0-pre21'}, '0.0.0-oidc-bootstrap.0': {name, version: '0.0.0-oidc-bootstrap.0'}}};
  assert.equal(validatePackument(good, name), good);
  // latest is required only of the three bootstrapped packages.
  const other = {name: 'other', versions: {'1.0.0': {version: '1.0.0'}}, 'dist-tags': {next: '1.0.0'}};
  assert.equal(validatePackument(other, 'other'), other);
  for (const [label, document] of [
    ['null', null], ['an empty object', {}], ['an error body', {error: 'internal'}], ['an array', []],
    ['dist-tags without versions', {name, 'dist-tags': {latest: '1.0.0'}}],
    ['versions without dist-tags', {name, versions: good.versions}],
    ['versions as an array', {...good, versions: []}], ['another package', {...good, name: '@julclang/julc-wasm'}],
    ['a version key that is not a version', {...good, versions: {...good.versions, 'not-a-version': {version: 'not-a-version'}}}],
    ['a version without its metadata', {...good, versions: {...good.versions, '0.1.0': null}}],
    ['metadata of another version', {...good, versions: {...good.versions, '0.1.0': {version: '0.2.0'}}}],
    ['a dist-tag naming a missing version', {...good, 'dist-tags': {...good['dist-tags'], latest: '1.0.0'}}],
    ['a dist-tag that is not a string', {...good, 'dist-tags': {latest: 1}}],
    ['an array dist-tag holder', {...good, 'dist-tags': {...good['dist-tags'], latest: ['0.1.0-pre21']}}],
    ['empty versions and dist-tags', {name, versions: {}, 'dist-tags': {}}],
    ['no latest for one of the three packages', {...good, 'dist-tags': {bootstrap: '0.0.0-oidc-bootstrap.0'}}],
  ]) {
    assert.throws(() => validatePackument(document, name), /the registry's document for @julclang\/wasm-client/, label);
  }
});

test('npm-verify\'s tag rule: a final on latest (or behind a later one), a prerelease on one of its tags', () => {
  const tags = t => ({'dist-tags': t});
  assert.deepEqual(tagProblems('0.2.0', tags({latest: '0.2.0'})), []);
  assert.deepEqual(tagProblems('0.2.0', tags({latest: '0.3.0'})), []);
  assert.match(tagProblems('0.2.0', tags({latest: '0.1.0', preview: '0.2.0'})).join(), /latest names 0\.1\.0/);
  assert.deepEqual(tagProblems('0.2.0-pre1', tags({latest: '0.1.0', preview: '0.2.0-pre1'})), []);
  assert.deepEqual(tagProblems('0.1.0-pre22', tags({latest: '0.1.0-pre22'})), []);
  assert.deepEqual(tagProblems('0.1.0-pre22', tags({latest: '0.1.0', 'release-0.1': '0.1.0-pre22'})), []);
  assert.deepEqual(tagProblems('0.1.0-pre21', tags({latest: '0.1.0', 'release-0.1.0-pre21': '0.1.0-pre21'})), []);
  assert.match(tagProblems('0.2.0-pre1', tags({latest: '0.1.0'})).join(), /none of the dist-tags latest, preview/);
  assert.deepEqual(tagProblems('0.2.0-beta1', tags({beta: '0.2.0-beta1'})), []);
  assert.match(tagProblems('0.2.0-beta1', tags({latest: '0.3.0'})).join(), /none of the dist-tags beta, release-0\.2/);
  assert.deepEqual(tagProblems('0.2.0-rc1', tags({latest: '0.1.0', next: '0.2.0-rc1'})), []);
  assert.match(tagProblems('0.2.0-rc1', tags({latest: '0.1.0', preview: '0.2.0-rc1'})).join(),
    /none of the dist-tags next, release-0\.2, release-0\.2\.0-rc1/);
});


test('version precedence matches semver, including the orders D8 relies on', () => {
  assert.ok(compareVersions('0.1.0-pre21-rc2', '0.1.0-pre21') > 0, 'a candidate sorts above its final release');
  assert.ok(compareVersions('0.1.0-pre100', '0.1.0-pre20') < 0, 'preN compares as text (open question 8)');
  assert.ok(compareVersions('0.1.0-beta1', '0.1.0-pre1') < 0, 'beta sorts below every preN');
  assert.ok(compareVersions('0.1.0', '0.1.0-pre99') > 0);
  assert.ok(compareVersions('0.1.0-pre.10', '0.1.0-pre.9') > 0, 'numeric identifiers compare as numbers');
  assert.equal(compareVersions('0.1.0-pre22', '0.1.0-pre22'), 0);
  // Against npm's own semver, when the npm in use can be found. Also: the fallback tags are not semver ranges, which
  // npm publish --tag refuses.
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
  for (const version of versions) {
    for (const tag of candidateTags(version, 'latest').slice(1)) assert.equal(semver.validRange(tag), null, tag);
  }
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
  assert.throws(() => certificateIdentity({...bundle, verificationMaterial: {publicKey: {hint: 'x'}}}), /signed by a key/);
  assert.throws(() => statementOf({...bundle, dsseEnvelope: {...bundle.dsseEnvelope, payloadType: 'text/plain'}}), /in-toto/);
  const truncated = structuredClone(bundle);
  truncated.verificationMaterial.certificate.rawBytes = Buffer.from(bundle.verificationMaterial.certificate.rawBytes,
    'base64').subarray(0, 200).toString('base64');
  assert.throws(() => certificateIdentity(truncated), /truncated DER|malformed/);
});

// The bundle shapes Sigstore has used: v0.1 and v0.2 carry an x509CertificateChain (leaf first), v0.3 a single
// certificate. npm's deserializer takes the first alternative set, in the order publicKey, x509CertificateChain,
// certificate, and verifies against that one; JuLC must read exactly that certificate, or refuse.
test('the signing certificate is the one Sigstore verification uses, and ambiguous bundles are refused', () => {
  const bundle = verifiedProvenance(joustAudit, JOUST_NAME, JOUST_VERSION);
  const raw = bundle.verificationMaterial.certificate.rawBytes;
  const other = Buffer.from(raw, 'base64');
  other[other.length - 1] ^= 1; // another certificate (its signature byte changed): never verified by anyone
  const otherRaw = other.toString('base64');
  const shaped = material => ({...bundle, verificationMaterial: {tlogEntries: [], ...material}});
  const v03 = shaped({certificate: {rawBytes: raw}});
  const v01 = {...shaped({x509CertificateChain: {certificates: [{rawBytes: raw}, {rawBytes: otherRaw}]}}),
    mediaType: 'application/vnd.dev.sigstore.bundle+json;version=0.1'};
  const v02 = {...shaped({x509CertificateChain: {certificates: [{rawBytes: raw}]}}),
    mediaType: 'application/vnd.dev.sigstore.bundle+json;version=0.2'};
  for (const accepted of [v03, v01, v02]) {
    assert.deepEqual(signingCertificate(accepted), Buffer.from(raw, 'base64'), accepted.mediaType);
    assert.deepEqual(certificateProblems(accepted, joustIdentity), [], accepted.mediaType);
  }
  // A chain whose leaf (the one verified) is another certificate is read as that certificate, not as a later one.
  const leafOther = shaped({x509CertificateChain: {certificates: [{rawBytes: otherRaw}, {rawBytes: raw}]}});
  assert.deepEqual(signingCertificate(leafOther), other);
  // More than one alternative: npm would verify the chain (or the key), JuLC would otherwise read the certificate.
  for (const [label, material] of [
    ['chain and certificate', {x509CertificateChain: {certificates: [{rawBytes: otherRaw}]}, certificate: {rawBytes: raw}}],
    ['key and certificate', {publicKey: {hint: 'SHA256:x'}, certificate: {rawBytes: raw}}],
    ['key and chain', {publicKey: {hint: 'SHA256:x'}, x509CertificateChain: {certificates: [{rawBytes: raw}]}}],
  ]) {
    assert.throws(() => signingCertificate(shaped(material)), /exactly one kind of verification material/, label);
    assert.throws(() => certificateProblems(shaped(material), joustIdentity), /exactly one kind/, label);
  }
  assert.throws(() => signingCertificate(shaped({publicKey: {hint: 'SHA256:x'}})), /signed by a key/);
  assert.throws(() => signingCertificate(shaped({})), /none/);
  assert.throws(() => signingCertificate(shaped({x509CertificateChain: {certificates: []}})), /no signing certificate/);
  // null counts as absent, as in the deserializer.
  assert.deepEqual(signingCertificate(shaped({publicKey: null, x509CertificateChain: null, certificate: {rawBytes: raw}})),
    Buffer.from(raw, 'base64'));
});
