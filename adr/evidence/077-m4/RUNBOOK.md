# npm publication of the WebAssembly packages: maintainer runbook (ADR-077 M4)

The release workflow `.github/workflows/native-image-release.yml` publishes `@julclang/wasm-client`,
`@julclang/julc-vm-wasm` and `@julclang/julc-wasm` (ADR-077 D8, D8a). This page is what the maintainer does by hand:
the one-time setup before the first real publication, the rehearsal, each release, recovery, and a bad release.

npm facts below were checked against <https://docs.npmjs.com/trusted-publishers> on 2026-11-17:
- trusted publishing needs npm CLI 11.5.1 or later and Node 22.14.0 or later (the jobs pin Node 24.22.3 and the npm
  11.21.1 tarball by SHA-256);
- a GitHub Actions trusted publisher names the owner, the repository, the workflow **file name** (with `.yml`) and,
  optionally, an environment. It does **not** name a branch or tag: the environment's deployment policy is what keeps
  publication to release tags;
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
| `npm-dry-run` | push and dispatch | `npm-gates`, `wasm`, `attest`, `github-release` | `contents: read`, `attestations: read` | none | `!cancelled() && npm-gates succeeded && mode == 'dry-run' && (dispatch \|\| (github-release and attest succeeded))` |
| `npm-publish` | push and dispatch | `npm-gates`, `wasm`, `attest`, `github-release` | `contents: read`, `id-token: write`, `attestations: read` | **`npm-publish`** | the same with `mode == 'publish'`; per-tag concurrency, never cancelled |
| `npm-verify` | push and dispatch | `npm-gates`, `wasm`, `npm-publish` | `contents: read`, `attestations: read` | none | `!cancelled() && npm-publish succeeded && published == 'true'` |
| `update-homebrew` | tag push | `validate`, `github-release` | none (a secret of `release-staging`) | `release-staging` | push and `github_release == true` |

`npm-gates` reads `gradle.properties` at the tag with `git show`, without Gradle:

| `github_release` | `npm_publish` | Version | Then |
|---|---|---|---|
| not `true` | any | any | neither npm job runs (no release to publish from) |
| `true` | `true` | not a release candidate | `npm-publish` publishes, then `npm-verify` |
| `true` | `true` | `-rc` | `npm-dry-run` (`npm publish --dry-run`); no `npm-verify` |
| `true` | not `true` | any | `npm-dry-run`; no `npm-verify` |

**The gates are fixed at the tag.** A version tagged with `npm_publish = false` (or `github_release = false`) can never
be published to npm later: a dispatch reads the same `gradle.properties`, and a tag is never moved. Publishing it
needs a new version.

Dist-tags (D8): `-preN` and final versions go to `latest`, `-beta` to `beta`. A release candidate, a snapshot, and a
1.x prerelease other than `-beta` are refused (D8's table is to be revisited at 1.0). **`latest` never moves
backwards**: if the D8 tag of any of the three packages already names a higher version (a recovery of version N after
N+1 was published, or a maintenance release), all three are published with `release-<major>.<minor>` (for example
`release-0.1`) instead, and the D8 tag stays where it is.

Every job that reads the release's npm files runs `julc-wasm/src/ci/release-assets.sh`: it downloads the three
tarballs, both engine archives and the checksum file from the **published** release (a draft is invisible to these
read-only tokens), and verifies each of the five files with `gh attestation verify --repo bloxbean/julc
--signer-workflow bloxbean/julc/.github/workflows/native-image-release.yml --source-ref refs/tags/v<version>
--source-digest <tag commit> --signer-digest <workflow commit> --deny-self-hosted-runners`. The scripts then check the
checksum file against all five files and, in the tag's own run, against the `wasm` job's digests.

## One-time setup, before the first real publication

Steps 1 to 4 can be done any time; steps 5 and 6 only when the first real release is ready, because of the 2-day
expiry.

1. **Create the `npm-publish` environment** (Settings, Environments): deployment branches and tags "Selected", with the
   tag rule `v*` only (no branches). Optionally a required reviewer from release-owners. No secrets. Because npm's
   trusted publisher does not check the ref, this policy, the job's own ref check, and who may push `v*` tags and run
   workflows (repository write access, the tag rulesets) together decide who can publish.
2. **Rehearse** (see "Rehearsal" below): a release candidate with `github_release = true`.
3. **Publish a placeholder of each name, by hand, with 2FA** (a package must exist before a trusted publisher can be
   configured on its settings page). As JOUST did, for each of the three names:
   ```
   mkdir placeholder && cd placeholder
   cat > package.json <<'EOF'
   {"name": "@julclang/wasm-client", "version": "0.0.0-oidc-bootstrap.0",
    "description": "Placeholder for trusted publishing; do not use.", "license": "MIT"}
   EOF
   npm publish --access public --tag bootstrap
   ```
   npm makes a package's first version `latest` whatever `--tag` says, so the placeholder is briefly `latest` until
   the first real version replaces it.
4. **Refuse tokens.** On each package's Settings page, choose "Require two-factor authentication and disallow
   tokens", right after publishing its placeholder if npm allows it before a trusted publisher exists, otherwise in
   step 5.
5. **Configure the trusted publishers, immediately before the first real release** (it expires after 2 days unused).
   On each package's Settings page, "Trusted publishing", GitHub Actions:
   - Organization or user: `bloxbean`
   - Repository: `julc`
   - Workflow filename: `native-image-release.yml`
   - Environment name: `npm-publish`
   - Allowed actions: **allow direct `npm publish`** (staged publishing alone does not let the job publish)
   - Leave "Allow npm dist-tag" off.
6. **Set `npm_publish = true`** (with `github_release = true`) in `gradle.properties` on the release commit, and tag
   the release within the 2 days. If the configuration expires first, delete and create it again.
7. **After the first real publication**, check that `npm-verify` passed (registry contents, `npm audit signatures`,
   and provenance naming `bloxbean/julc`, `.github/workflows/native-image-release.yml` and `refs/tags/v<version>`),
   check on npmjs.com that each package shows its provenance and that `latest` is the new version, and deprecate each
   placeholder:
   ```
   npm deprecate @julclang/wasm-client@0.0.0-oidc-bootstrap.0 "Placeholder for trusted publishing; do not use."
   ```
   (and the same for the two engine packages), so `latest` can never fall back to it.

The publish command runs in `julc-wasm/src/ci/npm-publish.mjs` (release-owner reviewed through CODEOWNERS), checked
out alone at the tag's commit. npm validates the workflow named in the job's OIDC token, which is always the calling
workflow, so no other workflow (`native-image-dev.yml`, `bloxbean-snapshot-dist.yml`) can publish these packages even
if it ran the script.

## Rehearsal (before the first real publication)

**Open until the rehearsal:** the graph-level checks of ADR-077 M4 cannot run locally: the two recovery sequences, a
normal run whose `attest` or `github-release` fails publishing nothing, the dispatch negatives on GitHub, and the job
conditions on GitHub's real dependency graph. The rehearsal closes what it can; the rest closes with the first real
publication. **A rehearsal proves neither npm authentication nor provenance**: only the first real publication does.

On a release candidate (`-rcN`), with `github_release = true` and `npm_publish` either way (a candidate is always a dry
run):
1. The tag's run: `wasm` writes `julc-npm-<version>-SHA256SUMS.txt` and its five digest outputs; `attest` makes the
   attestation; `github-release` attaches the three tarballs and the checksum file and its final check passes;
   `npm-dry-run` verifies all five files' attestation and lists each package's files with tag `latest`; `npm-publish`
   and `npm-verify` are skipped.
2. By hand, on a downloaded asset: `gh attestation verify julclang-wasm-client-<version>.tgz --repo bloxbean/julc
   --signer-workflow bloxbean/julc/.github/workflows/native-image-release.yml --source-ref refs/tags/v<version>
   --source-digest <tag commit>` passes, and with another `--source-digest` fails.
3. **A dispatch from a branch must fail the gate:** `gh workflow run native-image-release.yml --ref main`; `npm-gates`
   fails and nothing else runs.
4. **A dispatch on the candidate's tag** runs only `npm-gates` and `npm-dry-run`, which verifies the existing assets.
5. **A whole-workflow re-run** of the candidate's run stops in `wasm` ("already holds npm files"), building nothing.
6. **A deliberately failed `attest`, in dry-run mode**: on a second candidate, make `attest` fail (for example, a
   temporary commit on a rehearsal branch that adds `run: exit 1` before its attest step, tagged as a new candidate).
   `github-release` and `npm-dry-run` must be skipped, and no release created.

## Each release

1. Decide `github_release` and `npm_publish` in `gradle.properties` on the release commit. `npm_publish = true` needs
   `github_release = true`; npm publishes only from the GitHub release's attested assets. The gates are fixed at the
   tag (above).
2. Tag as usual (`tag-release.yml`). The tag push runs the whole workflow. If the `npm-publish` environment has a
   required reviewer, approve the deployment.
3. A published version can never be replaced, unpublished for reuse, or published again with other bytes. Check the
   release notes (D7: every API change is listed) before setting `npm_publish = true`.

## Recovery

A release is built once. Web Image output is not byte-identical across builds, so the workflow never rebuilds a
version whose release holds npm tarballs: `wasm` stops first, with a message pointing here. Never delete release
assets or recreate a release to "start again"; npm is the record once a version is published.

- **Within the original run** (any job failed, before any full re-run): use **"Re-run failed jobs"**. It keeps the run's
  artifacts and the `wasm` job's digest outputs. `github-release` finds the release (a draft too), keeps assets
  already attached with the same bytes, deletes a failed upload's `starter` placeholder and uploads it again, stops if
  a completed asset differs, and finally checks the release is published with every file's bytes. `npm-publish` skips
  each version already on npm with the same tarball and this workflow's provenance, and publishes the rest.
- **The first real publication fails at authentication** (npm answers 401, 403 or 404 on `PUT`, or "OIDC token
  exchange" fails in the log): nothing was published. Fix the trusted publisher (repository, workflow file name,
  environment `npm-publish`, direct publishing allowed, not expired) and use "Re-run failed jobs".
- **After a refused full re-run, or once the original run can no longer be re-run**: dispatch the workflow **on the
  tag**:
  ```
  gh workflow run native-image-release.yml --repo bloxbean/julc --ref v<version>
  ```
  Anyone with write access to the repository can dispatch; the `npm-publish` environment (tags `v*` only, and its
  reviewer if set) and `npm-gates` (the ref must be exactly the release tag, matching `gradle.properties` at it) are
  the controls. The dispatch skips every build job, `attest`, `github-release` and `update-homebrew`, verifies the
  release's files against their attestation, and runs `npm-publish` (or `npm-dry-run`) and `npm-verify` on them. A
  dispatch from a branch, or on a tag that does not match `gradle.properties`, is refused.
- **Concurrency:** one `npm-publish` per tag runs at a time and is never cancelled by another. GitHub keeps one waiting
  job per group, so if a third run arrives while one runs and one waits, the newer waiting job replaces the older
  waiting one; no two ever overlap.
- **Recovering an older version after a newer one is out** publishes it as `release-<major>.<minor>` and leaves
  `latest` on the newer version (above); `npm-verify` notes it.
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
