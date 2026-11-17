# npm publication of the WebAssembly packages: maintainer runbook (ADR-077 M4)

The release workflow `.github/workflows/native-image-release.yml` publishes `@julclang/wasm-client`,
`@julclang/julc-vm-wasm` and `@julclang/julc-wasm` (ADR-077 D8, D8a). This page is what the maintainer does by hand:
the one-time setup before the first real publication, each release, recovery, and a bad release.

npm facts below were checked against <https://docs.npmjs.com/trusted-publishers> on 2026-11-17:
- trusted publishing needs npm CLI 11.5.1 or later and Node 22.14.0 or later (the job pins Node 24.22.3 and npm
  11.21.1);
- a GitHub Actions trusted publisher names the owner, the repository, the workflow **file name** (with `.yml`) and,
  optionally, an environment;
- `npm stage publish` is always allowed; direct `npm publish` must be chosen ("Allowed actions");
- a newly created configuration expires unless its first publication succeeds within **2 days**;
- managing dist-tags through trusted publishing is a separate permission ("Allow npm dist-tag"), off by default;
- "Require two-factor authentication and disallow tokens" refuses token publishing;
- only GitHub-hosted runners are supported, and npm generates provenance automatically for a public repository.

## How the jobs fit together

| Job | Runs on | Needs | Permissions | Environment | Condition |
|---|---|---|---|---|---|
| `uplc-conformance`, `validate`, `native-image`, `wasm`, `wasm-package-checks`, `native-image-playground`, `shadow-jars` | tag push | as before | `contents: read` (`wasm` also `attestations: read`) | none | `github.event_name == 'push'` |
| `attest` | tag push | `validate`, `wasm` | `id-token: write`, `attestations: write` | none | push and `github_release == true` |
| `github-release` | tag push | `validate`, `native-image`, `wasm`, `wasm-package-checks`, `native-image-playground`, `shadow-jars`, `attest` | `contents: write` | none | push and `github_release == true` |
| `npm-gates` | push and dispatch | none | `contents: read` | none | always; fails unless the ref is `refs/tags/v<version>` matching `gradle.properties` at the tag |
| `npm-publish` | push and dispatch | `npm-gates`, `wasm`, `attest`, `github-release` | `contents: read`, `id-token: write`, `attestations: read` | `release-staging` | `!cancelled() && npm-gates succeeded && mode != none && (dispatch \|\| (github-release and attest succeeded))`; per-tag concurrency, never cancelled |
| `npm-verify` | push and dispatch | `npm-gates`, `wasm`, `npm-publish` | `contents: read`, `attestations: read` | none | `!cancelled() && npm-publish succeeded && published == 'true'` |
| `update-homebrew` | tag push | `validate`, `github-release` | none (a secret of `release-staging`) | `release-staging` | push and `github_release == true` |

`npm-gates` reads `gradle.properties` at the tag with `git show`, without Gradle:

| `github_release` | `npm_publish` | Version | `npm-publish` |
|---|---|---|---|
| not `true` | any | any | skipped (no release to publish from) |
| `true` | `true` | not a release candidate | **publishes** |
| `true` | `true` | `-rc` | dry run (`npm publish --dry-run`), `published=false`, no `npm-verify` |
| `true` | not `true` | any | dry run, `published=false`, no `npm-verify` |

Dist-tags (D8): `-preN` and final versions go to `latest`, `-beta` to `beta`. A release candidate, a snapshot, and a
1.x prerelease other than `-beta` are refused (D8's table is to be revisited at 1.0).

## One-time setup, before the first real publication

Do these in order. Steps 1 to 3 can be done any time; steps 4 and 5 only when the first real release is ready,
because of the 2-day expiry.

1. **Rehearse first.** Tag a release candidate with `github_release = true` (and `npm_publish` either way). Check in
   the run that:
   - `wasm` wrote `julc-npm-<version>-SHA256SUMS.txt` and its five digest outputs;
   - `attest` made the attestation (the run's "Attestations" summary), and
     `gh attestation verify julclang-wasm-client-<version>.tgz --repo bloxbean/julc --signer-workflow
     bloxbean/julc/.github/workflows/native-image-release.yml --source-ref refs/tags/v<version>` passes on a
     downloaded release asset;
   - `github-release` attached the three tarballs and the checksum file;
   - `npm-publish` ran in dry-run mode, listing the files of each package with tag `latest`, and `npm-verify` was
     skipped.
2. **Publish a placeholder of each name, by hand, with 2FA** (a package must exist before a trusted publisher can be
   configured on its settings page). As JOUST did:
   ```
   mkdir placeholder && cd placeholder
   # for each of @julclang/wasm-client, @julclang/julc-vm-wasm, @julclang/julc-wasm:
   npm init -y --scope=@julclang   # then set "name" to the package name, "version" to "0.0.0-oidc-bootstrap.0",
                                   # "description" to "Placeholder for trusted publishing; do not use."
   npm publish --access public --tag bootstrap
   ```
   npm makes a package's first version `latest` whatever `--tag` says, so the placeholder is briefly `latest` until
   the first real version replaces it.
3. **Refuse tokens.** On each package's Settings page, choose "Require two-factor authentication and disallow
   tokens", right after publishing its placeholder if npm allows it before a trusted publisher exists, otherwise in
   step 4.
4. **Configure the trusted publishers, immediately before the first real release** (it expires after 2 days unused).
   On each package's Settings page, "Trusted publishing", GitHub Actions:
   - Organization or user: `bloxbean`
   - Repository: `julc`
   - Workflow filename: `native-image-release.yml`
   - Environment name: `release-staging`
   - Allowed actions: **allow direct `npm publish`** (staged publishing alone does not let the job publish)
   - Leave "Allow npm dist-tag" off.
5. **Set `npm_publish = true`** (with `github_release = true`) in `gradle.properties` on the release commit, and tag
   the release within the 2 days. If the configuration expires first, delete and create it again.
6. **After the first real publication**, check `npm-verify` passed (registry contents, `npm audit signatures`, and
   provenance naming `bloxbean/julc`, `.github/workflows/native-image-release.yml` and `refs/tags/v<version>`), check
   on npmjs.com that each package shows its provenance and that `latest` is the new version, and then deprecate each
   placeholder:
   ```
   npm deprecate @julclang/wasm-client@0.0.0-oidc-bootstrap.0 "Placeholder for trusted publishing; do not use."
   ```
   (and the same for the two engine packages), so `latest` can never fall back to it.

The publish command runs in `julc-wasm/src/ci/npm-publish.mjs`, called from `native-image-release.yml`. npm validates
the workflow named in the job's OIDC token, which is always the calling workflow, so no other workflow
(`native-image-dev.yml`, `bloxbean-snapshot-dist.yml`) can publish these packages even if it ran the script.

## Each release

1. Decide `github_release` and `npm_publish` in `gradle.properties` on the release commit. `npm_publish = true` needs
   `github_release = true`; npm publishes only from the GitHub release's attested assets.
2. Tag as usual (`tag-release.yml`). The tag push runs the whole workflow.
3. A published version can never be replaced, unpublished for reuse, or published again with other bytes. Check the
   release notes (D7: every API change is listed) before setting `npm_publish = true`.

## Recovery

A release is built once. Web Image output is not byte-identical across builds, so the workflow never rebuilds a
version whose release holds npm tarballs: `wasm` stops first, with a message pointing here. Never delete release
assets or recreate a release to "start again"; npm is the record once a version is published.

- **Within the original run** (any job failed, before any full re-run): use **"Re-run failed jobs"**. It keeps the run's
  artifacts and the `wasm` job's digest outputs. `github-release` keeps assets already attached with the same bytes,
  deletes a failed upload's `starter` placeholder and uploads it again, and stops if a completed asset differs.
  `npm-publish` skips each version already on npm with the same tarball and this workflow's provenance, and publishes
  the rest.
- **After a refused full re-run, or once the original run can no longer be re-run**: dispatch the workflow **on the
  tag**:
  ```
  gh workflow run native-image-release.yml --repo bloxbean/julc --ref v<version>
  ```
  It skips every build job, `attest`, `github-release` and `update-homebrew`, verifies the release's tarballs against
  their attestation, and runs `npm-publish` and `npm-verify` on them, with the gates in `gradle.properties` at the
  tag. A dispatch from a branch, or on a tag that does not match `gradle.properties`, is refused.
- **`npm-publish` fails with "already on the registry and is not this release's tarball with this workflow's
  provenance"**: someone published that version another way (by hand, or another build). Do not retry. Investigate;
  the version number is spent. Deprecate it if it is wrong (below) and release the next version.
- **`npm-publish` fails with "did not show it within 300 s"**: the registry was slow. Re-run the failed job.
- **`npm-verify` fails**: treat the release as bad until the cause is known.

## A bad release

By hand, with 2FA (the trusted publisher has no dist-tag permission):
```
npm deprecate @julclang/julc-wasm@<bad> "<reason>; use <good>"   # each affected package
npm dist-tag add @julclang/julc-wasm@<previous good> latest      # until a fixed version is published
```
Do not unpublish: it breaks dependents, and npm allows it only within 72 hours and without dependents. A wrong result
from `vm.applyParams` also gets a GitHub security advisory (D8a).
