// ADR-077 D8 and D8a: what the npm-publish and npm-verify jobs of native-image-release.yml share. Plain Node.js with
// no dependencies: those jobs check out only this directory and install nothing from it.
import {execFileSync} from 'node:child_process';
import {createHash} from 'node:crypto';
import {existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import path from 'node:path';

/** The three packages, in publication order: the client first, as both engines depend on it (D8a). */
export const PACKAGES = ['@julclang/wasm-client', '@julclang/julc-vm-wasm', '@julclang/julc-wasm'];
/** The identity every published version's provenance must carry (I12). */
export const REPOSITORY = 'bloxbean/julc';
export const WORKFLOW = '.github/workflows/native-image-release.yml';
export const DEFAULT_REGISTRY = 'https://registry.npmjs.org/';
export const SLSA_PROVENANCE_V1 = 'https://slsa.dev/provenance/v1';
const GITHUB_ISSUER = 'https://token.actions.githubusercontent.com';
const INSTALL_SCRIPTS = ['preinstall', 'install', 'postinstall', 'prepare', 'preprepare', 'postprepare'];

export const tarballName = (name, version) => `${name.slice(1).replace('/', '-')}-${version}.tgz`;
export const sumsName = version => `julc-npm-${version}-SHA256SUMS.txt`;
export const archiveNames = version => [`julc-vm-wasm-${version}.tar.gz`, `julc-wasm-${version}.tar.gz`];
export const releaseTag = version => `refs/tags/v${version}`;
/** The identity of this workflow at a release's tag. */
export const releaseIdentity = version => ({repository: REPOSITORY, workflow: WORKFLOW, ref: releaseTag(version)});

export class ReleaseError extends Error {}
const fail = message => { throw new ReleaseError(message); };

// --- Versions and dist-tags (D8) ---

const IDENTIFIER = '(?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*)';
const SEMVER = new RegExp(`^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:-(${IDENTIFIER}(?:\\.${IDENTIFIER})*))?$`);

/**
 * A strict semantic version without build metadata, within npm's limits (its semver package: at most 256 characters,
 * each number a safe integer, no leading zeros).
 */
export function parseVersion(version) {
  const text = String(version);
  const match = text.length <= 256 ? SEMVER.exec(text) : null;
  if (!match) fail(`not a semantic version: ${JSON.stringify(version)}`);
  const numbers = [match[1], match[2], match[3], ...(match[4] ?? '').split('.').filter(part => /^\d+$/.test(part))];
  if (numbers.some(number => !Number.isSafeInteger(Number(number)))) fail(`not a semantic version: ${JSON.stringify(version)}`);
  return {major: Number(match[1]), minor: Number(match[2]), patch: Number(match[3]),
    prerelease: match[4] ? match[4].split('.') : []};
}

/** Semantic-version precedence (semver 2.0.0, section 11): negative, zero or positive. */
export function compareVersions(a, b) {
  const x = parseVersion(a);
  const y = parseVersion(b);
  for (const part of ['major', 'minor', 'patch']) if (x[part] !== y[part]) return Math.sign(x[part] - y[part]);
  // A version without a prerelease part ranks above every prerelease of it.
  if (!x.prerelease.length || !y.prerelease.length) {
    return Math.sign(Number(!x.prerelease.length) - Number(!y.prerelease.length));
  }
  for (let i = 0; i < Math.max(x.prerelease.length, y.prerelease.length); i++) {
    const [p, q] = [x.prerelease[i], y.prerelease[i]];
    if (p === undefined) return -1;
    if (q === undefined) return 1;
    const [pn, qn] = [/^\d+$/.test(p), /^\d+$/.test(q)];
    if (pn && qn) { if (Number(p) !== Number(q)) return Math.sign(Number(p) - Number(q)); continue; }
    if (pn !== qn) return pn ? -1 : 1;
    if (p !== q) return p < q ? -1 : 1;
  }
  return 0;
}

// D8's table (r23). The tag names are these constants; each appears nowhere else.
/** A final version, and a -preN while its package has no final version on the registry. */
export const LATEST_TAG = 'latest';
/** A -preN once its package has a final version on the registry. */
export const PREVIEW_TAG = 'preview';
/** A -betaN. */
export const BETA_TAG = 'beta';
/** A release candidate of a final version, X.Y.Z-rcN. */
export const CANDIDATE_TAG = 'next';

/**
 * The form of a release version, by its whole prerelease part (identifiers as written, case-sensitive):
 * - 'final': none (0.1.0, 1.0.0);
 * - 'pre': preN (0.1.0-pre22);
 * - 'beta': betaN (0.2.0-beta1);
 * - 'rc': rcN, a release candidate of a final version (0.1.0-rc1, 1.0.0-rc2);
 * - 'pre-rc': preN-rcN, a release candidate of a prerelease (0.1.0-pre22-rc1), never published: semver orders it
 *   above preN, so ranges would choose it over the release.
 * A snapshot, and any other form (-rc.1, -RC1, -beta1-rc1, -alpha1, -pre.22, -preview1, ...), is refused.
 */
export function versionKind(version) {
  if (/snapshot/i.test(String(version))) fail(`${version} is a snapshot build, which is never published to npm (ADR-077 D8)`);
  const text = parseVersion(version).prerelease.join('.');
  if (!text) return 'final';
  if (/^pre[1-9]\d*$/.test(text)) return 'pre';
  if (/^beta[1-9]\d*$/.test(text)) return 'beta';
  if (/^rc[1-9]\d*$/.test(text)) return 'rc';
  if (/^pre[1-9]\d*-rc[1-9]\d*$/.test(text)) return 'pre-rc';
  fail(`${version}: ADR-077 D8 publishes only X.Y.Z, X.Y.Z-preN, X.Y.Z-betaN and X.Y.Z-rcN (N from 1); `
    + `not "-${text}"`);
}

/** A release candidate of a prerelease (X.Y.Z-preN-rcN): dry-run only. */
export const isPrereleaseCandidate = version => versionKind(version) === 'pre-rc';

/**
 * The tag D8's table gives a version before looking at the registry, or a refusal: final and -preN `latest` (a -preN
 * may become `preview`: see d8Tag), -betaN `beta`, X.Y.Z-rcN `next`. A release candidate of a prerelease, a snapshot,
 * and any other form are refused.
 */
export function distTag(version) {
  const kind = versionKind(version);
  if (kind === 'pre-rc') {
    fail(`${version} is a release candidate of a prerelease, which is never published to npm: semver orders it above `
      + `${finalVersionOf(version)}, so ranges would choose it (ADR-077 D8)`);
  }
  return {final: LATEST_TAG, pre: LATEST_TAG, beta: BETA_TAG, rc: CANDIDATE_TAG}[kind];
}

/** Whether a package's registry document has a final (no-prerelease) version. */
export function hasFinalVersion(document) {
  return Object.keys(document?.versions ?? {}).some(version => {
    try { return parseVersion(version).prerelease.length === 0; } catch { return false; }
  });
}

/**
 * D8's tag for one package's version, given its registry document at publication time: a -preN goes to `latest` only
 * while the package has no final version; once it has one, to `preview`. Anything else as distTag.
 */
export function d8Tag(version, document) {
  const tag = distTag(version);
  if (versionKind(version) === 'pre' && hasFinalVersion(document)) return PREVIEW_TAG;
  return tag;
}

/**
 * The dist-tags a version may be published with, in order of preference (D8): D8's tag, then `release-<major>.<minor>`,
 * then `release-<version>`, which can only ever name this version. None of them is a semver range (npm refuses those).
 */
export function candidateTags(version, tag) {
  const {major, minor} = parseVersion(version);
  return [tag, `release-${major}.${minor}`, `release-${version}`];
}

/**
 * The tag one package's version is published with: the first candidate that does not already name a higher version of
 * that package, so no dist-tag ever moves backwards (a recovery of version N after N+1, a maintenance release, or a
 * package of N+1 published before the others). A prerelease channel (`preview`, `beta`, `next`) is also skipped unless
 * the version is ahead of `latest`: a channel is what comes after `latest`. Chosen per package, from the registry as
 * it is at publication time; the workflow publishes one version at a time (one concurrency group for every npm
 * publication).
 */
export async function packageTag(registry, name, version) {
  const document = await packument(registry, name);
  const tags = document?.['dist-tags'] ?? {};
  const higher = [];
  for (const candidate of candidateTags(version, d8Tag(version, document))) {
    const holder = tags[candidate];
    const channel = [PREVIEW_TAG, BETA_TAG, CANDIDATE_TAG].includes(candidate);
    if (channel && tags[LATEST_TAG] !== undefined && compareVersions(tags[LATEST_TAG], version) >= 0) {
      higher.push(`${LATEST_TAG} is ${tags[LATEST_TAG]}, not behind it (for ${candidate})`);
      continue;
    }
    if (holder === undefined || compareVersions(holder, version) <= 0) {
      return {tag: candidate, reason: higher.length ? `${name}: ${higher.join(', ')}, higher than ${version}` : null};
    }
    higher.push(`${candidate} is ${holder}`);
  }
  fail(`${name}: ${higher.join(', ')}: every tag D8 allows names a higher version`);
}

/**
 * What is wrong with one package's dist-tags for a published version (npm-verify): a final version is named by
 * `latest` or a later version; a prerelease by one of the tags it may have been published with (D8's tag, `latest` or
 * `preview` for a -preN, `release-<major>.<minor>`, `release-<version>`), or a later version.
 */
export function tagProblems(version, document) {
  const tags = document?.['dist-tags'] ?? {};
  const atOrAbove = tag => tags[tag] !== undefined && compareVersions(tags[tag], version) >= 0;
  const kind = versionKind(version);
  if (kind === 'final') {
    return atOrAbove(LATEST_TAG) ? []
      : [`dist-tag ${LATEST_TAG} names ${tags[LATEST_TAG] ?? 'nothing'}, expected ${version} or later`];
  }
  const base = distTag(version);
  const candidates = [...new Set([...(kind === 'pre' ? [LATEST_TAG, PREVIEW_TAG] : [base]),
    ...candidateTags(version, base).slice(1)])];
  return candidates.some(atOrAbove) ? []
    : [`none of the dist-tags ${candidates.join(', ')} names ${version} or later (${JSON.stringify(tags)})`];
}

/** A release candidate of a prerelease's own version: 0.1.0-pre21-rc2 gives 0.1.0-pre21. */
export function finalVersionOf(version) {
  const {major, minor, patch, prerelease} = parseVersion(version);
  return `${major}.${minor}.${patch}-${prerelease.join('.').replace(/-rc[1-9]\d*$/, '')}`;
}

// --- Local files: the release's tarballs (the npm-packages artifact), their checksum file and the wasm job's digests
// (D8a, I1, I11) ---

export const sha256Hex = bytes => createHash('sha256').update(bytes).digest('hex');
export const sha512Hex = bytes => createHash('sha512').update(bytes).digest('hex');
export const integrityOf = bytes => 'sha512-' + createHash('sha512').update(bytes).digest('base64');
export const shasumOf = bytes => createHash('sha1').update(bytes).digest('hex');

/** `sha256sum` output: one `<digest>  <name>` (or ` *<name>`) per line, no name twice. */
export function parseSums(text) {
  const sums = new Map();
  for (const line of text.split('\n').filter(l => l.trim())) {
    const match = /^([0-9a-f]{64}) [ *]([^/\\\s]+)$/.exec(line);
    if (!match) fail(`malformed checksum line: ${JSON.stringify(line)}`);
    if (sums.has(match[2])) fail(`checksum file names ${match[2]} twice`);
    sums.set(match[2], match[1]);
  }
  return sums;
}

/** `--digest <file>=<sha256>` values, the wasm job's outputs in a normal run. */
export function parseDigests(values) {
  const digests = new Map();
  for (const value of values) {
    const match = /^([^=\s/\\]+)=([0-9a-f]{64})$/.exec(value);
    if (!match) fail(`malformed --digest ${JSON.stringify(value)}: expected <file>=<sha256 hex>`);
    if (digests.has(match[1])) fail(`--digest names ${match[1]} twice`);
    digests.set(match[1], match[2]);
  }
  return digests;
}

/** package/package.json inside an npm tarball, read without extracting anything else. */
export function tarballManifest(file) {
  return JSON.parse(execFileSync('tar', ['-xzOf', file, 'package/package.json'], {encoding: 'utf8'}));
}

/**
 * The three tarballs in `dir`, checked before anything is published or compared:
 * - the checksum file lists exactly the three tarballs and the two archives, and each of the five files is in `dir`
 *   with the SHA-256 of its line;
 * - every digest the wasm job emitted (in a normal run) equals the checksum file's line, and the file's own digest;
 * - each tarball is the package and version expected, with no install scripts (I7), and an engine depends on exactly
 *   this version of the client (I2).
 */
export function localTarballs(dir, version, digests = new Map()) {
  const sumsFile = path.join(dir, sumsName(version));
  if (!existsSync(sumsFile)) fail(`missing ${sumsName(version)} in ${dir}`);
  const sums = parseSums(readFileSync(sumsFile, 'utf8'));
  const expected = [...PACKAGES.map(name => tarballName(name, version)), ...archiveNames(version)].sort();
  if (JSON.stringify([...sums.keys()].sort()) !== JSON.stringify(expected)) {
    fail(`${sumsName(version)} must list exactly ${expected.join(', ')}; it lists ${[...sums.keys()].sort().join(', ')}`);
  }
  for (const [file, digest] of digests) {
    if (!sums.has(file)) fail(`the wasm job's digest names ${file}, which ${sumsName(version)} does not list`);
    if (sums.get(file) !== digest) {
      fail(`${file}: ${sumsName(version)} says ${sums.get(file)}, the wasm job's digest output says ${digest}`);
    }
  }
  // The engine archives are part of the release npm depends on (I1): present, and the bytes the checksum file names.
  for (const archive of archiveNames(version)) {
    const file = path.join(dir, archive);
    if (!existsSync(file)) fail(`missing ${archive} in ${dir}`);
    const sha256 = sha256Hex(readFileSync(file));
    if (sha256 !== sums.get(archive)) fail(`${archive}: SHA-256 ${sha256}, but ${sumsName(version)} says ${sums.get(archive)}`);
  }
  const tarballs = PACKAGES.map(name => {
    const file = path.join(dir, tarballName(name, version));
    if (!existsSync(file)) fail(`missing ${tarballName(name, version)} in ${dir}`);
    const bytes = readFileSync(file);
    const sha256 = sha256Hex(bytes);
    if (sha256 !== sums.get(path.basename(file))) {
      fail(`${path.basename(file)}: SHA-256 ${sha256}, but ${sumsName(version)} says ${sums.get(path.basename(file))}`);
    }
    const pkg = tarballManifest(file);
    if (pkg.name !== name || pkg.version !== version) {
      fail(`${path.basename(file)} holds ${pkg.name}@${pkg.version}, not ${name}@${version}`);
    }
    for (const script of INSTALL_SCRIPTS) if (pkg.scripts?.[script] !== undefined) fail(`${name} has a ${script} script (I7)`);
    const expectedDependencies = name === PACKAGES[0] ? undefined : {[PACKAGES[0]]: version};
    if (JSON.stringify(pkg.dependencies) !== JSON.stringify(expectedDependencies)) {
      fail(`${name}'s dependencies are ${JSON.stringify(pkg.dependencies)}, expected ${JSON.stringify(expectedDependencies)}`);
    }
    return {name, file, bytes, sha256, sha512: sha512Hex(bytes), integrity: integrityOf(bytes), shasum: shasumOf(bytes)};
  });
  for (const [engine, archive] of [[PACKAGES[1], archiveNames(version)[0]], [PACKAGES[2], archiveNames(version)[1]]]) {
    engineIdentity(path.join(dir, tarballName(engine, version)), path.join(dir, archive), engine);
  }
  return tarballs;
}

/**
 * I1 within one set of npm files: an engine tarball's engine/ launcher, module and engine.json are the matching
 * archive's, byte for byte. With the archives also equal to the published release's (npm-files.sh), the tarballs
 * provably carry the engines that are public, so files of two builds can never be mixed.
 */
export function engineIdentity(tarball, archive, name) {
  const work = mkdtempSync(path.join(tmpdir(), 'julc-npm-identity-'));
  try {
    const packed = path.join(work, 'packed');
    const released = path.join(work, 'released');
    for (const [file, into] of [[tarball, packed], [archive, released]]) {
      mkdirSync(into, {recursive: true});
      execFileSync('tar', ['-xzf', file, '-C', into]);
    }
    const read = file => existsSync(file) ? readFileSync(file) : fail(`${name}: no ${path.relative(work, file)}`);
    const manifestBytes = read(path.join(packed, 'package', 'engine', 'engine.json'));
    let launcher;
    try { ({launcher} = JSON.parse(manifestBytes.toString('utf8'))); } catch { launcher = undefined; }
    if (typeof launcher !== 'string' || !/^[a-z]+(-[a-z]+)*-[0-9a-f]{12}\.js$/.test(launcher)) {
      fail(`${name}: engine/engine.json names no plain launcher`);
    }
    for (const file of ['engine.json', launcher, launcher + '.wasm']) {
      if (!read(path.join(packed, 'package', 'engine', file)).equals(read(path.join(released, file)))) {
        fail(`${name}: engine/${file} differs from ${path.basename(archive)}'s (I1): the files are not one build's`);
      }
    }
  } finally {
    rmSync(work, {recursive: true, force: true});
  }
}

// --- The registry ---

export const normaliseRegistry = registry => (registry ?? DEFAULT_REGISTRY).replace(/\/*$/, '/');
export function isLoopbackRegistry(registry) {
  const {hostname} = new URL(normaliseRegistry(registry));
  return ['localhost', '127.0.0.1', '[::1]', '::1'].includes(hostname);
}
const escapedName = name => name.replace('/', '%2f');

const isObject = value => typeof value === 'object' && value !== null && !Array.isArray(value);

/**
 * A package's registry document, checked before anything reads it: an object for this package, with object-valued
 * `versions` (each key a version, each value an object) and `dist-tags` (each holder a version in `versions`). An
 * incomplete document (no `versions`, null, {}, an error body) must never make a prerelease look like "no final version
 * yet" or switch the no-backwards check off, so it fails the job; only a 404 means "not published".
 */
export function validatePackument(document, name) {
  const where = `the registry's document for ${name}`;
  if (!isObject(document)) fail(`${where} is not an object: ${JSON.stringify(document)?.slice(0, 200)}`);
  if (document.name !== name) fail(`${where} names ${JSON.stringify(document.name)}`);
  if (!isObject(document.versions)) fail(`${where} has no versions object`);
  if (!isObject(document['dist-tags'])) fail(`${where} has no dist-tags object`);
  // A published package has versions, and every package of this release already has latest (its bootstrap
  // placeholder, D8a): a genuinely new package answers 404, never an empty or latest-less 200.
  if (Object.keys(document.versions).length === 0) fail(`${where} has no versions`);
  if (PACKAGES.includes(name) && !Object.hasOwn(document['dist-tags'], 'latest')) fail(`${where} has no latest tag`);
  for (const [version, meta] of Object.entries(document.versions)) {
    try { parseVersion(version); } catch { fail(`${where} lists a version that is not one: ${JSON.stringify(version)}`); }
    if (!isObject(meta) || meta.version !== version || meta.name !== name) {
      fail(`${where}: version ${version} has no metadata of its own (name ${JSON.stringify(meta?.name)})`);
    }
  }
  for (const [tag, holder] of Object.entries(document['dist-tags'])) {
    if (typeof holder !== 'string' || !Object.hasOwn(document.versions, holder)) {
      fail(`${where}: dist-tag ${tag} names ${JSON.stringify(holder)}, which is not one of its versions`);
    }
  }
  return document;
}

/** The registry's full metadata for a package, validated, or null when the registry does not know it (404). */
export async function packument(registry, name) {
  const response = await fetch(normaliseRegistry(registry) + escapedName(name), {
    headers: {accept: 'application/json'}, cache: 'no-store'});
  if (response.status === 404) return null;
  // Exactly 200: anything else (a 206 partial answer, a 304, a 5xx) is not a document to decide on.
  if (response.status !== 200) fail(`GET ${name} from ${normaliseRegistry(registry)}: HTTP ${response.status}`);
  let document;
  try { document = await response.json(); } catch { fail(`GET ${name}: the registry's answer is not JSON`); }
  return validatePackument(document, name);
}

/** One version's metadata, or null when the registry does not (yet) show it. */
export async function registryVersion(registry, name, version) {
  return (await packument(registry, name))?.versions?.[version] ?? null;
}

// --- Provenance (I11, I12) ---

/** The in-toto statement of a provenance bundle, decoded from its DSSE envelope. */
export function statementOf(bundle) {
  const envelope = bundle?.dsseEnvelope;
  if (envelope?.payloadType !== 'application/vnd.in-toto+json') fail('the provenance bundle has no in-toto DSSE payload');
  return JSON.parse(Buffer.from(envelope.payload, 'base64').toString('utf8'));
}

/** The registry's provenance bundle for a version, read from its metadata (`dist.attestations`); null if it has none. */
export async function registryProvenance(dist) {
  const attestations = dist?.attestations;
  if (!attestations?.url || attestations.provenance?.predicateType !== SLSA_PROVENANCE_V1) return null;
  const response = await fetch(attestations.url, {headers: {accept: 'application/json'}, cache: 'no-store'});
  if (response.status === 404) return null;
  if (!response.ok) fail(`GET ${attestations.url}: HTTP ${response.status}`);
  let document;
  try { document = await response.json(); } catch { fail(`GET ${attestations.url}: the answer is not JSON`); }
  if (!isObject(document) || !Array.isArray(document.attestations)) {
    fail(`GET ${attestations.url}: no attestations list in the answer`);
  }
  return document.attestations.find(entry => isObject(entry) && entry.predicateType === SLSA_PROVENANCE_V1)?.bundle
    ?? null;
}

const purl = (name, version) => `pkg:npm/${name.replace('@', '%40')}@${version}`;

/**
 * What is wrong with a provenance statement for a version (an empty list when nothing is): it names exactly this
 * package version, with the tarball's SHA-512, built on a GitHub-hosted runner by this repository's release workflow
 * at the release tag. Reads the statement only: its signature is the npm-verify job's to check.
 */
export function statementProblems(statement, {name, version, sha512, identity}) {
  const problems = [];
  const check = (ok, message) => { if (!ok) problems.push(message); };
  check(statement?._type === 'https://in-toto.io/Statement/v1', `statement type ${statement?._type}`);
  check(statement?.predicateType === SLSA_PROVENANCE_V1, `predicate type ${statement?.predicateType}`);
  const subjects = statement?.subject ?? [];
  check(subjects.length === 1 && subjects[0].name === purl(name, version),
    `subject ${JSON.stringify(subjects.map(subject => subject.name))}, expected ${purl(name, version)}`);
  check(subjects[0]?.digest?.sha512 === sha512, `subject sha512 ${subjects[0]?.digest?.sha512}, the tarball's is ${sha512}`);
  const workflow = statement?.predicate?.buildDefinition?.externalParameters?.workflow ?? {};
  const expected = {ref: identity.ref, repository: `https://github.com/${identity.repository}`, path: identity.workflow};
  for (const [key, value] of Object.entries(expected)) {
    check(workflow[key] === value, `workflow ${key} ${JSON.stringify(workflow[key])}, expected ${JSON.stringify(value)}`);
  }
  const builder = statement?.predicate?.runDetails?.builder?.id;
  check(builder === 'https://github.com/actions/runner/github-hosted', `builder ${builder}`);
  return problems;
}

// A minimal DER reader: enough to take the Fulcio extensions out of a signing certificate.
function tlv(der, at) {
  if (at + 2 > der.length) fail('truncated DER');
  let length = der[at + 1];
  let start = at + 2;
  if (length & 0x80) {
    const count = length & 0x7f;
    if (count === 0 || count > 4) fail('unsupported DER length');
    length = 0;
    for (let i = 0; i < count; i++) length = length * 256 + der[start++];
  }
  if (start + length > der.length) fail('truncated DER');
  return {tag: der[at], start, end: start + length};
}
function children(der, parent) {
  const list = [];
  for (let at = parent.start; at < parent.end;) {
    const child = tlv(der, at);
    list.push(child);
    at = child.end;
  }
  return list;
}
function oidText(bytes) {
  const parts = [Math.floor(bytes[0] / 40), bytes[0] % 40];
  let value = 0;
  for (const byte of bytes.subarray(1)) {
    value = value * 128 + (byte & 0x7f);
    if (!(byte & 0x80)) { parts.push(value); value = 0; }
  }
  return parts.join('.');
}

/** A certificate's extensions, as OID → the extension value's bytes (the OCTET STRING's contents). */
export function certificateExtensions(der) {
  const certificate = tlv(der, 0);
  const [tbs] = children(der, certificate);
  const wrapper = children(der, tbs).find(child => child.tag === 0xa3);
  if (!wrapper) fail('the certificate has no extensions');
  const extensions = new Map();
  for (const extension of children(der, children(der, wrapper)[0])) {
    const parts = children(der, extension);
    const value = parts[parts.length - 1];
    if (parts[0].tag !== 0x06 || value.tag !== 0x04) fail('malformed certificate extension');
    extensions.set(oidText(der.subarray(parts[0].start, parts[0].end)), der.subarray(value.start, value.end));
  }
  return extensions;
}

// Fulcio's certificate extensions (sigstore/fulcio docs/oid-info.md), each a DER UTF8String.
const FULCIO = {issuer: '1.3.6.1.4.1.57264.1.8', buildSignerUri: '1.3.6.1.4.1.57264.1.9',
  runnerEnvironment: '1.3.6.1.4.1.57264.1.11', sourceRepositoryUri: '1.3.6.1.4.1.57264.1.12',
  sourceRepositoryRef: '1.3.6.1.4.1.57264.1.14'};

// A Sigstore bundle's verification material is a protobuf oneof. npm 11.21.1's deserializer
// (@sigstore/protobuf-specs, VerificationMaterial.fromJSON) takes the first that is set, in the order publicKey,
// x509CertificateChain, certificate, and @sigstore/verify checks the signature against that one: for a chain, its first
// (leaf) certificate. Reading another alternative could read a certificate nothing verified, so a bundle with more than
// one, or with a public key instead of a Fulcio certificate, is refused.
const isSet = value => value !== null && value !== undefined;
const MATERIAL = ['publicKey', 'x509CertificateChain', 'certificate'];

/** The DER bytes of the certificate Sigstore verification checks a bundle's signature against. */
export function signingCertificate(bundle) {
  const material = bundle?.verificationMaterial;
  const present = MATERIAL.filter(kind => isSet(material?.[kind]));
  if (present.length !== 1) {
    fail(`the provenance bundle must carry exactly one kind of verification material; it has ${present.join(', ') || 'none'}`);
  }
  if (present[0] === 'publicKey') fail('the provenance bundle is signed by a key, not a Fulcio certificate');
  const raw = present[0] === 'certificate' ? material.certificate.rawBytes
    : material.x509CertificateChain.certificates?.[0]?.rawBytes;
  if (typeof raw !== 'string' || !raw) fail('the provenance bundle has no signing certificate');
  return Buffer.from(raw, 'base64');
}

/** The signer's identity from a Sigstore bundle's Fulcio certificate (the one its signature is verified against). */
export function certificateIdentity(bundle) {
  const extensions = certificateExtensions(signingCertificate(bundle));
  const identity = {};
  for (const [key, oid] of Object.entries(FULCIO)) {
    const value = extensions.get(oid);
    if (!value) continue;
    const string = tlv(value, 0);
    if (string.tag !== 0x0c || string.end !== value.length) fail(`certificate extension ${oid} is not a UTF8String`);
    identity[key] = value.subarray(string.start, string.end).toString('utf8');
  }
  return identity;
}

/** What is wrong with a bundle's signing certificate for this workflow at the release tag (empty when nothing is). */
export function certificateProblems(bundle, identity) {
  const actual = certificateIdentity(bundle);
  const expected = {
    issuer: GITHUB_ISSUER,
    buildSignerUri: `https://github.com/${identity.repository}/${identity.workflow}@${identity.ref}`,
    runnerEnvironment: 'github-hosted',
    sourceRepositoryUri: `https://github.com/${identity.repository}`,
    sourceRepositoryRef: identity.ref,
  };
  return Object.entries(expected).filter(([key, value]) => actual[key] !== value)
    .map(([key, value]) => `certificate ${key} ${JSON.stringify(actual[key])}, expected ${JSON.stringify(value)}`);
}

/** The provenance bundle of `name@version` among those `npm audit signatures` verified. */
export function verifiedProvenance(audit, name, version) {
  const entries = (audit.verified ?? []).filter(entry => entry.name === name && entry.version === version);
  if (entries.length !== 1) return null;
  return (entries[0].attestationBundles ?? []).find(entry => entry.predicateType === SLSA_PROVENANCE_V1)?.bundle ?? null;
}
