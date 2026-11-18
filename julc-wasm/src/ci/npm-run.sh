#!/usr/bin/env bash
# ADR-077 D8a: the run whose npm-packages artifact a recovery dispatch on a release tag publishes from: the run of
# native-image-release.yml for the tag's push (event push, head branch v<version>, head commit the tag's commit) whose
# attest job succeeded, in any attempt. Exactly one such run must exist (a release is built once, so a second run stops
# before attest), and its npm-packages artifact must not have expired (artifacts are kept 14 days).
#
#   npm-run.sh <version>        writes run_id=<id> to $GITHUB_OUTPUT (or stdout)
#
# Environment: GH_TOKEN (actions: read); GITHUB_REPOSITORY; SOURCE_DIGEST, the tag's commit (github.sha).
set -euo pipefail
version=$1
: "${GITHUB_REPOSITORY:?}" "${SOURCE_DIGEST:?}"
gone() {
  echo "::error::The npm files of v${version} are no longer available (artifacts are kept 14 days): $1." \
    "This version can no longer be published to npm." >&2
  exit 1
}
runs=$(gh api --paginate \
  "repos/${GITHUB_REPOSITORY}/actions/workflows/native-image-release.yml/runs?event=push&head_sha=${SOURCE_DIGEST}&per_page=100" \
  --jq ".workflow_runs[] | select(.head_branch == \"v${version}\") | .id")
attested=()
for run in $runs; do
  if [[ -n "$(gh api --paginate "repos/${GITHUB_REPOSITORY}/actions/runs/${run}/jobs?filter=all&per_page=100" \
      --jq '.jobs[] | select(.name == "Attest the npm tarballs and engine archives" and .conclusion == "success") | .id')" ]]; then
    attested+=("$run")
  fi
done
if [[ ${#attested[@]} -eq 0 ]]; then
  gone "no run of native-image-release.yml for the push of v${version} (${SOURCE_DIGEST}) attested them"
fi
if [[ ${#attested[@]} -gt 1 ]]; then
  echo "::error::Several runs for the push of v${version} attested npm files (${attested[*]}); a release is built once," \
    "so this needs a maintainer to decide which run's files are the release's." >&2
  exit 1
fi
run=${attested[0]}
live=$(gh api "repos/${GITHUB_REPOSITORY}/actions/runs/${run}/artifacts?name=npm-packages" \
  --jq '.artifacts[] | select(.expired == false) | .id')
if [[ -z "$live" ]]; then
  gone "run ${run}'s npm-packages artifact has expired or was deleted"
fi
echo "npm files of v${version}: run ${run}, artifact ${live}"
echo "run_id=${run}" >> "${GITHUB_OUTPUT:-/dev/stdout}"
