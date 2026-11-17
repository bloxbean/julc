// ADR-077 D8a: the npm-verify job of native-image-release.yml. Holds no credentials. Checks what the registry serves for
// a published version against the release's tarballs (which the workflow verified against their attestation first):
// - the registry's integrity, and the tarball `npm pack name@version` downloads, equal the release's (I1, I11);
// - the dist-tag D8 gives the version names it, or a later version (a recovery run for an older release);
// - the three exact versions install with --ignore-scripts (I7) and lock to the release's integrity;
// - `npm audit signatures` verifies them, and each carries a provenance attestation, among the bundles it verified,
//   whose statement names the tarball's SHA-512 and whose signing certificate names this repository, this workflow
//   file and the release tag (I12). `npm audit signatures` alone would pass a version without an attestation.
//
//   node npm-verify.mjs --dir <release assets> --version <version> --work <empty directory>
//        [--digest <file>=<sha256>]... [--registry <url>] [--npm <npm command>]
//        [--no-provenance]   only against a loopback registry, for the local tests: no audit, no provenance
import {spawnSync} from 'node:child_process';
import {appendFileSync, mkdirSync, readFileSync, writeFileSync} from 'node:fs';
import path from 'node:path';
import {parseArgs} from 'node:util';
import {
  ReleaseError, certificateProblems, compareVersions, distTag, isLoopbackRegistry, localTarballs,
  normaliseRegistry, packument, parseDigests, releaseIdentity, statementOf, statementProblems, verifiedProvenance,
} from './npm-release.mjs';

const {values: options} = parseArgs({options: {
  dir: {type: 'string'}, version: {type: 'string'}, work: {type: 'string'}, digest: {type: 'string', multiple: true},
  registry: {type: 'string'}, npm: {type: 'string'}, 'no-provenance': {type: 'boolean'},
}});
const summary = line => { if (process.env.GITHUB_STEP_SUMMARY) appendFileSync(process.env.GITHUB_STEP_SUMMARY, line + '\n'); };

/** Runs npm. `attempts` > 1 retries a failure 30 s apart: the registry's install metadata can lag behind a
 *  publication for a while after the full document shows it. */
async function npm(args, cwd, attempts = 1) {
  const command = options.npm ?? 'npm';
  const full = options.registry ? [...args, '--registry', normaliseRegistry(options.registry)] : args;
  for (let attempt = 1; ; attempt++) {
    console.log(`$ (${cwd}) ${command} ${full.join(' ')}`);
    const run = spawnSync(command, full, {cwd, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], maxBuffer: 1 << 28});
    if (run.error) throw run.error;
    process.stderr.write(run.stderr ?? '');
    if (run.status === 0 || attempt >= attempts) return run;
    console.log(`attempt ${attempt} of ${attempts} failed; trying again in 30 s`);
    await new Promise(resolve => setTimeout(resolve, 30_000));
  }
}

async function main() {
  const {dir, version, work} = options;
  if (!dir || !version || !work) throw new ReleaseError('usage: npm-verify.mjs --dir <dir> --version <version> --work <dir>');
  const provenance = !options['no-provenance'];
  if (!provenance && !isLoopbackRegistry(options.registry)) {
    throw new ReleaseError('--no-provenance is only for a loopback test registry: every real version carries provenance (I12)');
  }
  const tag = distTag(version);
  const identity = releaseIdentity(version);
  const tarballs = localTarballs(dir, version, parseDigests(options.digest ?? []));
  const problems = [];
  summary(`### npm registry check of ${version}\n`);

  // What the registry says, and what it serves.
  const packed = path.join(work, 'registry');
  mkdirSync(packed, {recursive: true});
  for (const tarball of tarballs) {
    const label = `${tarball.name}@${version}`;
    const document = await packument(options.registry, tarball.name);
    const meta = document?.versions?.[version];
    if (!meta) { problems.push(`${label} is not on the registry`); continue; }
    if (meta.dist?.integrity !== tarball.integrity) {
      problems.push(`${label}: registry integrity ${meta.dist?.integrity}, the release's tarball is ${tarball.integrity}`);
    }
    const pack = await npm(['pack', `${tarball.name}@${version}`, '--pack-destination', packed, '--json', '--ignore-scripts'],
      work, 4);
    if (pack.status !== 0) { problems.push(`${label}: npm pack failed`); continue; }
    const [{filename}] = JSON.parse(pack.stdout);
    if (!readFileSync(path.join(packed, path.basename(filename))).equals(tarball.bytes)) {
      problems.push(`${label}: the tarball the registry serves differs from the release's ${path.basename(tarball.file)}`);
    }
    const tagged = document['dist-tags']?.[tag];
    if (tagged === undefined || compareVersions(tagged, version) < 0) {
      problems.push(`${label}: dist-tag ${tag} names ${tagged ?? 'nothing'}, expected ${version} (D8)`);
    } else if (tagged !== version) {
      console.log(`note ${label}: dist-tag ${tag} names the later ${tagged}`);
    }
    const tags = JSON.stringify(document['dist-tags']);
    console.log(`ok ${label}: registry tarball equals the release's (${tarball.integrity}); dist-tags ${tags}`);
    summary(`- ${label}: registry tarball equals the release asset; dist-tags \`${tags}\``);
  }

  // Installed as a user installs them, then audited.
  const fixture = path.join(work, 'fixture');
  mkdirSync(fixture, {recursive: true});
  writeFileSync(path.join(fixture, 'package.json'), JSON.stringify({name: 'julc-npm-verify', private: true}) + '\n');
  const install = await npm(['install', '--ignore-scripts', '--no-audit', '--no-fund', '--save-exact',
    ...tarballs.map(tarball => `${tarball.name}@${version}`)], fixture, 4);
  if (install.status !== 0) {
    problems.push('installing the three versions failed');
  } else {
    const lock = JSON.parse(readFileSync(path.join(fixture, 'package-lock.json'), 'utf8'));
    for (const tarball of tarballs) {
      const entry = lock.packages?.[`node_modules/${tarball.name}`];
      if (entry?.version !== version || entry?.integrity !== tarball.integrity) {
        problems.push(`${tarball.name}: installed ${entry?.version} with ${entry?.integrity}, expected ${version} with ${tarball.integrity}`);
      }
    }
    if (provenance) {
      const run = await npm(['audit', 'signatures', '--json', '--include-attestations'], fixture);
      let audit;
      try { audit = JSON.parse(run.stdout); } catch { audit = null; }
      if (!audit || audit.error) {
        problems.push(`npm audit signatures failed: ${audit?.error?.summary ?? run.stdout.slice(0, 500)}`);
      } else {
        for (const kind of ['invalid', 'missing']) {
          if (audit[kind]?.length) problems.push(`npm audit signatures: ${kind} ${JSON.stringify(audit[kind])}`);
        }
        for (const tarball of tarballs) {
          const label = `${tarball.name}@${version}`;
          const bundle = verifiedProvenance(audit, tarball.name, version);
          if (!bundle) { problems.push(`${label}: no verified provenance attestation (I12)`); continue; }
          const wrong = [...statementProblems(statementOf(bundle), {name: tarball.name, version, sha512: tarball.sha512, identity}),
            ...certificateProblems(bundle, identity)];
          if (wrong.length) { problems.push(`${label}: provenance:\n    - ${wrong.join('\n    - ')}`); continue; }
          console.log(`ok ${label}: verified provenance from ${identity.repository}, ${identity.workflow}, ${identity.ref}`);
          summary(`- ${label}: verified provenance (${identity.workflow} at \`${identity.ref}\`)`);
        }
      }
    }
  }
  if (problems.length) throw new ReleaseError(`npm-verify found ${problems.length} problem(s):\n  - ${problems.join('\n  - ')}`);
  console.log(`ok: ${tarballs.length} packages at ${version} on ${normaliseRegistry(options.registry)}`
    + (provenance ? ', audited, with this workflow\'s provenance' : ' (no audit or provenance: local test)'));
}

main().catch(error => {
  console.error(`::error::${error instanceof ReleaseError ? error.message : error.stack}`);
  process.exitCode = 1;
});
