# npm publication of the WebAssembly packages: maintainer runbook (ADR-077 M4)

The release workflow `.github/workflows/native-image-release.yml` publishes `@julclang/wasm-client`,
`@julclang/julc-vm-wasm` and `@julclang/julc-wasm` (ADR-077 D8, D8a). This page is what the maintainer does by hand:
the one-time setup before the first real publication, the rehearsal, each release, recovery, and a bad release.

npm facts below were checked against <https://docs.npmjs.com/trusted-publishers> on 2026-11-17:
- trusted publishing needs npm CLI 11.5.1 or later and Node 22.14.0 or later (the jobs pin Node 24.22.3 and the npm
  11.21.1 tarball by SHA-256);
- a GitHub Actions trusted publisher names the owner, the repository, the workflow **file name** (with `.yml`) and,
  optionally, an environment. It does **not** name a branch or tag (see "Who can publish" below);
- `npm stage publish` is always allowed; direct `npm publish` must be chosen ("Allowed actions");
- a newly created configuration expires unless its first publication succeeds within **2 days**;
- managing dist-tags through trusted publishing is a separate permission ("Allow npm dist-tag"), off by default;
- "Require two-factor authentication and disallow tokens" refuses token publishing;
- only GitHub-hosted runners are supported, and npm generates provenance automatically for a public repository.

## How the jobs fit together

| Job | Runs on | Needs | Permissions | Environment | Condition |
|---|---|---|---|---|---|
| `uplc-conformance`, `validate`, `native-image`, `wasm`, `wasm-package-checks`, `native-image-playground`, `shadow-jars` | tag push | as before | `contents: read` | none | `github.event_name == 'push'`; `wasm` first stops if the tag already has a GitHub release, and uploads the npm files as the `npm-packages` artifact (14 days) |
| `attest` | tag push | `validate`, `wasm` | `id-token: write`, `attestations: write` | none | push and `github_release == true`; attests the `npm-packages` artifact's five files |
| `github-release` | tag push | `validate`, `native-image`, `wasm`, `wasm-package-checks`, `native-image-playground`, `shadow-jars`, `attest` | `contents: write` | none | push and `github_release == true`; creates the release published (never a draft), attaches each file once; no npm file |
| `npm-gates` | push and dispatch | none | `contents: read` | none | always; fails unless the ref is `refs/tags/v<version>` matching `gradle.properties` at the tag |
| `npm-dry-run` | push and dispatch | `npm-gates`, `wasm`, `attest`, `github-release` | `contents: read`, `attestations: read`, `actions: read` | none | `!cancelled() && npm-gates succeeded && mode == 'dry-run' && (dispatch \|\| (github-release and attest succeeded))` |
| `npm-publish` | push and dispatch | `npm-gates`, `wasm`, `attest`, `github-release` | `contents: read`, `id-token: write`, `attestations: read`, `actions: read` | `release-staging` | the same with `mode == 'publish'`; first step: the ref must be exactly `refs/tags/v<version>`; one concurrency group for every version (`npm-publish-julclang`), a running job never cancelled |
| `npm-verify` | push and dispatch | `npm-gates`, `wasm`, `npm-publish` | `contents: read`, `attestations: read`, `actions: read` | none | `!cancelled() && npm-publish succeeded && published == 'true'` |
| `update-homebrew` | tag push | `validate`, `github-release` | none (a secret of `release-staging`) | `release-staging` | push and `github_release == true` |

`npm-gates` reads `gradle.properties` at the tag with `git show`, without Gradle:

| `github_release` | `npm_publish` | Version | Then |
|---|---|---|---|
| not `true` | any | any | neither npm job runs (no release to publish from) |
| `true` | `true` | `X.Y.Z`, `-preN`, `-betaN`, `-rcN` | `npm-publish` publishes, then `npm-verify` |
| `true` | `true` | `-preN-rcN` (a candidate of a prerelease) | `npm-dry-run` (`npm publish --dry-run`); no `npm-verify` |
| `true` | not `true` | a form above | `npm-dry-run`; no `npm-verify` |
| `true` | any | any other form (`-rc.1`, `-RC1`, `-beta1-rc1`, `-alpha1`, ...) | `npm-gates` fails: D8 has no rule for it |

**The gates are fixed at the tag.** A version tagged with `npm_publish = false` (or `github_release = false`) can never
be published to npm later: a dispatch reads the same `gradle.properties`, and a tag is never moved. Publishing it
needs a new version.

Dist-tags (D8), per package, from the registry when it is published. The tag names are constants in
`julc-wasm/src/ci/npm-release.mjs` (`LATEST_TAG`, `PREVIEW_TAG`, `BETA_TAG`, `CANDIDATE_TAG`):

| Version | Dist-tag | Install |
|---|---|---|
| `X.Y.Z` (final) | `latest` (unless `latest` names a higher version: see below) | `npm install @julclang/julc-wasm` |
| `X.Y.Z-preN`, while the package has no final version on the registry | `latest` | `npm install @julclang/julc-wasm` |
| `X.Y.Z-preN`, once it has one (any major) | `preview` | `npm install @julclang/julc-wasm@preview` |
| `X.Y.Z-betaN` | `beta` | `npm install @julclang/julc-wasm@beta` |
| `X.Y.Z-rcN` (a candidate of a final version; semver orders it below `X.Y.Z`) | `next` | `npm install @julclang/julc-wasm@next` |
| `X.Y.Z-preN-rcN` (a candidate of a prerelease; semver orders it above `X.Y.Z-preN`) | never published: dry run | |
| a snapshot, or any other form | refused | |

`preview`, `beta` and `next` are channels ahead of `latest`: a version takes one only when it is ahead of `latest`.
A channel exists only after its first version, and a final release does not reset it, so afterwards it can name a
version older than `latest` (`npm-verify` notes it). **Within one `X.Y.Z` semver orders `beta < pre < rc`**: a beta
series belongs to a new `X.Y.Z` (`0.1.0-beta1` after `0.1.0-pre21` goes to `release-0.1`, never `beta`), and a first
final backfilled below a `-preN` on `latest` (`0.1.0` after `0.2.0-pre1`) goes to `release-0.1`. Users should pin
prereleases exactly: `^0.1.0-pre21` also matches a later `0.1.0-rcN`.
So until JuLC's first final release, `npm install @julclang/julc-wasm` gets the newest `-preN`; after a final release
higher than every `-preN` on `latest`, the newest final release. A first final backfilled below the `-preN` on `latest`
(`0.1.0` after `0.2.0-pre1`) leaves `latest` on that prerelease until a final higher than it is published.
**No dist-tag ever moves backwards.** Each package's tag is the first of D8's tag,
`release-<major>.<minor>` (for example `release-0.1`) and `release-<version>` (for example `release-0.1.8`) that does
not already name a higher version of that package (and, for a channel, that is ahead of `latest`). So a recovery of
version N after N+1, a maintenance release (0.1.8 after 0.2.0 and 0.1.9), or a package of N+1 published before the
others, never moves a tag down; the three packages of one version can end on different tags. Every npm publication of every version runs in one concurrency
group, so two releases never choose their tags at the same time.

**Version ordering (open question 8):** semver compares `preN`, `betaN` and `rcN` as text, so `0.1.0-pre100` sorts
below `0.1.0-pre99` (and `rc10` below `rc9`). From `pre100` on, a new release would be "older" than `pre99`: it would
go to `release-0.1`, and `latest` (or `preview`) would stay on `pre99`. The version scheme must change before `pre99` (ADR-077 open question 8).

**The npm files are not release assets.** The release has the same files as before npm (the engine archives and
their checksum file, the CLI and playground zips, the static playground and the shadow jars). The three npm tarballs,
their own copies of the two engine archives and `julc-npm-<version>-SHA256SUMS.txt` over all five are the tag run's
`npm-packages` artifact, kept **14 days**. `npm-dry-run`, `npm-publish` and `npm-verify` download it: from their own
run on a tag push; on a recovery dispatch, from the tag's push run (`julc-wasm/src/ci/npm-run.sh`: the run of this
workflow for the tag's push, at the tag's commit, whose `attest` succeeded in any attempt; exactly one must exist).
Then `julc-wasm/src/ci/npm-files.sh` verifies each of the five files with `gh attestation verify --repo bloxbean/julc
--signer-workflow bloxbean/julc/.github/workflows/native-image-release.yml --source-ref refs/tags/v<version>
--source-digest <tag commit> --signer-digest <workflow commit> --deny-self-hosted-runners` (the attestation lives in
GitHub's attestation store), and requires the published release to attach the same two engine archives (npm gets only
versions whose archives are public). The scripts then check the checksum file against all five files and, in the
tag's own run, against the `wasm` job's digests. The release's other artifacts (`release-*`) are kept 14 days too, for
completing a release by hand.

## Who can publish

`npm-publish` runs in `release-staging`, release-ops' environment for release jobs (the one JOUST's npm job and this
workflow's `update-homebrew` use; it holds the Maven Central credentials of `release.yml` too). It admits `v*` tags,
`main` and `release/**`, with no reviewers. npm's trusted publisher checks the workflow file and the environment, not
the ref, so what keeps publication to release tags is:
- `npm-publish`'s first step, which fails unless the run's ref is exactly `refs/tags/v<version>` (and `npm-gates`
  before it, which refuses any ref that is not a `v*` tag matching `gradle.properties` there). This is the control
  against a dispatch from `main` or `release/**`, which the environment admits;
- the tag ruleset (who may create `v*` tags) and `tag-release.yml`'s `authorize` job, which needs a release owner's
  approval in the `release` environment before a release tag is created. That is the normal route. By release-ops'
  intentional exception, an org admin can bypass the tag ruleset and environment approvals and create a `v*` tag
  directly; the npm jobs check the tag and the version, not how the tag was approved;
- `main` and `release/**` take changes only through pull requests with code-owner review, and CODEOWNERS covers the
  workflows and `julc-wasm/src/ci`, the scripts `npm-publish` runs.

So `release-staging`'s exposure for npm is the same as for the Maven Central credentials it already holds. If the
environment ever refuses a release tag, fix it in Settings and use "Re-run failed jobs": the environment's settings are
read when the job starts, not fixed in the tag's workflow.

## One-time setup, before the first real publication

Steps 1 to 3 can be done any time; steps 4 and 5 only when the first real release is ready, because of the 2-day
expiry.

1. **Rehearse** (see "Rehearsal" below): a release candidate of a prerelease (`X.Y.Z-preN-rcN`) with
   `github_release = true`.
2. **Publish a placeholder of each name, by hand, with 2FA** (a package must exist before a trusted publisher can be
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
6. **After the first real publication**, check that `npm-verify` passed (registry contents, `npm audit signatures`,
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

On a release candidate of a prerelease (`X.Y.Z-preN-rcN`, JuLC's usual candidate naming), with
`github_release = true` and `npm_publish` either way (such a candidate is always a dry run; an `X.Y.Z-rcN` candidate of
a final version is published, to `next`):
1. The tag's run: `wasm` uploads the `npm-packages` artifact (14 days) with `julc-npm-<version>-SHA256SUMS.txt`, and
   its five digest outputs; `attest` makes the attestation; `github-release` attaches no npm file and its final check
   passes; `npm-dry-run` verifies all five files' attestation and the release's archives, and lists each package's
   files with the tag each would get; `npm-publish` and `npm-verify` are skipped.
2. By hand, on the downloaded artifact (`gh run download <run id> --repo bloxbean/julc -n npm-packages`):
   `gh attestation verify julclang-wasm-client-<version>.tgz --repo bloxbean/julc
   --signer-workflow bloxbean/julc/.github/workflows/native-image-release.yml --source-ref refs/tags/v<version>
   --source-digest <tag commit>` passes, and with another `--source-digest` fails.
3. **A dispatch from a branch must fail the gate:** `gh workflow run native-image-release.yml --ref main`; `npm-gates`
   fails and nothing else runs.
4. **A dispatch on the candidate's tag** runs only `npm-gates` and `npm-dry-run`, which finds the tag's push run,
   downloads its `npm-packages` artifact by id and verifies it. Expected: `npm-run.sh` prints the run and artifact
   ids; the download puts the six files directly in `assets/` (not in a subdirectory); `npm-files.sh` prints
   "the five files are one set, run <id>" and "public: …" for both archives. Only a real dispatch exercises the
   by-id download.
5. **A whole-workflow re-run** of the candidate's run stops in `wasm` ("already has a GitHub release"), building
   nothing.
6. **The ref check:** a dispatch from `main` (step 3) fails in `npm-gates`; `npm-publish`'s own exact-ref check is
   covered by `workflow-steps-test.py` (main, `release/**`, another tag, a branch named like the tag).
7. **Checks that need a modified workflow** (a deliberately failed `attest`, a failed upload): never on the real
   repository, where they would leave a permanent `v*` tag and release made by a modified workflow. Run them in a
   fork or a scratch repository, with `npm_publish = false` (dry-run mode) and no trusted publisher: for example
   `run: exit 1` before the attest step must leave `github-release` and `npm-dry-run` skipped and no release created.

## Each release

1. Decide `github_release` and `npm_publish` in `gradle.properties` on the release commit. `npm_publish = true` needs
   `github_release = true`; npm publishes only versions whose engine archives the GitHub release attaches. The gates
   are fixed at the tag (above).
   **Never create a GitHub release, or a draft, for the tag by hand** before the run: `wasm` stops when the tag already
   has a release ("built once"), and `github-release` refuses a draft.
2. Tag as usual (`tag-release.yml`, with a release owner's approval). The tag push runs the whole workflow.
3. A published version can never be replaced, unpublished for reuse, or published again with other bytes. Check the
   release notes (D7: every API change is listed) before setting `npm_publish = true`.
4. The release is public from the moment `github-release` creates it, while its assets upload (a few minutes),
   possibly with the Latest badge (a final release): the accepted cost of never using drafts, which read-only checks
   cannot see. Release notes and announcements should wait for the run to finish.

## Recovery

A release is built once. Web Image output is not byte-identical across builds, so the workflow never rebuilds a
version that has a GitHub release: `wasm` stops first, with a message pointing here. Never delete release assets or
recreate a release to "start again"; npm is the record once a version is published.

**The recovery window is 14 days from the tag's run**: npm is published only from that run's `npm-packages`
artifact. To tell whether a version's npm files are still available:
```
gh run list --repo bloxbean/julc --workflow native-image-release.yml --event push --branch v<version> --json databaseId,headSha,conclusion
gh api repos/bloxbean/julc/actions/runs/<run id>/artifacts?name=npm-packages --jq '.artifacts[] | "\(.expired) \(.expires_at)"'
```
`false <date>` means available until that date. Nothing, or `true`, means they are gone: that version can no longer be
published to npm (a dispatch fails with "the npm files of v<version> are no longer available"); release the next one.
A dispatch that says "no run … has a successful attestation yet" is not expiry: the push run's `attest` failed or has
not run; use "Re-run failed jobs" in that run, then dispatch again. "Cannot read the run history" is a GitHub API
failure: dispatch again later.
After expiry, "Re-run failed jobs" fails in its download step with the download action's own generic error, not this
message: run the check above. If some packages of the version were published before the window closed (the client,
say) and the rest can no longer be, deprecate the published ones by hand (`npm deprecate @julclang/<name>@<version>
"incomplete release; use <next>"`, with 2FA) and release the next version.

A version already on npm stays checkable after its artifact expired, since its attestation lives in GitHub's
attestation store: `npm pack @julclang/<name>@<version>` and then `gh attestation verify <the .tgz> --repo
bloxbean/julc --signer-workflow bloxbean/julc/.github/workflows/native-image-release.yml --source-ref
refs/tags/v<version>`, plus `npm audit signatures` in a project that installs it.

- **Within the original run** (any job failed, before any full re-run): use **"Re-run failed jobs"**. It keeps the run's
  artifacts and the `wasm` job's digest outputs. `github-release` finds the release, keeps assets already attached
  with the same bytes, deletes a failed upload's `starter` placeholder and uploads it again, stops if a completed
  asset differs, and finally checks the release is published with every file's bytes. `npm-publish` skips each
  version already on npm with the same tarball and this workflow's provenance, and publishes the rest.
- **After a whole-workflow re-run** (refused in `wasm`, since the tag has a release): "Re-run failed jobs" can no longer
  reach the original attempt. Finish the release by hand from the original run's artifacts (kept 14 days; the bytes
  are the ones its digests and checksum files name), never replacing a completed asset, then dispatch on the tag for
  npm (which reads the run's `npm-packages` artifact, not the release):
  `gh run download` puts each artifact in its own directory (`dist/release-wasm/…`, `dist/release-cli-…/…`). Download
  into a new, empty directory, and attach the files found under it:
  ```
  rm -rf dist && gh run download <original run id> --repo bloxbean/julc --pattern 'release-*' --dir dist
  gh api repos/bloxbean/julc/releases/tags/v<version> --jq '.assets[] | "\(.id) \(.name) \(.state)"'
  gh api -X DELETE repos/bloxbean/julc/releases/assets/<id of each asset in state starter>
  find dist -type f                                          # the files; attach those the release lacks:
  gh release upload v<version> <each missing file, as listed by find> --repo bloxbean/julc      # never --clobber
  ```
  Then check the release against the release's inventory (the 14 files `github-release` attaches: 4 CLI zips, 4
  playground zips, 2 shadow jars, the 2 engine archives, the static playground and `julc-wasm-<version>-SHA256SUMS.txt`)
  and the original run's files, as the workflow's final check does. It prints `ok` for each file and fails
  (non-zero) on a missing tool, a draft, a duplicate file name, a file missing from the artifacts or the release, a
  differing asset, or anything outside the inventory:
  ```
  (
    set -euo pipefail
    version=<version>
    if command -v sha256sum > /dev/null; then sha() { sha256sum "$1" | cut -d' ' -f1; }
    elif command -v shasum > /dev/null; then sha() { shasum -a 256 "$1" | cut -d' ' -f1; }
    else echo "needs sha256sum or shasum"; exit 1; fi
    command -v jq > /dev/null || { echo "needs jq"; exit 1; }
    work=$(mktemp -d)
    for label in macos-aarch64 linux-x86_64 linux-arm64 windows-x86_64; do
      echo "julc-${version}-${label}.zip"; echo "julc-playground-${version}-${label}.zip"
    done > "$work/expected.txt"
    printf '%s\n' "julc-compiler-all-${version}-all.jar" "julc-vm-java-all-${version}-all.jar" \
      "julc-wasm-${version}.tar.gz" "julc-vm-wasm-${version}.tar.gz" "julc-playground-static-${version}.tar.gz" \
      "julc-wasm-${version}-SHA256SUMS.txt" >> "$work/expected.txt"
    sort -o "$work/expected.txt" "$work/expected.txt"
    gh api "repos/bloxbean/julc/releases/tags/v${version}" > "$work/release.json"
    [ "$(jq -r .draft "$work/release.json")" = false ] || { echo "v${version}'s release is a draft"; exit 1; }
    jq -r '.assets[] | "\(.name) \(.state) \(.digest)"' "$work/release.json" | sort > "$work/release.txt"
    find dist -type f | while read -r f; do echo "$(basename "$f") $(sha "$f")"; done | sort > "$work/files.txt"
    dupes=$(cut -d' ' -f1 "$work/files.txt" | uniq -d)
    [ -z "$dupes" ] || { echo "file names found twice under dist: $dupes"; exit 1; }
    failed=0
    for side in files release; do
      if ! diff <(cut -d' ' -f1 "$work/$side.txt") "$work/expected.txt" > "$work/$side.diff"; then
        echo "the $side differ from the inventory (< not in it, > missing):"; cat "$work/$side.diff"; failed=1
      fi
    done
    while read -r name sha; do
      line=$(awk -v n="$name" '$1 == n' "$work/release.txt")
      if [ "$line" = "$name uploaded sha256:$sha" ]; then echo "ok $name"; else echo "DIFFERENT $name: ${line:-missing}"; failed=1; fi
    done < "$work/files.txt"
    exit "$failed"
  )
  ```
  The npm jobs also require the release's engine archives to equal the attested ones in `npm-packages`, so a wrong
  archive is refused there.
  `update-homebrew` does not run on this path (nor on a dispatch). Update the formulas by hand: in
  `bloxbean/homebrew-tap`, set `version` and each `sha256` in `Formula/julc.rb` and `Formula/julc-playground.rb` to
  the release's zips (`sha256sum` of the macOS, Linux x86_64 and Linux arm64 assets), as the job's script does.
  The artifacts expire 14 days after the original run: after that, a release missing assets cannot be completed with
  its original bytes, nor its npm packages published, and the version is spent (release the next one).
- **The first real publication fails at authentication** (npm answers 401, 403 or 404 on `PUT`, or "OIDC token
  exchange" fails in the log): nothing was published. Fix the trusted publisher (repository, workflow file name,
  environment `release-staging`, direct publishing allowed, not expired) and use "Re-run failed jobs".
- **After a refused full re-run, or once the original run can no longer be re-run**, within 14 days of the tag's run:
  dispatch the workflow **on the tag**:
  ```
  gh workflow run native-image-release.yml --repo bloxbean/julc --ref v<version>
  ```
  Anyone with write access to the repository can dispatch. The controls are the exact-ref checks in `npm-gates` and
  `npm-publish` (the ref must be exactly the release tag, matching `gradle.properties` at it), and that a `v*` tag
  is normally created only through the tag ruleset and `tag-release.yml`'s release-owner authorisation, with
  release-ops' org-admin exception ("Who can publish"). The dispatch skips every build job, `attest`,
  `github-release` and `update-homebrew`, downloads the tag's push run's `npm-packages` artifact, verifies it against
  its attestation and the release's archives, and runs `npm-publish` (or `npm-dry-run`) and `npm-verify` on it. A
  dispatch from a branch, on a tag that does not match `gradle.properties`, or after the artifact expired, is
  refused.
- **Concurrency:** one `npm-publish` runs at a time across all versions (`npm-publish-julclang`), and a running one is
  never cancelled. GitHub keeps one waiting job per group: if a third release's job arrives while one runs and one
  waits, the newer waiting job **cancels** the older waiting one. That run shows `npm-publish` as cancelled (and
  `npm-verify` skipped); its release is complete and published, so finish it by dispatching the workflow on its tag
  within 14 days (it waits for the group like any other).
- **A stale channel** (for example `next` naming `0.2.0-rc1` after `0.2.0` is out): optionally move it by hand, with
  2FA and publications quiesced ("Dist-tags by hand"): `npm dist-tag add @julclang/<name>@<final> next` for each of
  the three packages; the trusted publisher has no dist-tag permission.
- **Recovering an older version after a newer one is out** publishes it on the first tag that does not move backwards
  (above) and leaves `latest` on the newer version; `npm-verify` checks that each tag the run chose still names that
  version or a later one.
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
