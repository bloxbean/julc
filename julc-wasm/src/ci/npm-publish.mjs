// ADR-077 D8a: the npm-publish job of native-image-release.yml. Publishes the three tarballs the wasm job packed, as the
// GitHub release holds them (the workflow verifies them against their attestation first), in dependency order, with
// npm trusted publishing and provenance. Never publishes a version twice: a version already on npm is skipped only
// when it is this tarball, with provenance from this workflow at this tag (I11); anything else stops the job before
// the next package.
//
//   node npm-publish.mjs --dir <release assets> --version <version> --mode publish|dry-run
//        [--digest <file>=<sha256>]...   the wasm job's digest outputs (a normal run; none on dispatch)
//        [--registry <url>] [--npm <npm command>] [--visibility-timeout <seconds>]
//        [--no-provenance]               only against a loopback registry, for the local tests
//
// Writes `published` (true only when every package was published or verified as already published) and `tags` (a
// JSON object: the dist-tag chosen for each package this run published itself) to $GITHUB_OUTPUT when it is set. A dry run (a release candidate, or npm_publish = false) publishes nothing.
import {spawnSync} from 'node:child_process';
import {appendFileSync} from 'node:fs';
import path from 'node:path';
import {parseArgs} from 'node:util';
import {
  ReleaseError, distTag, finalVersionOf, isLoopbackRegistry, isReleaseCandidate, localTarballs, normaliseRegistry,
  packageTag, parseDigests, registryProvenance, registryVersion, releaseIdentity, statementOf, statementProblems,
} from './npm-release.mjs';

const {values: options} = parseArgs({options: {
  dir: {type: 'string'}, version: {type: 'string'}, mode: {type: 'string'}, digest: {type: 'string', multiple: true},
  registry: {type: 'string'}, npm: {type: 'string'}, 'visibility-timeout': {type: 'string'},
  'no-provenance': {type: 'boolean'},
}});

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const output = (key, value) => {
  if (process.env.GITHUB_OUTPUT) appendFileSync(process.env.GITHUB_OUTPUT, `${key}=${value}\n`);
  console.log(`output ${key}=${value}`);
};
const summary = line => { if (process.env.GITHUB_STEP_SUMMARY) appendFileSync(process.env.GITHUB_STEP_SUMMARY, line + '\n'); };

/**
 * Whether the registry's `name@version` is exactly this tarball with provenance from this workflow at the release
 * tag: 'absent', 'match', or a list of what differs.
 */
async function registryState(registry, tarball, version, identity) {
  const meta = await registryVersion(registry, tarball.name, version);
  if (!meta) return {state: 'absent'};
  const problems = [];
  if (meta.dist?.integrity !== tarball.integrity) {
    problems.push(`registry integrity ${meta.dist?.integrity}, the release's tarball is ${tarball.integrity}`);
  }
  if (meta.dist?.shasum !== undefined && meta.dist.shasum !== tarball.shasum) {
    problems.push(`registry shasum ${meta.dist.shasum}, the release's tarball is ${tarball.shasum}`);
  }
  const bundle = await registryProvenance(meta.dist);
  if (!bundle) {
    problems.push('no provenance attestation (published by hand, or by another workflow without provenance)');
    return {state: 'differs', problems, missingProvenance: true};
  }
  problems.push(...statementProblems(statementOf(bundle), {name: tarball.name, version, sha512: tarball.sha512, identity}));
  return problems.length ? {state: 'differs', problems} : {state: 'match'};
}

function npmPublish(npm, tarball, tag, {dryRun, provenance, registry}) {
  const args = ['publish', path.resolve(tarball.file), '--access', 'public', '--tag', tag, '--ignore-scripts'];
  if (provenance) args.push('--provenance');
  if (dryRun) args.push('--dry-run');
  if (registry) args.push('--registry', registry);
  console.log(`$ ${npm} ${args.join(' ')}`);
  const run = spawnSync(npm, args, {encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe']});
  process.stdout.write(run.stdout ?? '');
  process.stderr.write(run.stderr ?? '');
  if (run.error) throw run.error;
  return {ok: run.status === 0, text: `${run.stdout}\n${run.stderr}`};
}

// npm's own refusal ("You cannot publish over the previously published versions: X."), from the registry or from the
// CLI's own check, and the conflict codes registries answer with.
const ALREADY_EXISTS = /cannot publish over the previously published version|EPUBLISHCONFLICT|E409|this package is already present/i;

async function main() {
  const {dir, version, mode} = options;
  if (!dir || !version || !['publish', 'dry-run'].includes(mode)) {
    throw new ReleaseError('usage: npm-publish.mjs --dir <dir> --version <version> --mode publish|dry-run [--digest f=sha]...');
  }
  const registry = options.registry ? normaliseRegistry(options.registry) : undefined;
  const provenance = !options['no-provenance'];
  if (!provenance && !isLoopbackRegistry(registry)) {
    throw new ReleaseError('--no-provenance is only for a loopback test registry: every real version carries provenance (I12)');
  }
  if (mode === 'publish' && process.env.GITHUB_ACTIONS !== 'true' && !isLoopbackRegistry(registry)) {
    throw new ReleaseError('a real publication runs only in GitHub Actions, through trusted publishing (ADR-077 D8a)');
  }
  const npm = options.npm ?? 'npm';
  const timeout = Number(options['visibility-timeout'] ?? 300) * 1000;
  const identity = releaseIdentity(version);

  let tag;
  if (mode === 'publish') {
    tag = distTag(version);
  } else if (isReleaseCandidate(version)) {
    tag = distTag(finalVersionOf(version));
    console.log(`${version} is a release candidate: never published (D8). Dry run with its final release's tag, ${tag}.`);
  } else {
    tag = distTag(version);
  }
  const tarballs = localTarballs(dir, version, parseDigests(options.digest ?? []));
  for (const tarball of tarballs) {
    console.log(`${tarball.name}@${version}: sha256 ${tarball.sha256}, ${tarball.integrity}, ${tarball.bytes.length} bytes`);
  }
  console.log(`dist-tag ${tag}; registry ${registry ?? '(npm default)'}; mode ${mode}${provenance ? '' : '; no provenance (local test)'}`);
  summary(`### npm ${mode === 'publish' ? 'publication' : 'dry run'} of ${version} (dist-tag \`${tag}\`)\n`);

  /** Waits until the registry shows the version, then requires it to be this tarball with this workflow's provenance.
   *  The registry can lag behind a publication, so a version not shown yet, or shown without its attestation yet, is
   *  asked for again until the timeout. Only a version this run published itself without provenance (a local test,
   *  --no-provenance) is accepted without one: one found by an "already exists" refusal never is (I11). */
  const settle = async (tarball, acceptMissingProvenance) => {
    const deadline = Date.now() + timeout;
    const interval = Math.min(5000, Math.max(100, timeout / 20));
    for (;;) {
      const result = await registryState(registry, tarball, version, identity);
      if (result.state === 'match') return result;
      if (result.state === 'differs') {
        const onlyMissingProvenance = result.missingProvenance && result.problems.length === 1;
        if (onlyMissingProvenance && acceptMissingProvenance) return {state: 'match-without-provenance'};
        if (!onlyMissingProvenance) return result;
      }
      if (Date.now() >= deadline) return result;
      await sleep(interval);
    }
  };

  // The tag chosen for each package this run published itself, for npm-verify's postcondition (M4-C12).
  const selected = {};
  for (const tarball of tarballs) {
    const label = `${tarball.name}@${version}`;
    const before = await registryState(registry, tarball, version, identity);
    if (before.state === 'match') {
      console.log(`skip ${label}: already on the registry, the same tarball, with provenance from ${identity.workflow} at ${identity.ref}`);
      summary(`- ${label}: already published, identical, with this workflow's provenance (skipped)`);
      continue;
    }
    if (before.state === 'differs') {
      throw new ReleaseError(`${label} is already on the registry and is not this release's tarball with this workflow's `
        + `provenance; nothing more is published (I11):\n  - ${before.problems.join('\n  - ')}`);
    }
    // No dist-tag ever moves backwards: this package's tag, chosen from the registry as it is now (D8).
    const chosen = await packageTag(registry, tarball.name, version, tag);
    if (chosen.reason) console.log(`${chosen.reason}: publishing as ${chosen.tag}, leaving ${tag} where it is`);
    const run = npmPublish(npm, tarball, chosen.tag, {dryRun: mode === 'dry-run', provenance, registry});
    if (mode === 'dry-run') {
      if (!run.ok) throw new ReleaseError(`npm publish --dry-run failed for ${label}`);
      summary(`- ${label}: dry run passed with tag \`${chosen.tag}\` (not published)`);
      continue;
    }
    if (!run.ok && !ALREADY_EXISTS.test(run.text)) throw new ReleaseError(`npm publish failed for ${label}; nothing more is published`);
    if (!run.ok) console.log(`${label}: the registry already has this version although it did not show it; checking it`);
    const after = await settle(tarball, run.ok && !provenance);
    if (after.state === 'absent') {
      throw new ReleaseError(`${label}: npm publish ${run.ok ? 'succeeded' : 'reported that it exists'}, but the registry `
        + `did not show it within ${timeout / 1000} s. Re-run the failed job: it skips the version once it is visible.`);
    }
    if (after.state === 'differs') {
      throw new ReleaseError(`${label} on the registry is not this release's tarball with this workflow's provenance:\n  - `
        + after.problems.join('\n  - '));
    }
    if (run.ok) selected[tarball.name] = chosen.tag;
    const how = run.ok ? 'published' : 'already published (a registry delay), identical';
    console.log(`${how} ${label} as ${chosen.tag}`);
    summary(`- ${label}: ${how} as \`${chosen.tag}\`${provenance ? ', with provenance' : ''}`);
  }
  output('tags', JSON.stringify(selected));
  output('published', String(mode === 'publish'));
}

main().catch(error => {
  output('published', 'false');
  console.error(`::error::${error instanceof ReleaseError ? error.message : error.stack}`);
  process.exitCode = 1;
});
