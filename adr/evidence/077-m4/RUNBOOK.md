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
| `uplc-conformance`, `validate`, `native-image`, `wasm`, `wasm-package-checks`, `native-image-playground`, `shadow-jars` | tag push | as before | `contents: read` | none | `github.event_name == 'push'`; `wasm` first stops if the tag already has a GitHub release |
| `attest` | tag push | `validate`, `wasm` | `id-token: write`, `attestations: write` | none | push and `github_release == true` |
| `github-release` | tag push | `validate`, `native-image`, `wasm`, `wasm-package-checks`, `native-image-playground`, `shadow-jars`, `attest` | `contents: write` | none | push and `github_release == true`; creates the release published (never a draft), attaches each file once |
| `npm-gates` | push and dispatch | none | `contents: read`, `actions: read` | none | always; fails unless the ref is `refs/tags/v<version>` matching `gradle.properties` at the tag, and, to publish, unless the `npm-publish` environment has exactly the policy tag `v*` |
| `npm-dry-run` | push and dispatch | `npm-gates`, `wasm`, `attest`, `github-release` | `contents: read`, `attestations: read` | none | `!cancelled() && npm-gates succeeded && mode == 'dry-run' && (dispatch \|\| (github-release and attest succeeded))` |
| `npm-publish` | push and dispatch | `npm-gates`, `wasm`, `attest`, `github-release` | `contents: read`, `id-token: write`, `attestations: read` | **`npm-publish`** | the same with `mode == 'publish'`; one concurrency group for every version (`npm-publish-julclang`), a running job never cancelled |
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
1.x prerelease other than `-beta` are refused (D8's table is to be revisited at 1.0). **No dist-tag ever moves
backwards.** Each package's tag is chosen when it is published, from the registry as it is then: the first of D8's
tag, `release-<major>.<minor>` (for example `release-0.1`) and `release-<version>` (for example `release-0.1.8`) that
does not already name a higher version of that package. So a recovery of version N after N+1, a maintenance release
(0.1.8 after 0.2.0 and 0.1.9), or a package of N+1 published before the others, never moves a tag down; the three
packages of one version can end on different tags. Every npm publication of every version runs in one concurrency
group, so two releases never choose their tags at the same time.

**Version ordering (open question 8):** semver compares `preN` as text, so `0.1.0-pre100` sorts below `0.1.0-pre99`.
From `pre100` on, a new release would be "older" than `pre99`: it would go to `release-0.1`, and `latest` would stay
on `pre99`. The version scheme must change before `pre99` (ADR-077 open question 8).

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
   workflows (repository write access, the tag rulesets) together decide who can publish. **It does not exist yet**
   (2026-11-17), and GitHub creates a missing environment, with no protection, for the first job that names it; so
   `npm-gates` refuses to dry-run or publish unless the environment exists with exactly the policy tag `v*` (it reads
   it with the job token's `actions: read`). Create it **before the rehearsal**: the rehearsal's dry run runs the same
   check and fails without it. Read the policy back, and do not set `npm_publish = true` until this prints exactly
   `tag v*` and `true false`:
   ```
   gh api repos/bloxbean/julc/environments/npm-publish/deployment-branch-policies --jq '.branch_policies[] | "\(.type) \(.name)"'
   gh api repos/bloxbean/julc/environments/npm-publish --jq '.deployment_branch_policy | "\(.custom_branch_policies) \(.protected_branches)"'
   ```
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
5. **A whole-workflow re-run** of the candidate's run stops in `wasm` ("already has a GitHub release"), building
   nothing.
6. **The `npm-publish` environment's policy** reads back as above (step 1 of the setup), and the candidate's
   `npm-gates` log shows "npm-publish environment: tag:v* only": the job token could read the policy, so a real tag's
   check will too. If that step failed for any reason other than the policy itself, do not set `npm_publish = true`:
   a real tag whose check cannot read the policy can never reach npm (the workflow at a tag is fixed).
7. **Checks that need a modified workflow** (a deliberately failed `attest`, a failed upload): never on the real
   repository, where they would leave a permanent `v*` tag and release made by a modified workflow. Run them in a
   fork or a scratch repository, with `npm_publish = false` (dry-run mode) and no trusted publisher: for example
   `run: exit 1` before the attest step must leave `github-release` and `npm-dry-run` skipped and no release created.

## Each release

1. Decide `github_release` and `npm_publish` in `gradle.properties` on the release commit. `npm_publish = true` needs
   `github_release = true`; npm publishes only from the GitHub release's attested assets. The gates are fixed at the
   tag (above).
   **Never create a GitHub release, or a draft, for the tag by hand** before the run: `wasm` stops when the tag already
   has a release ("built once"), and `github-release` refuses a draft.
2. Tag as usual (`tag-release.yml`). The tag push runs the whole workflow. If the `npm-publish` environment has a
   required reviewer, approve the deployment.
3. A published version can never be replaced, unpublished for reuse, or published again with other bytes. Check the
   release notes (D7: every API change is listed) before setting `npm_publish = true`.
4. The release is public from the moment `github-release` creates it, while its assets upload (a few minutes),
   possibly with the Latest badge (a final release): the accepted cost of never using drafts, which read-only checks
   cannot see. Release notes and announcements should wait for the run to finish.

## Recovery

A release is built once. Web Image output is not byte-identical across builds, so the workflow never rebuilds a
version whose release holds npm tarballs: `wasm` stops first, with a message pointing here. Never delete release
assets or recreate a release to "start again"; npm is the record once a version is published.

- **Within the original run** (any job failed, before any full re-run): use **"Re-run failed jobs"**. It keeps the run's
  artifacts and the `wasm` job's digest outputs. `github-release` finds the release, keeps assets already attached
  with the same bytes, deletes a failed upload's `starter` placeholder and uploads it again, stops if a completed
  asset differs, and finally checks the release is published with every file's bytes. `npm-publish` skips each
  version already on npm with the same tarball and this workflow's provenance, and publishes the rest.
- **After a whole-workflow re-run** (refused in `wasm`, since the tag has a release): "Re-run failed jobs" can no longer
  reach the original attempt. Finish the release by hand from the original run's artifacts (kept 7 days; the bytes
  are the attested ones), never replacing a completed asset, then dispatch on the tag for npm:
  ```
  gh run download <original run id> --repo bloxbean/julc --pattern 'release-*' --dir dist
  gh api repos/bloxbean/julc/releases/tags/v<version> --jq '.assets[] | "\(.id) \(.name) \(.state)"'
  gh api -X DELETE repos/bloxbean/julc/releases/assets/<id of each asset in state starter>
  gh release upload v<version> dist/<each missing file> --repo bloxbean/julc      # never --clobber
  ```
  Then check every asset against the original run's bytes, as the workflow's final check does (each line must say
  `ok`, and the release must not be a draft):
  ```
  gh api repos/bloxbean/julc/releases/tags/v<version> --jq '.draft'      # false
  for f in dist/*; do
    n=$(basename "$f")
    d=$(gh api repos/bloxbean/julc/releases/tags/v<version> --jq ".assets[] | select(.name == \"$n\") | \"\(.state) \(.digest)\"")
    [ "$d" = "uploaded sha256:$(sha256sum "$f" | cut -d' ' -f1)" ] && echo "ok $n" || echo "DIFFERENT $n: $d"
  done
  ```
  The npm jobs also verify each npm file against its attestation, so a wrong npm file is refused there.
  `update-homebrew` does not run on this path (nor on a dispatch). Update the formulas by hand: in
  `bloxbean/homebrew-tap`, set `version` and each `sha256` in `Formula/julc.rb` and `Formula/julc-playground.rb` to
  the release's zips (`sha256sum` of the macOS, Linux x86_64 and Linux arm64 assets), as the job's script does.
  The artifacts expire 7 days after the original run: after that, a release missing assets cannot be completed with
  its attested bytes, and the version is spent (release the next one).
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
- **Concurrency:** one `npm-publish` runs at a time across all versions (`npm-publish-julclang`), and a running one is
  never cancelled. GitHub keeps one waiting job per group: if a third release's job arrives while one runs and one
  waits, the newer waiting job **cancels** the older waiting one. That run shows `npm-publish` as cancelled (and
  `npm-verify` skipped); its release is complete and published, so finish it by dispatching the workflow on its tag
  (it waits for the group like any other).
- **Recovering an older version after a newer one is out** publishes it on the first tag that does not move backwards
  (above) and leaves `latest` on the newer version; `npm-verify` notes it, and checks that each tag the run chose
  still names that version or a later one.
- **`npm-publish` fails with "already on the registry and is not this release's tarball with this workflow's
  provenance"**: someone published that version another way (by hand, or another build). Do not retry. Investigate;
  the version number is spent. Deprecate it if it is wrong (below) and release the next version.
- **`npm-publish` fails with "did not show it within 300 s"**: the registry was slow. Re-run the failed job.
- **`npm-verify` fails**: treat the release as bad until the cause is known.

## Dist-tags by hand

The rule that no dist-tag moves backwards holds among the workflow's own publications only. A dist-tag changed by hand
(an incident rollback, `npm dist-tag add`/`rm`, or a deprecation that moves `latest`) can break it, and a publication
running at the same time would choose its tag from a registry that is changing. So change dist-tags by hand only with
publications quiesced:
1. no job pending or running in the `npm-publish-julclang` concurrency group (the repository's Actions page, filtered
   to `native-image-release.yml`), and no new `v*` tag pushed or dispatch started until step 3;
2. make the change;
3. read back every changed package's tags (`npm view @julclang/<name> dist-tags`) and check them before resuming.

The named exception is an intentional emergency rollback (below): it moves `latest` backwards on purpose.

## A bad release

By hand, with 2FA (the trusted publisher has no dist-tag permission), with publications quiesced (above):
```
npm deprecate @julclang/julc-wasm@<bad> "<reason>; use <good>"   # each affected package
npm dist-tag add @julclang/julc-wasm@<previous good> latest      # until a fixed version is published
```
Do not unpublish: it breaks dependents, and npm allows it only within 72 hours and without dependents. A wrong result
from `vm.applyParams` also gets a GitHub security advisory (D8a).
